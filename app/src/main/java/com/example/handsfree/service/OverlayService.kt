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
import com.example.handsfree.pipeline.VoskEngineAdapter
import com.example.handsfree.pipeline.WhisperOnnxEngine
import com.example.handsfree.util.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service hosting the HandsFree pipeline:
 *
 *   AudioPreprocessor -> SpeechEngine -> TranslationLayer
 *       -> LevenshteinMatcher -> CommandExecutor -> AccessibilityService
 *
 * The engine behind [SpeechEngine] is chosen at startup: the fine-tuned
 * Whisper ONNX build when its assets are present, otherwise VOSK, so the
 * pipeline stays testable while the export is outstanding.
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

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleLabel: TextView? = null

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
        }
        speechEngine.onError = { e ->
            CrashLog.exception(this@OverlayService, "engine error", e)
            emitStatus(STATUS_MODEL_ERROR)
            emitError(e.message ?: e.javaClass.simpleName)
        }
        speechEngine.prepare(if (speechEngine is WhisperOnnxEngine) "tl" else "en")
    }

    /**
     * Whisper when its assets are bundled, VOSK otherwise.
     *
     * Flip [WhisperOnnxEngine.MODEL_PRESENT] once the ONNX export lands and the
     * app switches over with no other change.
     */
    private fun createEngine(): SpeechEngine =
        if (WhisperOnnxEngine.MODEL_PRESENT) {
            Log.i(TAG, "using Whisper ONNX engine")
            WhisperOnnxEngine(this)
        } else {
            Log.i(TAG, "Whisper assets absent — falling back to VOSK")
            VoskEngineAdapter(this)
        }

    // ---- activation ----

    /** Wake, listen for one utterance, then idle again. */
    private fun startListening() {
        if (listening) return
        if (!speechEngine.isReady) {
            emitError("Model still loading…")
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
        mainHandler.removeCallbacks(sleepRunnable)
        audioPreprocessor.stopCapture()
        pipelineRunning = false
        emitStatus(STATUS_IDLE)
        updateBubble("🎤", 0xFF1E88E5.toInt())
        Log.i(TAG, "stopped listening: $reason")
    }

    private fun startPipeline() {
        if (pipelineRunning) return
        pipelineRunning = true
        audioPreprocessor.startCapture { buffer ->
            val raw = speechEngine.acceptWaveform(buffer)
            if (raw != null && raw.isNotBlank()) {
                handleUtterance(raw)
            } else {
                val partial = speechEngine.getPartialResult()
                if (partial.isNotEmpty()) emitPartial(partial)
            }
        }
    }

    /** Full path from transcript to executed command. */
    private fun handleUtterance(raw: String) {
        val normalized = runCatching { translationLayer.normalize(raw) }
            .getOrElse { raw }.ifBlank { raw }
        emitResult(raw, normalized)

        val match = matcher?.classify(normalized)
        if (match == null) {
            emitCommand("—", false, "No matching command")
            stopListening("no match")
            return
        }

        val (category, phrase) = match
        val result = runCatching { commandExecutor.execute(category, normalized, phrase) }
            .getOrElse { CommandExecutor.Result(false, it.message ?: "Execution failed") }

        emitCommand(category, result.success, result.message)
        stopListening("command handled")
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

    // ---- listener plumbing ----

    private fun emitPartial(p: String) = mainHandler.post { pipelineListener?.onPartialResult(p) }
    private fun emitResult(raw: String, norm: String) =
        mainHandler.post { pipelineListener?.onTranscriptionResult(raw, norm) }
    private fun emitStatus(s: String) = mainHandler.post { pipelineListener?.onStatusChanged(s) }
    private fun emitCommand(i: String, ok: Boolean, m: String) {
        Log.i(TAG, "command $i success=$ok :: $m")
        mainHandler.post { pipelineListener?.onCommandExecuted(i, ok, m) }
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
            ACTION_ACTIVATE -> if (listening) stopListening("toggle") else startListening()
            ACTION_START -> startListening()
            ACTION_STOP_PIPELINE -> stopListening("explicit stop")
            ACTION_SWITCH_MODEL -> {
                val lang = intent.getStringExtra(EXTRA_LANGUAGE) ?: "en"
                if (lang != speechEngine.activeLanguage) {
                    emitStatus(STATUS_SWITCHING)
                    speechEngine.switchModel(lang)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        listening = false
        pipelineRunning = false
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                return
            } catch (se: SecurityException) {
                Log.w(TAG, "specialUse FGS rejected; using plain startForeground", se)
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

        const val ACTION_START = "com.example.handsfree.START"
        const val ACTION_STOP_PIPELINE = "com.example.handsfree.STOP_PIPELINE"
        const val ACTION_SWITCH_MODEL = "com.example.handsfree.SWITCH_MODEL"

        /** Wake or sleep the listener. Sent by the bubble and the volume-key activator. */
        const val ACTION_ACTIVATE = "com.example.handsfree.ACTIVATE"

        const val EXTRA_LANGUAGE = "language"

        const val STATUS_PREPARING = "PREPARING"
        const val STATUS_IDLE = "IDLE"
        const val STATUS_LISTENING = "LISTENING"
        const val STATUS_SWITCHING = "SWITCHING_MODEL"
        const val STATUS_MODEL_ERROR = "MODEL_ERROR"

        @Volatile
        var pipelineListener: PipelineListener? = null
    }
}
