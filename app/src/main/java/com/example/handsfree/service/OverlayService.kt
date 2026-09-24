package com.example.handsfree.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.handsfree.BuildConfig
import com.example.handsfree.R
import com.example.handsfree.pipeline.AppStateMachine
import com.example.handsfree.pipeline.AudioPreprocessor
import com.example.handsfree.pipeline.CommandExecutor
import com.example.handsfree.pipeline.KeywordSpotter
import com.example.handsfree.pipeline.LevenshteinMatcher
import com.example.handsfree.pipeline.PipelineWatchdog
import com.example.handsfree.pipeline.SpeechEngine
import com.example.handsfree.pipeline.TranslationLayer
import com.example.handsfree.pipeline.WakeWordDetector
import com.example.handsfree.pipeline.WhisperOnnxEngine
import com.example.handsfree.util.Copy
import com.example.handsfree.util.CrashLog
import com.example.handsfree.util.PixelCircleDrawable
import com.example.handsfree.util.UiStyle
import com.example.handsfree.util.pixelUnit
import com.example.handsfree.util.setPixelFill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the HandsFree pipeline:
 *
 *   AudioPreprocessor -> SpeechEngine -> TranslationLayer
 *       -> LevenshteinMatcher -> CommandExecutor -> AccessibilityService
 *
 * The demo uses Karl's fixed Tagalog Whisper ONNX export. The model is prepared
 * off the main thread before the activation controls can start microphone work.
 *
 * Activation model. The microphone is not held open indefinitely: that drains
 * battery and unnerves users. Instead the service idles until woken by either
 * the floating bubble or a volume-down hold, listens for one utterance, acts,
 * and returns to idle.
 *
 * No network calls anywhere. Everything is on-device.
 */
class OverlayService : Service() {

    private lateinit var audioPreprocessor: AudioPreprocessor
    private lateinit var speechEngine: SpeechEngine
    private lateinit var translationLayer: TranslationLayer
    private lateinit var commandExecutor: CommandExecutor
    private var matcher: LevenshteinMatcher? = null

    private lateinit var keywordSpotter: KeywordSpotter
    private var kwsBuffer = ShortArray(0)
    private var kwsPeak = 0f
    private var lastKwsLog = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val stateMachine = AppStateMachine()
    private lateinit var watchdog: PipelineWatchdog

    /**
     * Always-on call-sign mode. Off unless the user turns it on. Loaded from
     * [PREF_WAKE_WORD] in onCreate and saved by [setWakeWordEnabled], so a
     * service restart keeps what the main screen shows.
     */
    private var wakeWordEnabled = false

    private var activeSession = 0L

    @Volatile private var listenStartedAt = 0L

    /**
     * An explicit press that arrived while the model was still loading. The
     * first press after the app starts the service used to be dropped, which
     * reads as a dead button; now it starts listening once the model is ready.
     */
    @Volatile private var pendingStartWhenReady = false

    private lateinit var announcer: Announcer

    /** True while a feedback line plays. The capture callback drops audio then. */
    @Volatile private var announcing = false
    private var announceToken = 0

    /**
     * True while the popup shows a command result or the retry message. The
     * IDLE status that follows a result must leave it up for its linger time;
     * every other IDLE dismisses the popup.
     */
    private var popupShowsResult = false

    private val wakeWordDetector = WakeWordDetector()

