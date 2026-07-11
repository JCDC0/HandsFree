package com.example.handsfree.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.handsfree.pipeline.AudioPreprocessor
import com.example.handsfree.pipeline.TranslationLayer
import com.example.handsfree.pipeline.VoskSpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that hosts the HandsFree processing pipeline:
 *   1. [AudioPreprocessor]   — mic capture + VAD (16000 Hz, mono, 16-bit PCM)
 *   2. [VoskSpeechEngine]    — offline ASR via VOSK (English + Filipino models)
 *   3. [TranslationLayer]    — Tagalog/Taglish → English via bilingual lexicon
 *
 * Later phases add [com.example.handsfree.pipeline.AppStateMachine] (FSM),
 * intent classification (LevenshteinMatcher), and the grid overlay UI on top
 * of [com.example.handsfree.service.HandsFreeAccessibilityService].
 *
 * Pipeline results are surfaced to the attached [PipelineListener] (registered
 * by [com.example.handsfree.MainActivity]) via the service's companion
 * registry. Listener invocations are dispatched back to the main thread.
 *
 * No network calls anywhere — everything runs offline (manuscript requirement).
 */
class OverlayService : Service() {

    private lateinit var audioPreprocessor: AudioPreprocessor
    private lateinit var voskSpeechEngine: VoskSpeechEngine
    private lateinit var translationLayer: TranslationLayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var pipelineRunning = false

    /**
     * Listener registered by the debug UI to receive pipeline results without
     * elevated Event Bus / LocalBroadcastManager dependencies.
     */
    interface PipelineListener {
        /** Called when a complete utterance is transcribed + (stub-)normalized. */
        fun onTranscriptionResult(rawTranscribed: String, normalized: String)

        /** Called when the pipeline's textual status changes (e.g. LISTENING). */
        fun onStatusChanged(status: String)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        // Foreground notification MUST be posted within ~5s of construction.
        startForegroundCompat(buildNotification("HandsFree starting..."))

        audioPreprocessor = AudioPreprocessor()
        voskSpeechEngine = VoskSpeechEngine(this)
        translationLayer = runCatching {
            val json = assets.open(LEXICON_FILE).bufferedReader().use { it.readText() }
            TranslationLayer.fromAssets(json)
        }.getOrElse { e ->
            Log.e(TAG, "Lexicon load failed — falling back to empty passthrough", e)
            TranslationLayer.fromAssets("{\"entries\":{}}")
        }

        emitStatus("PREPARING")
        voskSpeechEngine.onReady = {
            Log.i(TAG, "VOSK ready, starting pipeline")
            emitStatus("LISTENING")
            startPipeline()
        }
        voskSpeechEngine.onError = { e ->
            Log.e(TAG, "VOSK model load error: ${e.message}", e)
            emitStatus("MODEL_ERROR")
        }
        voskSpeechEngine.unpackModelsIfNeeded()
    }

    /**
     * Wire the pipeline: AudioPreprocessor buffer → VoskSpeechEngine → TranslationLayer
     * → notify listener. Runs once the VOSK model is ready.
     */
    private fun startPipeline() {
        if (pipelineRunning) return
        pipelineRunning = true
        emitStatus("LISTENING")
        audioPreprocessor.startCapture { buffer ->
            val raw = voskSpeechEngine.acceptWaveform(buffer)
            if (raw != null) {
                Log.d(TAG, "VOSK result: \"$raw\"")
                val normalized = runCatching { translationLayer.normalize(raw) }
                    .getOrElse { e -> Log.w(TAG, "normalize failed", e); raw }
                val finalNormalized = normalized.ifEmpty { raw }
                emitResult(raw, finalNormalized)
                emitStatus("LISTENING")
            }
        }
    }

    private fun emitResult(raw: String, normalized: String) {
        Log.d(TAG, "emitResult: raw=\"$raw\" normalized=\"$normalized\"")
        mainHandler.post {
            pipelineListener?.onTranscriptionResult(raw, normalized)
        }
    }

    private fun emitStatus(status: String) {
        Log.d(TAG, "status -> $status")
        mainHandler.post { pipelineListener?.onStatusChanged(status) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        // Phase 1: the pipeline auto-starts in onCreate. START_STICKY keeps the
        // service alive across low-memory circumstances so the mic stays open.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        pipelineRunning = false
        runCatching { audioPreprocessor.release() }
        runCatching { voskSpeechEngine.release() }
        scope.cancel()
        if (pipelineListener != null) emitStatus("IDLE")
        super.onDestroy()
    }

    // ---- Foreground notification plumbing ----

    private fun buildNotification(text: String): Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HandsFree")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HandsFree Overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: must pass the type matching the manifest declaration.
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "handsfree_overlay"
        private const val NOTIF_ID = 1001
        private const val LEXICON_FILE = "translation-lexicon.json"

        /**
         * The currently registered [PipelineListener], if any. The activity
         * sets this on resume and clears it on pause so results only flow
         * while the debug UI is visible.
         */
        @Volatile
        var pipelineListener: PipelineListener? = null
    }
}