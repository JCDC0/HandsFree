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
import com.example.handsfree.R
import com.example.handsfree.pipeline.AudioPreprocessor
import com.example.handsfree.pipeline.CommandExecutor
import com.example.handsfree.pipeline.KeywordSpotter
import com.example.handsfree.pipeline.LevenshteinMatcher
import com.example.handsfree.pipeline.SpeechEngine
import com.example.handsfree.pipeline.TranslationLayer
import com.example.handsfree.pipeline.WakeWordDetector
import com.example.handsfree.pipeline.WhisperOnnxEngine
import com.example.handsfree.util.CrashLog
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
 * Activation model. The microphone is not held open indefinitely — that drains
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
    @Volatile private var kwsListening = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var pipelineRunning = false
    @Volatile private var listening = false
    @Volatile private var startWhenReady = false

    /** Always-on call-sign mode. Off unless the user turns it on. */
    @Volatile private var wakeWordEnabled = false

    /** Set when the bubble or accessibility button woke us — no call sign needed. */
    @Volatile private var explicitActivation = false

    /** Call sign heard alone: take the next utterance as the command. */
    @Volatile private var awaitingCommand = false

    private val wakeWordDetector = WakeWordDetector()

    private val rearmRunnable = Runnable {
        if (wakeWordEnabled && !listening && !kwsListening) startKwsListening()
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleLabel: TextView? = null

    private var popupView: View? = null
    private var popupStatusText: TextView? = null
    private var popupTranscriptionText: TextView? = null
    private var popupIntentText: TextView? = null
    private var popupWarningText: TextView? = null

    private val sleepRunnable = Runnable {
        if (listening) {
            Log.i(TAG, "listening window elapsed — returning to idle")
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
         * command ran but something outside the app will spoil it — today only
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
        CrashLog.reset(this)
        CrashLog.step(this, "STEP_0: OverlayService.onCreate")

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
        commandExecutor = CommandExecutor(this)
        keywordSpotter = KeywordSpotter(this)

        // Prepare KWS models off the main thread
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

        speechEngine = createEngine()
        emitStatus(STATUS_PREPARING)

        speechEngine.onReady = {
            Log.i(TAG, "${speechEngine.displayName} ready")
            CrashLog.info(this@OverlayService, "engine ready: ${speechEngine.displayName}")
            emitStatus(STATUS_IDLE)
            showBubble()
            if (startWhenReady) {
                startWhenReady = false
                startListening()
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

    // ---- activation ----

    /** Wake, listen for one utterance, then idle again. */
    private fun startListening() {
        if (listening) return
        if (!speechEngine.isReady) {
            startWhenReady = true
            emitStatus(STATUS_PREPARING)
            return
        }
        if (kwsListening) {
            stopKwsListening()
            Log.i(TAG, "switched from KWS to Whisper")
        }
        listening = true
        emitStatus(STATUS_LISTENING)
        updateBubble("●", 0xFFE53935.toInt())
        startCaptureIfNeeded()
        mainHandler.removeCallbacks(sleepRunnable)
        mainHandler.postDelayed(sleepRunnable, listenWindowMs())
    }

    /**
     * How long the mic stays open when the VAD never decides the utterance
     * ended. Read from preferences on every activation rather than cached, so
     * moving the slider takes effect on the next command with no restart.
     *
     * This is a *cap*, not a wait: a normal command ends on trailing silence
     * long before it. It only bites when the VAD cannot tell you stopped —
     * either the room stayed above `AudioPreprocessor`'s VAD_THRESHOLD, or the
     * speaker was quiet enough that speech never registered at all, in which
     * case the silence counter is never even armed. Testers hit that second
     * case and sat through the entire window in silence.
     */
    private fun listenWindowMs(): Long =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(PREF_LISTEN_WINDOW_S, DEFAULT_LISTEN_WINDOW_S)
            .coerceIn(MIN_LISTEN_WINDOW_S, MAX_LISTEN_WINDOW_S) * 1000L

    private fun stopListening(reason: String) {
        if (!listening) return
        listening = false
        startWhenReady = false
        mainHandler.removeCallbacks(sleepRunnable)
        emitStatus(STATUS_IDLE)
        updateBubble("🎤", 0xFF1E88E5.toInt())
        Log.i(TAG, "stopped listening: $reason")
        // Into the exported log, not just logcat. Across 15-20 testers this
        // is what separates "the VAD worked" from "we sat out the whole
        // window", and no tester can be asked to run adb.
        CrashLog.info(this, "listening ended: $reason")
        flushWhisperUtterance()
        checkStopCapture()
    }

    private fun checkStopCapture() {
        if (!listening && !kwsListening) {
            audioPreprocessor.stopCapture()
            pipelineRunning = false
        }
    }

    /** Whisper emits one result after the listening window, not per audio chunk. */
    private fun flushWhisperUtterance() {
        val whisper = speechEngine as? WhisperOnnxEngine ?: run { scheduleRearm(); return }
        scope.launch {
            val raw = whisper.onUtteranceComplete()
            mainHandler.post {
                // Re-arming has to wait for inference to finish. Starting a new
                // capture while the previous transcript is still being decoded
                // would feed audio into the engine mid-inference.
                if (!raw.isNullOrBlank()) handleUtterance(raw) else scheduleRearm()
            }
        }
    }

    // ---- KWS (keyword spotter) cascade ----

    /**
     * Open the mic for background keyword detection. No UI popup is shown.
     * When the keyword is detected, KWS capture is stopped and the full
     * Whisper pipeline is started via [startListening].
     */
    private fun startKwsListening() {
        if (kwsListening || listening) return
        if (!keywordSpotter.isReady) {
            // The three ONNX models load on a background coroutine started in
            // onCreate. Enabling the call sign in the first second after launch
            // landed here and gave up silently: the toggle read ON while
            // nothing was listening, forever. Retry instead.
            Log.i(TAG, "KWS models still loading - retrying in ${KWS_RETRY_MS}ms")
            mainHandler.postDelayed(
                { if (wakeWordEnabled && !listening && !kwsListening) startKwsListening() },
                KWS_RETRY_MS
            )
            return
        }
        kwsListening = true
        keywordSpotter.reset()
        emitStatus(STATUS_KWS_LISTENING)
        updateBubble("👂", 0xFF78909C.toInt())
        Log.i(TAG, "KWS listening started")
        
        kwsBuffer = ShortArray(0)
        startCaptureIfNeeded()
    }

    /** Stop KWS audio capture and reset state. */
    private fun stopKwsListening(reason: String = "manual") {
        if (!kwsListening) return
        kwsListening = false
        Log.i(TAG, "KWS listening stopped: $reason")
        checkStopCapture()
    }

    // ---- always-on call sign ----

    /**
     * Queue the next listening cycle in always-on mode.
     *
     * Only ever called once the previous utterance is fully handled, so at most
     * one capture exists at a time.
     */
    private fun scheduleRearm() {
        if (!wakeWordEnabled) return
        mainHandler.removeCallbacks(rearmRunnable)
        mainHandler.postDelayed(rearmRunnable, REARM_DELAY_MS)
    }

    private fun setWakeWordEnabled(enabled: Boolean) {
        if (wakeWordEnabled == enabled) return
        wakeWordEnabled = enabled
        Log.i(TAG, "call-sign mode ${if (enabled) "on" else "off"}")
        mainHandler.removeCallbacks(rearmRunnable)
        awaitingCommand = false
        if (enabled) {
            if (!listening && !kwsListening) startKwsListening()
        } else {
            if (kwsListening) stopKwsListening("call-sign mode off")
            else if (listening && !explicitActivation) stopListening("call-sign mode off")
        }
    }

    /**
     * Gate an utterance heard in always-on mode.
     *
     * @return the text to run as a command, or null when this utterance was not
     *         addressed to HandsFree and should be discarded silently.
     */
    private fun applyWakeGate(raw: String): String? {
        if (!wakeWordEnabled || explicitActivation || awaitingCommand) {
            awaitingCommand = false
            return raw
        }

        val hit = wakeWordDetector.detect(raw)
        if (hit == null) {
            // Not addressed to us. Deliberately not written to the transcript
            // history — in always-on mode that would fill with room noise and
            // bury the entries testers actually need to read.
            Log.d(TAG, "no call sign in \"$raw\" — ignoring")
            scheduleRearm()
            return null
        }

        if (hit.command.isBlank()) {
            // Call sign alone. Listen once more, without requiring it again.
            Log.i(TAG, "call sign \"${hit.matched}\" — awaiting command")
            awaitingCommand = true
            emitStatus(STATUS_LISTENING)
            scheduleRearm()
            return null
        }

        Log.i(TAG, "call sign \"${hit.matched}\" + command \"${hit.command}\"")
        return hit.command
    }

    private fun startCaptureIfNeeded() {
        if (pipelineRunning) return
        pipelineRunning = true
        audioPreprocessor.startCapture(
            callback = { buffer ->
                if (kwsListening) {
                    handleKwsAudio(buffer)
                } else if (listening) {
                    val raw = speechEngine.acceptWaveform(buffer)
                    if (raw != null && raw.isNotBlank()) {
                        handleUtterance(raw)
                    } else {
                        val partial = speechEngine.getPartialResult()
                        if (partial.isNotEmpty()) emitPartial(partial)
                    }
                }
            },
            onSilenceDetected = {
                if (listening) {
                    mainHandler.post { stopListening("Silence detected (VAD)") }
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

            // The cascade used to return -1f on every chunk and nobody could
            // tell, because nothing ever logged a score. Peak-per-interval so
            // the log stays readable at 12.5 chunks/second.
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
                    awaitingCommand = true
                    startListening()
                }
                break
            }
        }
    }

    /**
     * Put every fatal model/audio failure back into a recoverable service state.
     * Failures can originate on the model-init or audio-capture threads.
     */
    private fun handleBackendError(error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        mainHandler.post {
            CrashLog.exception(this@OverlayService, "backend error", error)
            listening = false
            kwsListening = false
            pipelineRunning = false
            startWhenReady = false
            // Do not re-arm after a backend failure. A mic or model fault that
            // repeats would otherwise loop on the microphone indefinitely.
            mainHandler.removeCallbacks(rearmRunnable)
            awaitingCommand = false
            mainHandler.removeCallbacks(sleepRunnable)
            runCatching { audioPreprocessor.stopCapture() }
            updateBubble("🎤", 0xFF1E88E5.toInt())
            emitStatus(STATUS_MODEL_ERROR)
            emitError(message)
        }
    }

    /** Full path from transcript to executed command. */
    private fun handleUtterance(raw: String) {
        val spoken = applyWakeGate(raw) ?: return
        explicitActivation = false

        val normalized = runCatching { translationLayer.normalize(spoken) }
            .getOrElse { spoken }.ifBlank { spoken }
        emitResult(raw, normalized)

        val match = matcher?.classify(normalized)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        
        if (match == null) {
            val intentStr = "No match"
            val resultMsg = "No matching command"
            val logEntry = "[$timestamp]\nNarinig: $raw\nSalin: $normalized\nIntent: $intentStr\nResult: FAILED - $resultMsg"
            recordTranscript(logEntry)

            emitCommand("NO MATCH", false, resultMsg)
            stopListening("no match")
            scheduleRearm()
            return
        }

        val (category, phrase) = match
        val intentStr = "$category ($phrase)"
        
        val result = runCatching { commandExecutor.execute(category, normalized, phrase) }
            .getOrElse { CommandExecutor.Result(false, it.message ?: "Execution failed") }

        val statusStr = if (result.success) "SUCCESS" else "FAILED"
        // The warning is its own line rather than part of Result, so a reviewer
        // reading the exported log can tell "the command worked, the network
        // did not" apart from "the pipeline misheard you".
        val warningLine = result.warning?.let { "\nWarning: $it" }.orEmpty()
        val logEntry = "[$timestamp]\nNarinig: $raw\nSalin: $normalized\nIntent: $intentStr\n" +
            "Result: $statusStr - ${result.message}$warningLine"
        recordTranscript(logEntry)
        if (result.warning != null) {
            CrashLog.info(this, "$category ran with warning: ${result.warning}")
        }

        emitCommand(category, result.success, result.message, result.warning)
        stopListening("command handled")
        scheduleRearm()
    }

    /**
     * Newest first, capped at [HISTORY_LIMIT]. Raised from 50 for the tester
     * round — a tester working through the command card fills 50 entries before
     * they reach the end of it, and the oldest entries are the ones a reviewer
     * most wants (the first attempt at a phrase, before the tester adapted).
     */
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

    // ---- floating bubble ----

    /**
     * Small always-on-top button. Tapping it wakes the listener, so the demo
     * works even where volume-key capture is unavailable, and it makes the
     * service's state visible.
     */
    private fun showBubble() {
        if (bubbleView != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission missing — bubble unavailable")
            return
        }
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm

        val label = TextView(this).apply {
            text = "🎤"
            textSize = 24f
            setPadding(28, 20, 28, 24)
            setBackgroundResource(R.drawable.bubble_background)
        }
        bubbleLabel = label

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }

        // Tap wakes the listener; drag repositions the bubble.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        label.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) dragged = true
                    params.x = startX + dx; params.y = startY + dy
                    runCatching { wm.updateViewLayout(label, params) }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        if (listening) stopListening("bubble tap") else {
                            explicitActivation = true
                            startListening()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(label, params); bubbleView = label }
            .onFailure { Log.e(TAG, "bubble add failed", it) }
    }

    private fun updateBubble(glyph: String, @Suppress("UNUSED_PARAMETER") tint: Int) {
        mainHandler.post { bubbleLabel?.text = glyph }
    }

    private fun hideBubble() {
        val v = bubbleView ?: return
        runCatching { windowManager?.removeView(v) }
        bubbleView = null; bubbleLabel = null
    }

    private fun showAssistantPopup() {
        mainHandler.post {
            if (popupView != null) return@post
            val wm = getSystemService(WindowManager::class.java) ?: return@post
            windowManager = wm

            val inflater = android.view.LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.popup_assistant, null)
            popupView = view
            popupStatusText = view.findViewById(R.id.popup_status_text)
            popupTranscriptionText = view.findViewById(R.id.popup_transcription_text)
            popupIntentText = view.findViewById(R.id.popup_intent_text)
            popupWarningText = view.findViewById(R.id.popup_warning_text)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 100 // Slight bottom margin
                windowAnimations = android.R.style.Animation_Dialog
            }

            runCatching { wm.addView(view, params) }
                .onFailure { Log.e(TAG, "popup add failed", it) }
        }
    }

    private fun hideAssistantPopup() {
        mainHandler.post {
            val v = popupView ?: return@post
            runCatching { windowManager?.removeView(v) }
            popupView = null; popupStatusText = null
            popupTranscriptionText = null; popupIntentText = null
            popupWarningText = null
        }
    }

    // ---- listener plumbing ----

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
                showAssistantPopup()
                popupStatusText?.text = "Listening..."
                popupTranscriptionText?.text = "..."
                popupIntentText?.visibility = View.GONE
                popupWarningText?.visibility = View.GONE
            }
            STATUS_KWS_LISTENING -> {
                // KWS is background/invisible — no popup shown
            }
            STATUS_IDLE, "STOPPED" -> popupStatusText?.text = "Transcribing..."
            STATUS_MODEL_ERROR -> {
                popupStatusText?.text = "Error"
                popupStatusText?.setTextColor(android.graphics.Color.RED)
                mainHandler.postDelayed({ hideAssistantPopup() }, 3000)
            }
        }
    }
    private fun emitCommand(i: String, ok: Boolean, m: String, warning: String? = null) {
        Log.i(TAG, "command $i success=$ok :: $m${warning?.let { " :: WARN $it" }.orEmpty()}")
        mainHandler.post {
            pipelineListener?.onCommandExecuted(i, ok, m, warning)
            popupIntentText?.visibility = View.VISIBLE
            popupIntentText?.text = if (ok) "$i: $m" else "$i FAILED: $m"
            popupIntentText?.setTextColor(if (ok) android.graphics.Color.parseColor("#81C784") else android.graphics.Color.parseColor("#E57373"))

            popupWarningText?.visibility = if (warning != null) View.VISIBLE else View.GONE
            popupWarningText?.text = warning

            // Auto-hide popup after showing the result. A warning is longer than
            // the result line and is the thing the tester most needs to read, so
            // it earns more time on screen.
            val linger = if (warning != null) WARNING_LINGER_MS else RESULT_LINGER_MS
            mainHandler.postDelayed({ hideAssistantPopup() }, linger)
        }
        runCatching { startForegroundCompat(buildNotification(warning ?: m)) }
    }

    private fun emitError(message: String) {
        Log.e(TAG, "error: $message")
        mainHandler.post { pipelineListener?.onPipelineError(message) }
        runCatching { startForegroundCompat(buildNotification("Error: $message")) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_ACTIVATE -> if (listening) {
                stopListening("toggle")
            } else {
                explicitActivation = true
                startListening()
            }
            ACTION_START -> {
                explicitActivation = true
                startListening()
            }
            // Bring the service up without opening a Whisper capture. Turning
            // the call sign on used to send ACTION_START, which began a full
            // 8-second listen the user never asked for -- the "it just opens
            // the mic" complaint. Call-sign mode wants the KWS path, not this.
            ACTION_ENSURE_RUNNING -> Unit
            ACTION_STOP_PIPELINE -> {
                startWhenReady = false
                wakeWordEnabled = false
                mainHandler.removeCallbacks(rearmRunnable)
                stopKwsListening("explicit stop")
                stopListening("explicit stop")
            }
            ACTION_SET_WAKE_WORD ->
                setWakeWordEnabled(intent.getBooleanExtra(EXTRA_ENABLED, false))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        listening = false
        pipelineRunning = false
        wakeWordEnabled = false
        kwsListening = false
        mainHandler.removeCallbacks(rearmRunnable)
        mainHandler.removeCallbacks(sleepRunnable)
        hideBubble()
        runCatching { audioPreprocessor.release() }
        runCatching { speechEngine.release() }
        runCatching { keywordSpotter.release() }
        scope.cancel()
        emitStatus(STATUS_IDLE)
        super.onDestroy()
    }

    // ---- notification ----

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
        private const val PREFS = "handsfree_prefs"

        /**
         * Entries kept in the transcript log, newest first. Every one is a few
         * hundred bytes and the whole file is rewritten on each command, so the
         * cost of raising this is a rewrite of ~30 KB per utterance.
         */
        const val HISTORY_LIMIT = 75

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
        /** Start the service without beginning a listening session. */
        const val ACTION_ENSURE_RUNNING = "com.example.handsfree.ENSURE_RUNNING"
        const val EXTRA_ENABLED = "enabled"

        const val STATUS_PREPARING = "PREPARING"
        const val STATUS_IDLE = "IDLE"
        const val STATUS_LISTENING = "LISTENING"
        const val STATUS_KWS_LISTENING = "KWS_LISTENING"
        const val STATUS_MODEL_ERROR = "MODEL_ERROR"

        @Volatile
        var pipelineListener: PipelineListener? = null

        val transcriptHistory = java.util.concurrent.CopyOnWriteArrayList<String>()
    }
}