    private val rearmRunnable = Runnable {
        if (wakeWordEnabled && stateMachine.isIdle && !stateMachine.isKwsListening) {
            startKwsListening()
        }
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleLabel: android.widget.ImageView? = null

    private var popupView: View? = null
    private var popupStatusText: TextView? = null
    private var popupTranscriptionText: TextView? = null
    private var popupIntentText: TextView? = null
    private var popupWarningText: TextView? = null

    private var glowView: EdgeGlowView? = null

    private val hidePopupRunnable = Runnable {
        dismissAssistantPopup()
    }

    private val sleepRunnable = Runnable {
        if (stateMachine.isListening) {
            Log.i(TAG, "listening window elapsed: returning to idle")
            stopListening("Timed out after ${listenWindowMs() / 1000}s, VAD never fired")
        }
    }

    interface PipelineListener {
        fun onTranscriptionResult(rawTranscribed: String, normalized: String)
        fun onPartialResult(partial: String) {}
        fun onStatusChanged(status: String)
        fun onPipelineError(message: String)
        /**
         * Result of executing a matched command. [warning] is non-null when the
         * command ran but something outside the app will spoil it: today only
         * a web search with no connection.
         */
        fun onCommandExecuted(
            intent: String,
            success: Boolean,
            message: String,
            warning: String? = null
        ) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        CrashLog.rotate(this, BuildConfig.BUILD_STAMP)
        running = true
        CrashLog.step(this, "STEP_0: OverlayService.onCreate")
        wakeWordEnabled = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_WAKE_WORD, false)
        CrashLog.info(this, "call sign ${if (wakeWordEnabled) "on" else "off"} at start")

        runCatching { startForegroundCompat(buildNotification("HandsFree starting…")) }
            .onFailure { CrashLog.exception(this, "STEP_1 startForeground", it); throw it }

        runCatching {
            val file = java.io.File(filesDir, "transcript.log")
            if (file.exists()) {
                val content = file.readText()
                if (content.isNotBlank()) {
                    val entries = content.split("|||")
                    transcriptHistory.clear()
                    transcriptHistory.addAll(entries.take(HISTORY_LIMIT))
                }
            }
        }.onFailure { Log.e(TAG, "Failed to load transcript history", it) }

        audioPreprocessor = AudioPreprocessor(this)
        announcer = Announcer(this)
        commandExecutor = CommandExecutor(this)
        keywordSpotter = KeywordSpotter(this)

        scope.launch {
            keywordSpotter.prepare(this@OverlayService)
        }

        translationLayer = runCatching {
            TranslationLayer.fromAssets(
                assets.open(LEXICON_FILE).bufferedReader().use { it.readText() }
            )
        }.getOrElse {
            CrashLog.exception(this, "lexicon load (empty fallback)", it)
            TranslationLayer.fromAssets("{\"entries\":{}}")
        }

        matcher = runCatching {
            LevenshteinMatcher.fromAssets(
                assets.open(VOCAB_FILE).bufferedReader().use { it.readText() }
            )
        }.getOrNull()

        watchdog = PipelineWatchdog(
            stateMachine = stateMachine,
            listenTimeoutMs = { listenWindowMs() + LISTEN_GRACE_MS },
            onExpiry = { stuckState ->
                Log.e(TAG, "pipeline watchdog expired in state $stuckState")
                CrashLog.info(this@OverlayService, "pipeline watchdog expired in state $stuckState")
                mainHandler.post {
                    mainHandler.removeCallbacks(sleepRunnable)
                    checkStopCapture()
                    updateBubble("🎤", 0xFF1E88E5.toInt())
                    if (stuckState == AppStateMachine.State.LISTENING) {
                        emitStatus(STATUS_IDLE)
                    } else {
                        showRetryMessage()
                    }
                    scheduleRearm()
                }
            }
        )

        speechEngine = createEngine()
        emitStatus(STATUS_PREPARING)

        speechEngine.onReady = {
            Log.i(TAG, "${speechEngine.displayName} ready")
            CrashLog.info(this@OverlayService, "engine ready: ${speechEngine.displayName}")
            emitStatus(STATUS_IDLE)
            // onReady runs on the engine's init thread. Window work must run on
            // the main thread: adding the bubble from here threw "Can't create
            // handler inside thread" (seen in logcat 2026-09-24), so the bubble
            // never appeared.
            mainHandler.post {
                showBubble()
                if (pendingStartWhenReady) {
                    pendingStartWhenReady = false
                    startListening(explicit = true)
                } else if (wakeWordEnabled && stateMachine.isIdle && !stateMachine.isKwsListening) {
                    startKwsListening()
                }
            }
        }
        speechEngine.onError = { e -> handleBackendError(e) }
        speechEngine.prepare("tl")
    }

    /** The demo has one speech backend: Karl's fixed Tagalog Whisper export. */
    private fun createEngine(): SpeechEngine {
        Log.i(
            TAG,
            "using Whisper ONNX engine (present=${WhisperOnnxEngine.MODEL_PRESENT}, " +
                "verified=${WhisperOnnxEngine.INFERENCE_IMPLEMENTED})"
        )
        return WhisperOnnxEngine(this)
    }

    private fun startListening(explicit: Boolean = true, awaitingCommand: Boolean = false) {
        if (!speechEngine.isReady) {
            if (explicit) pendingStartWhenReady = true
            Log.w(TAG, "Deferred activation: speech engine not ready (starts when ready: $explicit)")
            CrashLog.info(this, "activation deferred: speech engine not ready")
            return
        }
        val previousKws = stateMachine.isKwsListening
        val session = stateMachine.activate(explicit, awaitingCommand)
        if (session == null) {
            Log.w(TAG, "Rejected activation: pipeline is in state ${stateMachine.currentState}")
            CrashLog.info(this, "rejected activation: state is ${stateMachine.currentState}")
            return
        }
        activeSession = session
        CrashLog.info(this, "activation accepted, session $session")
        if (previousKws) {
            Log.i(TAG, "switched from KWS to Whisper")
            kwsBuffer = ShortArray(0)
        }
        emitStatus(STATUS_LISTENING)
        updateBubble("●", 0xFFE53935.toInt())
        // The mic starts now, whatever else happens, so the first word is not
        // lost to microphone start-up. With spoken feedback on, a short beep
        // plays and audio is dropped for its ~160 ms only (1.7.1). 1.7.0 spoke
        // "Listening" and opened the mic after it, about a second late, and
        // testers' first words were cut off ("san mo ang youtube").
        if (UiStyle.speechEnabled(this)) {
            cancelAnnouncement()
            announcing = true
            val token = announceToken
            beginCapture()
            announcer.cue {
                if (token == announceToken) {
                    announcing = false
                    audioPreprocessor.resetVad()
                }
            }
        } else {
            beginCapture()
        }
    }

    /**
     * Open the listening window: reset the silence detector, start capture,
     * and arm the mic-window timer. Runs straight after activation.
     */
    private fun beginCapture() {
        listenStartedAt = android.os.SystemClock.elapsedRealtime()
        audioPreprocessor.resetVad()
        startCaptureIfNeeded()
        mainHandler.removeCallbacks(sleepRunnable)
        mainHandler.postDelayed(sleepRunnable, listenWindowMs())
    }

    /**
     * Speak a feedback line (W7). While it plays, [announcing] is true and the
     * capture callback drops every buffer, so neither Whisper nor the
     * call-sign detector hears the app's own voice. [then] runs once, when the
     * line ends, or after [ANNOUNCE_TIMEOUT_MS] if the speech engine never
     * reports back. A newer line replaces an older one; the older line's
     * [then] is dropped.
     */
    private fun announce(text: String, tagalog: Boolean = speakTagalog(), then: (() -> Unit)? = null) {
        val token = ++announceToken
        announcing = true
        var fired = false
        val finish = {
            if (!fired && token == announceToken) {
                fired = true
                announcing = false
                then?.invoke()
            }
        }
        mainHandler.postDelayed({ finish() }, ANNOUNCE_TIMEOUT_MS)
        announcer.speak(text, tagalog) { finish() }
    }

