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
        if (wakeWordEnabled && !listening) startListening()
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleLabel: TextView? = null

    private var popupView: View? = null
    private var popupStatusText: TextView? = null
    private var popupTranscriptionText: TextView? = null
    private var popupIntentText: TextView? = null

    private val sleepRunnable = Runnable {
        if (listening) {
            Log.i(TAG, "listening window elapsed — returning to idle")
            stopListening("Timed out")
        }
    }

    interface PipelineListener {
        fun onTranscriptionResult(rawTranscribed: String, normalized: String)
        fun onPartialResult(partial: String) {}
        fun onStatusChanged(status: String)
        fun onPipelineError(message: String)
        /** Result of executing a matched command. */
        fun onCommandExecuted(intent: String, success: Boolean, message: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        CrashLog.reset(this)
        CrashLog.step(this, "STEP_0: OverlayService.onCreate")

        runCatching { startForegroundCompat(buildNotification("HandsFree starting…")) }
            .onFailure { CrashLog.exception(this, "STEP_1 startForeground", it); throw it }

        audioPreprocessor = AudioPreprocessor(this)
        commandExecutor = CommandExecutor(this)

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
        listening = true
        emitStatus(STATUS_LISTENING)
        updateBubble("●", 0xFFE53935.toInt())
        startPipeline()
        mainHandler.removeCallbacks(sleepRunnable)
        mainHandler.postDelayed(sleepRunnable, LISTEN_WINDOW_MS)
    }

    private fun stopListening(reason: String) {
        if (!listening) return
        listening = false
        startWhenReady = false
        mainHandler.removeCallbacks(sleepRunnable)
        audioPreprocessor.stopCapture()
        pipelineRunning = false
        emitStatus(STATUS_IDLE)
        updateBubble("🎤", 0xFF1E88E5.toInt())
        Log.i(TAG, "stopped listening: $reason")
        flushWhisperUtterance()
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
            if (!listening) startListening()
        } else if (listening && !explicitActivation) {
            stopListening("call-sign mode off")
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

    private fun startPipeline() {
        if (pipelineRunning) return
        pipelineRunning = true
        audioPreprocessor.startCapture(
            callback = { buffer ->
                val raw = speechEngine.acceptWaveform(buffer)
                if (raw != null && raw.isNotBlank()) {
                    handleUtterance(raw)
                } else {
                    val partial = speechEngine.getPartialResult()
                    if (partial.isNotEmpty()) emitPartial(partial)
                }
            },
            onSilenceDetected = {
                mainHandler.post { stopListening("Silence detected (VAD)") }
            },
            onError = { error -> handleBackendError(error) }
        )
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
            transcriptHistory.add(0, logEntry)
            if (transcriptHistory.size > 50) transcriptHistory.removeAt(transcriptHistory.size - 1)
            
            emitCommand("—", false, resultMsg)
            stopListening("no match")
            scheduleRearm()
            return
        }

        val (category, phrase) = match
        val intentStr = "$category ($phrase)"
        
        val result = runCatching { commandExecutor.execute(category, normalized, phrase) }
            .getOrElse { CommandExecutor.Result(false, it.message ?: "Execution failed") }

        val statusStr = if (result.success) "SUCCESS" else "FAILED"
        val logEntry = "[$timestamp]\nNarinig: $raw\nSalin: $normalized\nIntent: $intentStr\nResult: $statusStr - ${result.message}"
        transcriptHistory.add(0, logEntry)
        if (transcriptHistory.size > 50) transcriptHistory.removeAt(transcriptHistory.size - 1)

        emitCommand(category, result.success, result.message)
        stopListening("command handled")
        scheduleRearm()
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
                        if (listening) stopListening("bubble tap") else startListening()
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
            }
            STATUS_IDLE, "STOPPED" -> popupStatusText?.text = "Transcribing..."
            STATUS_MODEL_ERROR -> {
                popupStatusText?.text = "Error"
                popupStatusText?.setTextColor(android.graphics.Color.RED)
                mainHandler.postDelayed({ hideAssistantPopup() }, 3000)
            }
        }
    }
    private fun emitCommand(i: String, ok: Boolean, m: String) {
        Log.i(TAG, "command $i success=$ok :: $m")
        mainHandler.post { 
            pipelineListener?.onCommandExecuted(i, ok, m) 
            popupIntentText?.visibility = View.VISIBLE
            popupIntentText?.text = if (ok) "$i — $m" else "$i — FAILED: $m"
            popupIntentText?.setTextColor(if (ok) android.graphics.Color.parseColor("#81C784") else android.graphics.Color.parseColor("#E57373"))
            
            // Auto-hide popup after 3 seconds of showing the result
            mainHandler.postDelayed({ hideAssistantPopup() }, 3000)
        }
        runCatching { startForegroundCompat(buildNotification(m)) }
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
            ACTION_STOP_PIPELINE -> {
                startWhenReady = false
                wakeWordEnabled = false
                mainHandler.removeCallbacks(rearmRunnable)
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
        mainHandler.removeCallbacks(rearmRunnable)
        mainHandler.removeCallbacks(sleepRunnable)
        hideBubble()
        runCatching { audioPreprocessor.release() }
        runCatching { speechEngine.release() }
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

        /** How long the mic stays open after activation. */
        private const val LISTEN_WINDOW_MS = 8000L

        /**
         * Pause between an utterance being handled and the next capture in
         * always-on mode. Long enough for `AudioRecord` to be released and
         * re-acquired; short enough that a command spoken straight after the
         * call sign is not clipped.
         */
        private const val REARM_DELAY_MS = 600L

        const val ACTION_START = "com.example.handsfree.START"
        const val ACTION_STOP_PIPELINE = "com.example.handsfree.STOP_PIPELINE"
        /** Wake or sleep the listener. Sent by the bubble and the volume-key activator. */
        const val ACTION_ACTIVATE = "com.example.handsfree.ACTIVATE"
        /** Turn always-on call-sign listening on or off. Carries [EXTRA_ENABLED]. */
        const val ACTION_SET_WAKE_WORD = "com.example.handsfree.SET_WAKE_WORD"
        const val EXTRA_ENABLED = "enabled"

        const val STATUS_PREPARING = "PREPARING"
        const val STATUS_IDLE = "IDLE"
        const val STATUS_LISTENING = "LISTENING"
        const val STATUS_MODEL_ERROR = "MODEL_ERROR"

        @Volatile
        var pipelineListener: PipelineListener? = null

        val transcriptHistory = java.util.concurrent.CopyOnWriteArrayList<String>()
    }
}
