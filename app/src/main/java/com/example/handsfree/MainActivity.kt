package com.example.handsfree

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.handsfree.pipeline.LevenshteinMatcher
import com.example.handsfree.service.OverlayService
import com.example.handsfree.util.CrashLog

/**
 * Main launcher activity for HandsFree.
 *
 * Debug screen showing the full pipeline result:
 *   - Raw transcription from VOSK
 *   - Tagalog→English translation from TranslationLayer
 *   - Detected intent from LevenshteinMatcher
 *
 * The activity registers itself as the [OverlayService.PipelineListener]
 * while it is resumed and loads the intent vocabulary for local display.
 * Phase 2 adds the onboarding flow for Accessibility Service and overlay
 * permissions (Module G).
 */
class MainActivity : AppCompatActivity(), OverlayService.PipelineListener {

    private lateinit var statusText: TextView
    private lateinit var transcribedText: TextView
    private lateinit var translatedText: TextView
    private lateinit var intentText: TextView
    private lateinit var startButton: Button
    private lateinit var permButton: Button
    private lateinit var showLogButton: Button
    private lateinit var modelButton: Button

    /** Currently selected VOSK model — toggled by [modelButton]. */
    private var currentLanguage: String = "en"

    /**
     * Previous handler captured so we can delegate after logging our own crash
     * info. The default VM handler terminates the process — we keep that
     * behavior, just persist the stack trace to [CrashLog] first.
     */
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    /** True while the foreground service + mic capture are running. Drives the
     *  start/stop toggle label and guards against double-starts. */
    private var pipelineActive = false

    /** Clears transient error messages after a short delay. */
    private val errorClearHandler = Handler(Looper.getMainLooper())
    private val errorClearRunnable = Runnable {
        statusText.setTextColor(Color.BLACK)
        statusText.text = "..."
    }

    /** Levenshtein-based intent classifier loaded from intent-vocabulary.json. */
    private var levenshteinMatcher: LevenshteinMatcher? = null

    /**
     * Shown while the speech model loads. First launch unpacks a model from
     * assets, which can take several seconds — without this the app looks
     * frozen and testers tap Start repeatedly.
     */
    private var loadingDialog: AlertDialog? = null