    /** Cancel any line being spoken and release the capture gate. */
    private fun cancelAnnouncement() {
        announceToken++
        announcing = false
        announcer.stop()
    }

    /**
     * The line to speak for [key]. Tagalog only when the tester chose Tagalog
     * and an offline Filipino voice is installed; Tagalog read by an English
     * voice is hard to follow, so otherwise the English line is used.
     */
    private fun say(key: Copy.Key): String =
        getString(if (speakTagalog()) key.tl else key.en)

    private fun speakTagalog(): Boolean =
        Copy.lang(this) == Copy.Lang.TL && announcer.speaksFilipino

    /**
     * How long the mic stays open when the VAD never decides the utterance
     * ended. Read from preferences on every activation rather than cached, so
     * moving the slider takes effect on the next command with no restart.
     *
     * This is a cap, not a wait: a normal command ends on trailing silence
     * long before it. It only bites when the VAD cannot tell you stopped:
     * either the room stayed above AudioPreprocessor's VAD_THRESHOLD, or the
     * speaker was quiet enough that speech never registered at all, in which
     * case the silence counter is never even armed. Testers hit that second
     * case and sat through the entire window in silence.
     */
    private fun listenWindowMs(): Long =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(PREF_LISTEN_WINDOW_S, DEFAULT_LISTEN_WINDOW_S)
            .coerceIn(MIN_LISTEN_WINDOW_S, MAX_LISTEN_WINDOW_S) * 1000L

    private fun stopListening(reason: String) {
        if (!stateMachine.isListening) return
        mainHandler.removeCallbacks(sleepRunnable)
        updateBubble("🎤", 0xFF1E88E5.toInt())
        Log.i(TAG, "stopped listening: $reason")
        CrashLog.info(this, "listening ended: $reason")
        val session = activeSession
        if (!stateMachine.transitionIfSession(session, AppStateMachine.State.PREPROCESSING)) {
            stateMachine.reset()
            checkStopCapture()
            emitStatus(STATUS_IDLE)
            scheduleRearm()
            return
        }
        emitStatus(STATUS_TRANSCRIBING)
        checkStopCapture()
        flushWhisperUtterance(session)
    }

    /**
     * Return to IDLE for [session] with no command result, and dismiss the
     * popup. Does nothing if the session was already abandoned: whoever
     * abandoned it has already cleaned up.
     */
    private fun endSessionWithoutResult(session: Long, why: String) {
        if (!stateMachine.transitionIfSession(session, AppStateMachine.State.IDLE)) return
        CrashLog.info(this, "session $session ended with no command: $why")
        emitStatus(STATUS_IDLE)
        scheduleRearm()
    }

    private fun dropStaleResult(session: Long, what: String) {
        Log.w(TAG, "dropped $what from session $session (current ${stateMachine.session})")
        CrashLog.info(this, "dropped $what from session $session, current is ${stateMachine.session}")
    }

    private fun checkStopCapture() {
        if (!stateMachine.isListening && !stateMachine.isKwsListening) {
            audioPreprocessor.stopCapture()
        }
    }

    /**
     * Whisper emits one result after the listening window, not per audio chunk.
     * The result is used only if [session] is still current: after the
     * inference cap expires, a late transcript would otherwise run a command
     * the user no longer expects.
     */
    private fun flushWhisperUtterance(session: Long) {
        val whisper = speechEngine as? WhisperOnnxEngine ?: run {
            endSessionWithoutResult(session, "no Whisper engine")
            return
        }
        scope.launch {
            val raw = whisper.onUtteranceComplete()
            mainHandler.post {
                if (!stateMachine.isCurrentSession(session)) {
                    dropStaleResult(session, "transcript \"${raw.orEmpty()}\"")
                } else if (!raw.isNullOrBlank()) {
                    handleUtterance(raw, session)
                } else {
                    endSessionWithoutResult(session, "blank transcript")
                    if (UiStyle.speechEnabled(this@OverlayService)) {
                        announce(say(Copy.Key.SAY_NOT_UNDERSTOOD))
                    }
                }
            }
        }
    }

    /**
     * Open the mic for background keyword detection. No UI popup is shown.
     * When the keyword is detected, KWS capture is stopped and the full
     * Whisper pipeline is started.
     */
    private fun startKwsListening() {
        if (!stateMachine.isIdle || stateMachine.isKwsListening) return
        if (!keywordSpotter.isReady) {
            Log.i(TAG, "KWS models still loading - retrying in ${KWS_RETRY_MS}ms")
            mainHandler.postDelayed(
                { if (wakeWordEnabled && stateMachine.isIdle && !stateMachine.isKwsListening) startKwsListening() },
                KWS_RETRY_MS
            )
            return
        }
        if (!stateMachine.startKws()) return
        keywordSpotter.reset()
        emitStatus(STATUS_KWS_LISTENING)
        updateBubble("👂", 0xFF78909C.toInt())
        Log.i(TAG, "KWS listening started")

        kwsBuffer = ShortArray(0)
        startCaptureIfNeeded()
    }

    private fun stopKwsListening(reason: String = "manual") {
        if (!stateMachine.isKwsListening) return
        stateMachine.stopKws()
        Log.i(TAG, "KWS listening stopped: $reason")
        checkStopCapture()
    }

    private fun scheduleRearm() {
        if (!wakeWordEnabled) return
        mainHandler.removeCallbacks(rearmRunnable)
        mainHandler.postDelayed(rearmRunnable, REARM_DELAY_MS)
    }