    private fun showLoading(message: String) {
        runOnUiThread {
            if (loadingDialog?.isShowing == true) {
                loadingDialog?.setMessage(message); return@runOnUiThread
            }
            val bar = android.widget.ProgressBar(this).apply { setPadding(48, 48, 48, 24) }
            val label = TextView(this).apply {
                text = message
                textSize = 15f
                setPadding(48, 8, 48, 48)
                gravity = android.view.Gravity.CENTER
            }
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(bar); addView(label)
            }
            loadingDialog = AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(false)
                .create()
                .also { it.show() }
        }
    }

    private fun hideLoading() {
        runOnUiThread {
            runCatching { loadingDialog?.dismiss() }
            loadingDialog = null
        }
    }

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startOverlayService()
            } else {
                Toast.makeText(this, "RECORD_AUDIO permission required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        installCrashHandler()

        statusText = findViewById(R.id.status_text)
        transcribedText = findViewById(R.id.transcribed_text)
        translatedText = findViewById(R.id.translated_text)
        intentText = findViewById(R.id.intent_text)
        startButton = findViewById(R.id.start_button)
        permButton = findViewById(R.id.perm_button)
        showLogButton = findViewById(R.id.show_log_button)
        modelButton = findViewById(R.id.model_button)

        // Load LevenshteinMatcher for intent display in the debug UI
        levenshteinMatcher = runCatching {
            val json = assets.open("intent-vocabulary.json").bufferedReader().use { it.readText() }
            LevenshteinMatcher.fromAssets(json)
        }.getOrNull()

        // Fresh start: clear all text, reset model to EN, reset state
        transcribedText.text = "..."
        translatedText.text = "..."
        intentText.text = "—"
        statusText.text = "Ready — tap Start Mic & VOSK"
        statusText.setTextColor(Color.BLACK)
        currentLanguage = "en"
        modelButton.text = "Model: EN"
        pipelineActive = false
        startButton.text = "Start Listening"
        startButton.isEnabled = true
        // If service is lingering from a previous session, force it to EN
        val resetIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SWITCH_MODEL
            putExtra(OverlayService.EXTRA_LANGUAGE, "en")
        }
        startService(resetIntent)

        startButton.setOnClickListener {
            if (pipelineActive) {
                stopOverlayService()
            } else if (hasRecordAudioPermission()) {
                startOverlayService()
            } else {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        modelButton.setOnClickListener {
            val newLang = if (currentLanguage == "en") "tl" else "en"
            switchModel(newLang)
        }

        permButton.setOnClickListener {
            // Open Accessibility Settings so user can enable the HandsFree service.
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        showLogButton.setOnClickListener {
            val log = CrashLog.read(this)
            AlertDialog.Builder(this)
                .setTitle("crash.log (last line = the step that killed us)")
                .setMessage(log)
                .setNeutralButton("Copy to Clipboard") { _, _ ->
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("HandsFree crash log", log)
                    )
                    Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setPositiveButton("Close", null)
                .show()
        }
    }

    /**
     * Install a global [Thread.setDefaultUncaughtExceptionHandler] that
     * persists the stack trace to [CrashLog] *before* delegating to the
     * previous handler (which terminates the process via RuntimeInit).
     *
     * This catches Java/Kotlin exceptions only — a native SIGSEGV from a
     * misaligned libvosk.so bypasses this handler entirely, which is exactly
     * why the pipeline also leaves STEP_x breadcrumbs via [CrashLog.step]:
     * those survive the native death even when this handler never fires.
     */
    private fun installCrashHandler() {
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                CrashLog.exception(
                    applicationContext,
                    "UNCAUGHT on thread=${thread.name}",
                    throwable
                )
            }
            previousUncaughtHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onResume() {
        super.onResume()
        OverlayService.pipelineListener = this
        updatePermissionStatus()
    }

    override fun onPause() {
        super.onPause()
        if (OverlayService.pipelineListener === this) {
            OverlayService.pipelineListener = null
        }
        errorClearHandler.removeCallbacks(errorClearRunnable)
    }

    override fun onDestroy() {
        // A dialog still attached to a finishing activity leaks its window.
        hideLoading()
        super.onDestroy()
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Reflect missing permissions / overlay grant in the status TextView so the
     * user knows what to fix before tapping "Start Listening".
     */
    private fun updatePermissionStatus() {
        val missing = mutableListOf<String>()
        if (!hasRecordAudioPermission()) missing += "RECORD_AUDIO"
        if (!Settings.canDrawOverlays(this)) missing += "OVERLAY"
        if (missing.isNotEmpty()) {
            statusText.text = "Missing: ${missing.joinToString()} — grant before starting"
        } else if (statusText.text.toString().startsWith("Missing", ignoreCase = true)) {
            statusText.text = "Ready — tap Start Mic & VOSK"
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_LANGUAGE, currentLanguage)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        pipelineActive = true
        startButton.text = "Stop Listening"
        startButton.isEnabled = false // re-enabled once LISTENING fires
        Toast.makeText(this, "Pipeline starting...", Toast.LENGTH_SHORT).show()
        statusText.text = "PREPARING"
        transcribedText.text = "..."
        translatedText.text = "..."
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP_PIPELINE
        }
        startService(intent)
        pipelineActive = false
        startButton.text = "Start Listening"
        transcribedText.text = "..."
        translatedText.text = "..."
    }

    private fun switchModel(language: String) {
        currentLanguage = language
        modelButton.text = if (language == "en") "Model: EN" else "Model: TL"
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SWITCH_MODEL
            putExtra(OverlayService.EXTRA_LANGUAGE, language)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Switching to ${language.uppercase()} model...", Toast.LENGTH_SHORT).show()
    }

    // ---- OverlayService.PipelineListener ----

    override fun onTranscriptionResult(rawTranscribed: String, normalized: String) {
        runOnUiThread {
            transcribedText.text = rawTranscribed.ifBlank { "—" }
            translatedText.text = normalized.ifBlank { "—" }

            // Run intent classification and show result in debug UI
            val match = levenshteinMatcher?.classify(normalized)
            intentText.text = if (match != null) {
                "${match.first}  (matched: \"${match.second}\")"
            } else {
                "No match"
            }
        }
    }

    override fun onPartialResult(partial: String) {
        runOnUiThread {
            // Show the in-flight hypothesis with a trailing "..." so the user
            // can tell it's still being recognized, not a final transcript.
            transcribedText.text = if (partial.isEmpty()) "..." else "$partial..."
        }
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread {
            statusText.text = status
            when (status) {
                "PREPARING" -> showLoading("Loading speech model…\nFirst launch unpacks it — this can take a few seconds.")
                "SWITCHING_MODEL" -> {
                    startButton.isEnabled = false
                    showLoading("Switching model…")
                }
                "LISTENING" -> {
                    hideLoading()
                    startButton.isEnabled = true
                    startButton.text = "Stop Listening"
                    pipelineActive = true
                    statusText.setTextColor(Color.parseColor("#1E88E5"))
                    statusText.text = "Listening — speak your command"
                }
                "STOPPED", "IDLE" -> {
                    hideLoading()
                    startButton.isEnabled = true
                    startButton.text = "Start Listening"
                    pipelineActive = false
                    statusText.setTextColor(Color.BLACK)
                    statusText.text = getString(R.string.activator_hint)
                }
                "MODEL_ERROR" -> {
                    hideLoading()
                    startButton.isEnabled = true
                    startButton.text = "Start Listening"
                    statusText.setTextColor(Color.RED)
                }
            }
        }
    }

    /**
     * Report whether the matched command actually did anything. Without this
     * the debug UI shows a correct intent while nothing happens on screen,
     * which is indistinguishable from a recognition failure during testing.
     */
    override fun onCommandExecuted(intent: String, success: Boolean, message: String) {
        runOnUiThread {
            intentText.text = if (success) "$intent — $message" else "$intent — FAILED: $message"
            intentText.setTextColor(if (success) Color.parseColor("#2E7D32") else Color.RED)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPipelineError(message: String) {
        runOnUiThread {
            // Transient errors during startup (e.g. VOSK model loading) should
            // not flash red while the pipeline is still PREPARING.
            if (statusText.text != "PREPARING") {
                statusText.setTextColor(Color.RED)
            }
            statusText.text = message
            errorClearHandler.removeCallbacks(errorClearRunnable)
            errorClearHandler.postDelayed(errorClearRunnable, 3000L)
        }
    }
}