    private fun setWakeWordEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_WAKE_WORD, enabled).apply()
        if (wakeWordEnabled == enabled) {
            if (enabled) scheduleRearm()
            return
        }
        wakeWordEnabled = enabled
        Log.i(TAG, "call-sign mode ${if (enabled) "on" else "off"}")
        CrashLog.info(this, "call sign set ${if (enabled) "on" else "off"}")
        mainHandler.removeCallbacks(rearmRunnable)
        stateMachine.setAwaitingCommand(false)
        if (enabled) {
            if (stateMachine.isIdle && !stateMachine.isKwsListening) startKwsListening()
        } else {
            if (stateMachine.isKwsListening) {
                stopKwsListening("call-sign mode off")
            } else if (stateMachine.isListening && !stateMachine.isExplicitActivation) {
                stopListening("call-sign mode off")
            }
        }
    }

    private fun applyWakeGate(raw: String): String? {
        val explicit = stateMachine.consumeExplicitActivation()
        val awaiting = stateMachine.consumeAwaitingCommand()
        if (!wakeWordEnabled || explicit || awaiting) {
            return raw
        }

        val hit = wakeWordDetector.detect(raw)
        if (hit == null) {
            Log.d(TAG, "no call sign in \"$raw\": ignoring")
            return null
        }

        if (hit.command.isBlank()) {
            Log.i(TAG, "call sign \"${hit.matched}\": awaiting command")
            stateMachine.setAwaitingCommand(true)
            emitStatus(STATUS_LISTENING)
            return null
        }

        Log.i(TAG, "call sign \"${hit.matched}\" + command \"${hit.command}\"")
        return hit.command
    }

    private fun startCaptureIfNeeded() {
        if (audioPreprocessor.isCapturing) return
        audioPreprocessor.startCapture(
            callback = { buffer ->
                if (announcing) {
                    Unit
                } else if (stateMachine.isKwsListening) {
                    handleKwsAudio(buffer)
                } else if (stateMachine.isListening) {
                    val raw = speechEngine.acceptWaveform(buffer)
                    if (raw != null && raw.isNotBlank()) {
                        val session = activeSession
                        mainHandler.post {
                            if (stateMachine.transitionIfSession(session, AppStateMachine.State.PREPROCESSING)) {
                                mainHandler.removeCallbacks(sleepRunnable)
                                handleUtterance(raw, session)
                            } else {
                                dropStaleResult(session, "streaming result")
                            }
                        }
                    } else {
                        val partial = speechEngine.getPartialResult()
                        if (partial.isNotEmpty()) emitPartial(partial)
                    }
                }
            },
            onSilenceDetected = silence@{
                // The app's own voice can trip the silence detector while a
                // line plays; beginCapture resets it once the line ends.
                if (announcing) return@silence
                val listenedMs = android.os.SystemClock.elapsedRealtime() - listenStartedAt
                if (stateMachine.isListening && listenedMs >= MIN_LISTEN_MS) {
                    mainHandler.post { stopListening("Silence detected (VAD) after ${listenedMs} ms") }
                } else if (stateMachine.isListening) {
                    Log.i(TAG, "ignored VAD silence ${listenedMs} ms into listening")
                }
            },
            onError = { error -> handleBackendError(error) }
        )
    }

    private fun handleKwsAudio(buffer: ShortArray) {
        val newBuffer = ShortArray(kwsBuffer.size + buffer.size)
        System.arraycopy(kwsBuffer, 0, newBuffer, 0, kwsBuffer.size)
        System.arraycopy(buffer, 0, newBuffer, kwsBuffer.size, buffer.size)
        kwsBuffer = newBuffer

        while (kwsBuffer.size >= KeywordSpotter.CHUNK_SAMPLES) {
            val chunk = kwsBuffer.copyOfRange(0, KeywordSpotter.CHUNK_SAMPLES)
            kwsBuffer = kwsBuffer.copyOfRange(KeywordSpotter.CHUNK_SAMPLES, kwsBuffer.size)

            val score = keywordSpotter.processAudio(chunk)

            if (score >= 0f) {
                kwsPeak = maxOf(kwsPeak, score)
                val now = System.currentTimeMillis()
                if (now - lastKwsLog >= KWS_LOG_INTERVAL_MS) {
                    Log.i(TAG, "KWS peak score %.4f (threshold %.2f)".format(kwsPeak, KWS_THRESHOLD))
                    lastKwsLog = now
                    kwsPeak = 0f
                }
            }

            if (score >= 0f && keywordSpotter.isKeywordDetected(score)) {
                Log.i(TAG, "KWS fired (score=$score) -> starting Whisper")
                mainHandler.post {
                    startListening(explicit = false, awaitingCommand = true)
                }
                break
            }
        }
    }

    private fun handleBackendError(error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        mainHandler.post {
            CrashLog.exception(this@OverlayService, "backend error", error)
            stateMachine.reset()
            mainHandler.removeCallbacks(rearmRunnable)
            mainHandler.removeCallbacks(sleepRunnable)
            runCatching { audioPreprocessor.stopCapture() }
            updateBubble("🎤", 0xFF1E88E5.toInt())
            emitStatus(STATUS_MODEL_ERROR)
            if (UiStyle.speechEnabled(this@OverlayService)) announce(say(Copy.Key.SAY_ERROR))
            emitError(message)
        }
    }

    /**
     * Full path from transcript to executed command. Every transition names
     * [session]; if the watchdog abandoned it in the meantime, the transition
     * is refused and the command is not executed.
     */
    private fun handleUtterance(raw: String, session: Long) {
        val spoken = applyWakeGate(raw) ?: run {
            endSessionWithoutResult(session, "no call sign")
            return
        }

        if (!stateMachine.transitionIfSession(session, AppStateMachine.State.NORMALIZING)) {
            dropStaleResult(session, "transcript \"$raw\"")
            return
        }
        val normalized = runCatching { translationLayer.normalize(spoken) }
            .getOrElse { spoken }.ifBlank { spoken }
        emitResult(raw, normalized)

        if (!stateMachine.transitionIfSession(session, AppStateMachine.State.PROCESSING)) {
            dropStaleResult(session, "normalized \"$normalized\"")
            return
        }
        val match = matcher?.classify(normalized)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

        if (match == null) {
            val intentStr = "No match"
            val resultMsg = "No matching command"
            val logEntry = "[$timestamp]\nNarinig: $raw\nSalin: $normalized\nIntent: $intentStr\nResult: FAILED - $resultMsg"
            recordTranscript(logEntry)

            emitCommand("NO MATCH", false, resultMsg)
            stateMachine.transitionIfSession(session, AppStateMachine.State.IDLE)
            emitStatus(STATUS_IDLE)
            scheduleRearm()
            return
        }

        val (category, phrase) = match
        val intentStr = "$category ($phrase)"

        if (!stateMachine.transitionIfSession(session, AppStateMachine.State.EXECUTING)) {
            dropStaleResult(session, "command $intentStr")
            return
        }
        val result = runCatching { commandExecutor.execute(category, normalized, phrase) }
            .getOrElse { CommandExecutor.Result(false, it.message ?: "Execution failed") }

        val statusStr = if (result.success) "SUCCESS" else "FAILED"
        val warningLine = result.warning?.let { "\nWarning: $it" }.orEmpty()
        val logEntry = "[$timestamp]\nNarinig: $raw\nSalin: $normalized\nIntent: $intentStr\n" +
            "Result: $statusStr - ${result.message}$warningLine"
        recordTranscript(logEntry)

        if (result.warning != null) {
            CrashLog.info(this, "$category ran with warning: ${result.warning}")
        }

        emitCommand(category, result.success, result.message, result.warning)
        stateMachine.transitionIfSession(session, AppStateMachine.State.IDLE)
        emitStatus(STATUS_IDLE)
        scheduleRearm()
    }

    private fun recordTranscript(entry: String) {
        transcriptHistory.add(0, entry)
        while (transcriptHistory.size > HISTORY_LIMIT) {
            transcriptHistory.removeAt(transcriptHistory.size - 1)
        }
        saveTranscriptHistory()
    }

    private fun saveTranscriptHistory() {
        runCatching {
            val file = java.io.File(filesDir, "transcript.log")
            val content = transcriptHistory.joinToString("|||")
            file.writeText(content)
        }.onFailure { Log.e(TAG, "Failed to save transcript history", it) }
    }

    /**
     * Small always-on-top button. Tapping it wakes the listener, so the demo
     * works even where volume-key capture is unavailable, and it makes the
     * service's state visible.
     */
    private fun showBubble() {
        if (bubbleView != null) return
        if (!UiStyle.bubbleEnabled(this)) {
            Log.i(TAG, "floating mic button turned off in Settings")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission missing: bubble unavailable")
            return
        }
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm

        val density = resources.displayMetrics.density
        val label = android.widget.ImageView(this).apply {
            val pad = (15 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = UiStyle.circle(this@OverlayService, BUBBLE_IDLE, POPUP_BORDER, POPUP_SHADOW, cells = 14)
            setImageResource(UiStyle.micIcon(this@OverlayService))
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            contentDescription = "HandsFree"
        }
        bubbleLabel = label
        val bubbleSize = (BUBBLE_SIZE_DP * density).toInt()

        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            x = prefs.getInt(UiStyle.PREF_BUBBLE_X, 24)
            y = prefs.getInt(UiStyle.PREF_BUBBLE_Y, 320)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        label.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) dragged = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm.updateViewLayout(label, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        startListening(explicit = true)
                    } else {
                        // Remember where the tester left it, so it comes back
                        // there after a restart instead of the top left.
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt(UiStyle.PREF_BUBBLE_X, params.x)
                            .putInt(UiStyle.PREF_BUBBLE_Y, params.y)
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(label, params)
            bubbleView = label
        }.onFailure {
            Log.e(TAG, "bubble add failed", it)
            CrashLog.exception(this, "bubble addView failed", it)
        }
    }

    /**
     * Repaint the bubble for the pipeline state. W3 replaced the emoji glyphs
     * with the pixel icons used on the main screen; callers still pass the
     * old glyph and tint, and the tint picks the state: red listening, grey
     * call-sign listening, anything else idle.
     */
    private fun updateBubble(@Suppress("UNUSED_PARAMETER") glyph: String, tint: Int) {
        mainHandler.post {
            val view = bubbleLabel ?: return@post
            val ctx = this@OverlayService
            val (icon, fill) = when (tint) {
                0xFFE53935.toInt() -> UiStyle.stopIcon(ctx) to BUBBLE_LISTENING
                0xFF78909C.toInt() -> UiStyle.callSignIcon(ctx) to BUBBLE_KWS
                else -> UiStyle.micIcon(ctx) to BUBBLE_IDLE
            }
            // The style switch can change while the bubble is up; swap its
            // background on the next state change.
            if ((view.background is PixelCircleDrawable) != UiStyle.isPixel(ctx)) {
                view.background = UiStyle.circle(ctx, fill, POPUP_BORDER, POPUP_SHADOW, cells = 14)
            }
            view.setImageResource(icon)
            view.setPixelFill(fill)
        }
    }

    private fun hideBubble() {
        val v = bubbleView ?: return
        runCatching { windowManager?.removeView(v) }
        bubbleView = null
        bubbleLabel = null
    }

    private fun showAssistantPopup() {
        mainHandler.removeCallbacks(hidePopupRunnable)
        popupShowsResult = false
        if (popupView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm

        val inflater = android.view.LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.popup_assistant, null)
        val unit = view.pixelUnit()
        view.background = UiStyle.frame(this, POPUP_FILL, POPUP_BORDER, POPUP_SHADOW, unit)
        view.findViewById<TextView>(R.id.popup_status_text)?.typeface = UiStyle.labelFont(this)
        view.findViewById<TextView>(R.id.popup_intent_text)?.typeface = UiStyle.bodyFont(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
            width = resources.displayMetrics.widthPixels - (2 * POPUP_SIDE_MARGIN_DP * resources.displayMetrics.density).toInt()
            windowAnimations = android.R.style.Animation_Dialog
        }

        runCatching {
            wm.addView(view, params)
            popupView = view
            popupStatusText = view.findViewById(R.id.popup_status_text)
            popupTranscriptionText = view.findViewById(R.id.popup_transcription_text)
            popupIntentText = view.findViewById(R.id.popup_intent_text)
            popupWarningText = view.findViewById(R.id.popup_warning_text)
        }.onFailure {
            Log.e(TAG, "popup add failed", it)
            CrashLog.exception(this, "popup addView failed", it)
            popupView = null
            popupStatusText = null
            popupTranscriptionText = null
            popupIntentText = null
            popupWarningText = null
        }
    }

    /**
     * Show the screen-edge glow, or change its state if it is already up.
     *
     * The window never takes touches: FLAG_NOT_TOUCHABLE, plus an alpha of
     * [GLOW_WINDOW_ALPHA]. From Android 12 the system blocks touches that
     * pass through an overlay window from another app when its alpha is
     * above 0.8, even when the window is not touchable. Without that cap the
     * glow would freeze the phone while HandsFree listens.
     *
     * Called only from the main handler, like the popup, so show, restyle and
     * remove cannot interleave.
     */
    private fun showGlow(state: EdgeGlowView.State) {
        glowView?.let { it.state = state; return }
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm
        val view = EdgeGlowView(this).apply { this.state = state }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            alpha = GLOW_WINDOW_ALPHA
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            val real = wm.maximumWindowMetrics.bounds
            width = real.width()
            height = real.height()
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching {
            wm.addView(view, params)
            glowView = view
        }.onFailure {
            Log.e(TAG, "glow add failed", it)
            CrashLog.exception(this, "glow addView failed", it)
        }
    }

    private fun hideGlow() {
        val v = glowView ?: return
        glowView = null
        runCatching { (windowManager ?: getSystemService(WindowManager::class.java))?.removeView(v) }
            .onFailure { CrashLog.exception(this, "glow removeView failed", it) }
    }

    private fun hideAssistantPopup(delayMs: Long = 0L) {
        mainHandler.removeCallbacks(hidePopupRunnable)
        if (delayMs <= 0L) {
            dismissAssistantPopup()
        } else {
            mainHandler.postDelayed(hidePopupRunnable, delayMs)
        }
    }

    private fun dismissAssistantPopup() {
        popupShowsResult = false
        hideGlow()
        val v = popupView ?: return
        val wm = windowManager ?: getSystemService(WindowManager::class.java)
        runCatching { wm?.removeView(v) }
            .onFailure {
                Log.e(TAG, "popup remove failed", it)
                CrashLog.exception(this, "popup removeView failed", it)
            }
        popupView = null
        popupStatusText = null
        popupTranscriptionText = null
        popupIntentText = null
        popupWarningText = null
    }

    private fun emitPartial(p: String) = mainHandler.post { 
        pipelineListener?.onPartialResult(p) 
        popupTranscriptionText?.text = if (p.isEmpty()) "..." else "$p..."
    }

    private fun emitResult(raw: String, norm: String) = mainHandler.post { 
        pipelineListener?.onTranscriptionResult(raw, norm) 
        popupTranscriptionText?.text = raw.ifBlank { "..." }
    }

    private fun emitStatus(s: String) = mainHandler.post { 
        pipelineListener?.onStatusChanged(s)
        when (s) {
            STATUS_PREPARING -> popupStatusText?.text = "Preparing..."
            STATUS_LISTENING -> {
                showGlow(EdgeGlowView.State.LISTENING)
                showAssistantPopup()
                popupStatusText?.text = "Listening..."
                popupTranscriptionText?.text = "..."
                popupIntentText?.visibility = View.GONE
                popupWarningText?.visibility = View.GONE
            }
            STATUS_KWS_LISTENING -> Unit
            STATUS_TRANSCRIBING -> {
                popupStatusText?.text = "Transcribing..."
                glowView?.state = EdgeGlowView.State.TRANSCRIBING
            }
            STATUS_IDLE -> if (!popupShowsResult) hideAssistantPopup(0L)
            STATUS_MODEL_ERROR -> {
                popupShowsResult = true
                glowView?.state = EdgeGlowView.State.FAILURE
                popupStatusText?.text = "Error"
                popupStatusText?.setTextColor(android.graphics.Color.RED)
                hideAssistantPopup(3000L)
            }
        }
    }

    private fun emitCommand(i: String, ok: Boolean, m: String, warning: String? = null) {
        Log.i(TAG, "command $i success=$ok :: $m${warning?.let { " :: WARN $it" }.orEmpty()}")
        mainHandler.post {
            pipelineListener?.onCommandExecuted(i, ok, m, warning)
            popupShowsResult = popupView != null
            glowView?.state = if (ok) EdgeGlowView.State.SUCCESS else EdgeGlowView.State.FAILURE
            popupIntentText?.visibility = View.VISIBLE
            popupIntentText?.text = if (ok) "$i: $m" else "$i FAILED: $m"
            popupIntentText?.setTextColor(if (ok) android.graphics.Color.parseColor("#81C784") else android.graphics.Color.parseColor("#E57373"))

            popupWarningText?.visibility = if (warning != null) View.VISIBLE else View.GONE
            popupWarningText?.text = warning

            val linger = if (warning != null) WARNING_LINGER_MS else RESULT_LINGER_MS
            hideAssistantPopup(linger)

            if (UiStyle.speechEnabled(this)) {
                when {
                    // CommandExecutor's result text is English ("Opening YouTube").
                    ok -> announce(m, tagalog = false)
                    i == "NO MATCH" -> announce(say(Copy.Key.SAY_NOT_UNDERSTOOD))
                    else -> announce(say(Copy.Key.SAY_FAILED))
                }
            }
        }
        runCatching { startForegroundCompat(buildNotification(warning ?: m)) }
    }

    /**
     * Shown when the inference cap expires. Tells the main screen the pipeline
     * is idle, then keeps the message in the popup for [RETRY_LINGER_MS].
     */
    private fun showRetryMessage() {
        val message = Copy.get(this, Copy.Key.POPUP_RETRY)
        pipelineListener?.onStatusChanged(STATUS_IDLE)
        if (popupView == null) showAssistantPopup()
        popupStatusText?.text = message
        popupTranscriptionText?.text = "..."
        popupIntentText?.visibility = View.GONE
        popupWarningText?.visibility = View.GONE
        popupShowsResult = popupView != null
        glowView?.state = EdgeGlowView.State.FAILURE
        hideAssistantPopup(RETRY_LINGER_MS)
        if (UiStyle.speechEnabled(this)) announce(say(Copy.Key.SAY_NOT_UNDERSTOOD))
        runCatching { startForegroundCompat(buildNotification(message)) }
    }

    private fun emitError(message: String) {
        Log.e(TAG, "error: $message")
        mainHandler.post { pipelineListener?.onPipelineError(message) }
        runCatching { startForegroundCompat(buildNotification("Error: $message")) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_ACTIVATE -> startListening(explicit = true)
            ACTION_START -> startListening(explicit = true)
            ACTION_ENSURE_RUNNING -> Unit
            ACTION_STOP_PIPELINE -> {
                cancelAnnouncement()
                mainHandler.removeCallbacks(rearmRunnable)
                CrashLog.info(this, "explicit stop in state ${stateMachine.currentState}")
                if (stateMachine.isKwsListening) {
                    stopKwsListening("explicit stop")
                }
                if (stateMachine.isListening) {
                    stopListening("explicit stop")
                } else {
                    stateMachine.reset()
                    mainHandler.removeCallbacks(sleepRunnable)
                    checkStopCapture()
                    updateBubble("🎤", 0xFF1E88E5.toInt())
                    emitStatus(STATUS_IDLE)
                    scheduleRearm()
                }
            }
            ACTION_SET_BUBBLE ->
                if (UiStyle.bubbleEnabled(this)) {
                    if (speechEngine.isReady) showBubble()
                } else {
                    hideBubble()
                }
            ACTION_SET_WAKE_WORD ->
                setWakeWordEnabled(intent.getBooleanExtra(EXTRA_ENABLED, false))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        running = false
        CrashLog.stopped(this)
        if (::watchdog.isInitialized) {
            watchdog.release()
        }
        wakeWordEnabled = false
        stateMachine.reset()
        mainHandler.removeCallbacks(rearmRunnable)
        mainHandler.removeCallbacks(sleepRunnable)
        hideAssistantPopup(0L)
        hideGlow()
        hideBubble()
        runCatching { audioPreprocessor.release() }
        runCatching { speechEngine.release() }
        runCatching { keywordSpotter.release() }
        if (::announcer.isInitialized) announcer.release()
        scope.cancel()
        emitStatus(STATUS_IDLE)
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HandsFree")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "HandsFree Overlay", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIF_ID, notification, type)
                return
            } catch (se: SecurityException) {
                Log.w(TAG, "microphone FGS rejected; using plain startForeground", se)
            }
        }
        startForeground(NOTIF_ID, notification)
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "handsfree_overlay"
        private const val NOTIF_ID = 1001
        private const val LEXICON_FILE = "translation-lexicon.json"
        private const val VOCAB_FILE = "intent-vocabulary.json"

        /**
         * Mic-open cap, in seconds, adjustable by the tester from the debug
         * screen. Was a hardcoded 8s.
         *
         * Default lowered to 4 after a tester reported speaking a command,
         * falling silent, and still waiting out the whole window before
         * anything happened. 8s was picked as a safe upper bound back when the
         * VAD was assumed to always fire; it is the *worst* case that testers
         * actually feel, so it is the one that matters.
         *
         * Kept adjustable rather than just hardcoded lower, because the right
         * value depends on how quickly a person starts speaking after
         * activating and we have no data on that. The chosen value is written
         * into the exported log, so the tester round produces that data.
         */
        const val PREF_LISTEN_WINDOW_S = "listen_window_seconds"
        const val DEFAULT_LISTEN_WINDOW_S = 4
        const val MIN_LISTEN_WINDOW_S = 3
        const val MAX_LISTEN_WINDOW_S = 10
        const val PREFS = "handsfree_prefs"

        const val PREF_WAKE_WORD = "wake_word_enabled"

        /**
         * Entries kept in the transcript log, newest first. Every one is a few
         * hundred bytes and the whole file is rewritten on each command, so the
         * cost of raising this is a rewrite of ~30 KB per utterance.
         */
        const val HISTORY_LIMIT = 75

        /**
         * Extra time past the mic window before the watchdog ends LISTENING.
         * The listen timer normally ends it first; this only catches a timer
         * that never ran.
         */
        const val LISTEN_GRACE_MS = 4000L

        /**
         * Longest wait for a feedback line to report that it finished. After
         * this the mic opens anyway, so a stuck speech engine cannot leave the
         * app deaf. The watchdog's listen cap includes [LISTEN_GRACE_MS],
         * raised from 2 s to 4 s in 1.7.0 to cover the "Listening" line.
         */
        private const val ANNOUNCE_TIMEOUT_MS = 3000L

        private const val RETRY_LINGER_MS = 2000L

        /**
         * Window alpha for the edge glow. Android 12+ blocks touches passing
         * through another app's overlay above 0.8 alpha. Do not raise it.
         */
        private const val GLOW_WINDOW_ALPHA = 0.8f

        /** Popup frame colours. Always a dark panel, whatever the theme. */
        private const val POPUP_FILL = 0xF216162A.toInt()
        private const val POPUP_BORDER = 0xFFE6E6F0.toInt()
        private const val POPUP_SHADOW = 0x99000000.toInt()

        /** Gap between the popup and each side of the screen, so its frame reads. */
        private const val POPUP_SIDE_MARGIN_DP = 16

        /** Floating bubble: size and the fill for each state. */
        private const val BUBBLE_SIZE_DP = 60
        private const val BUBBLE_IDLE = 0xFF4263EB.toInt()
        private const val BUBBLE_LISTENING = 0xFFF03E3E.toInt()
        private const val BUBBLE_KWS = 0xFF495057.toInt()

        /**
         * Trailing silence cannot end a session sooner than this after
         * activation. Backstop for [AudioPreprocessor.resetVad]: the reset is
         * applied on the next audio chunk, so one chunk of old state can
         * still slip through.
         */
        private const val MIN_LISTEN_MS = 1000L

        /** How long the result popup stays on screen once the command has run. */
        private const val RESULT_LINGER_MS = 3000L
        private const val WARNING_LINGER_MS = 6000L

        /**
         * Pause between an utterance being handled and the next capture in
         * always-on mode. Long enough for `AudioRecord` to be released and
         * re-acquired; short enough that a command spoken straight after the
         * call sign is not clipped.
         */
        private const val REARM_DELAY_MS = 600L

        /** Poll interval while the KWS models are still loading. */
        private const val KWS_RETRY_MS = 500L

        /** How often the peak KWS score is logged while listening. */
        private const val KWS_LOG_INTERVAL_MS = 2000L

        /** Mirrors KeywordSpotter's default; logging only. */
        private const val KWS_THRESHOLD = 0.5f

        const val ACTION_START = "com.example.handsfree.START"
        const val ACTION_STOP_PIPELINE = "com.example.handsfree.STOP_PIPELINE"
        /** Wake or sleep the listener. Sent by the bubble and the volume-key activator. */
        const val ACTION_ACTIVATE = "com.example.handsfree.ACTIVATE"
        /** Turn always-on call-sign listening on or off. Carries [EXTRA_ENABLED]. */
        const val ACTION_SET_WAKE_WORD = "com.example.handsfree.SET_WAKE_WORD"
        /** Show or hide the floating mic button to match the Settings switch. */
        const val ACTION_SET_BUBBLE = "com.example.handsfree.SET_BUBBLE"

        /**
         * True between onCreate and onDestroy. Settings checks it before
         * sending [ACTION_SET_BUBBLE], so flipping the switch never starts the
         * service and loads the model just to hide a button.
         */
        @Volatile
        var running = false
            private set
        /** Start the service without beginning a listening session. */
        const val ACTION_ENSURE_RUNNING = "com.example.handsfree.ENSURE_RUNNING"
        const val EXTRA_ENABLED = "enabled"

        const val STATUS_PREPARING = "PREPARING"
        const val STATUS_IDLE = "IDLE"
        const val STATUS_LISTENING = "LISTENING"
        const val STATUS_TRANSCRIBING = "TRANSCRIBING"
        const val STATUS_KWS_LISTENING = "KWS_LISTENING"
        const val STATUS_MODEL_ERROR = "MODEL_ERROR"

        @Volatile
        var pipelineListener: PipelineListener? = null

        val transcriptHistory = java.util.concurrent.CopyOnWriteArrayList<String>()

        /**
         * Fill [transcriptHistory] from disk if it is empty. The settings
         * screen calls this because the list is otherwise filled only in
         * [onCreate], and after an app update or a process restart the
         * settings screen can open before the service has started. Seen
         * 2026-09-24: the log dialog read "No history recorded yet" over a
         * 7 KB history file.
         */
        fun ensureHistoryLoaded(context: android.content.Context) {
            if (transcriptHistory.isNotEmpty()) return
            runCatching {
                val file = java.io.File(context.filesDir, "transcript.log")
                if (!file.exists()) return
                val content = file.readText()
                if (content.isBlank()) return
                transcriptHistory.addAll(content.split("|||").take(HISTORY_LIMIT))
            }.onFailure { Log.e(TAG, "Failed to load transcript history", it) }
        }
    }
}
