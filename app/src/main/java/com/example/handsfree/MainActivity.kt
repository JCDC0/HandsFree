package com.example.handsfree

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
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
import com.example.handsfree.service.HandsFreeAccessibilityService
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

    /**
     * Previous handler captured so we can delegate after logging our own crash
     * info. The default VM handler terminates the process — we keep that
     * behavior, just persist the stack trace to [CrashLog] first.
     */
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    /** True while the foreground service + mic capture are running. Drives the
     *  start/stop toggle label and guards against double-starts. */
    private var pipelineActive = false

    private var isSettingUp = false

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

        // Load LevenshteinMatcher for intent display in the debug UI
        levenshteinMatcher = runCatching {
            val json = assets.open("intent-vocabulary.json").bufferedReader().use { it.readText() }
            LevenshteinMatcher.fromAssets(json)
        }.getOrNull()

        // Fresh start: clear the debug fields and reset state.
        transcribedText.text = "..."
        translatedText.text = "..."
        intentText.text = "—"
        statusText.text = getString(R.string.activator_hint)
        statusText.setTextColor(Color.BLACK)
        pipelineActive = false
        startButton.text = "Start Listening"
        startButton.isEnabled = true

        startButton.setOnClickListener {
            if (pipelineActive) {
                stopOverlayService()
            } else if (hasRecordAudioPermission()) {
                val missing = missingSetup()
                if (missing.isEmpty()) {
                    startOverlayService()
                } else {
                    showSetupGuide(missing)
                }
            } else {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        permButton.setOnClickListener {
            showSetupGuide(missingSetup())
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
        
        val missing = missingSetup()
        if (missing.isNotEmpty()) {
            if (isSettingUp) {
                // Already setting up, just advance the guide
                showSetupGuide(missing)
            } else {
                // First launch or user came back after a while, kick off setup
                isSettingUp = true
                showSetupGuide(missing)
            }
        } else if (isSettingUp) {
            isSettingUp = false
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
        }
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
        val missing = missingSetup().toMutableList()
        if (missing.isNotEmpty()) {
            statusText.text = "Missing: ${missing.joinToString()} — grant before starting"
        } else if (statusText.text.toString().startsWith("Missing", ignoreCase = true)) {
            statusText.text = getString(R.string.activator_hint)
        }
    }

    private fun missingSetup(): List<String> = buildList {
        if (!hasRecordAudioPermission()) add("RECORD_AUDIO")
        if (!Settings.canDrawOverlays(this@MainActivity)) add("OVERLAY")
        if (!isAccessibilityServiceEnabled()) add("ACCESSIBILITY")
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(this, HandsFreeAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
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

    // ---- OverlayService.PipelineListener ----

    private val transcriptHistory = mutableListOf<String>()

    override fun onTranscriptionResult(rawTranscribed: String, normalized: String) {
        runOnUiThread {
            if (rawTranscribed.isNotBlank()) {
                val match = levenshteinMatcher?.classify(normalized)
                val intentStr = if (match != null) "${match.first} (${match.second})" else "No match"
                
                val logEntry = "Narinig: $rawTranscribed\nSalin: $normalized\nIntent: $intentStr"
                transcriptHistory.add(0, logEntry)
                if (transcriptHistory.size > 20) transcriptHistory.removeLast()
                
                transcribedText.text = transcriptHistory.joinToString("\n\n---\n\n")
                
                translatedText.text = normalized
                intentText.text = if (match != null) {
                    "${match.first}  (matched: \"${match.second}\")"
                } else {
                    "No match"
                }
            } else {
                if (transcriptHistory.isEmpty()) transcribedText.text = "—"
                translatedText.text = "—"
                intentText.text = "—"
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
                "PREPARING" -> showLoading("Loading speech model…\nFirst launch copies offline files.")
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
                    pipelineActive = false
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
            hideLoading()
            pipelineActive = false
            startButton.isEnabled = true
            startButton.text = "Start Listening"
            statusText.setTextColor(Color.RED)
            statusText.text = message
        }
    }

    private fun showSetupGuide(missing: List<String>) {
        if (missing.isEmpty()) {
            isSettingUp = false
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
            return
        }
        
        isSettingUp = true

        val message = StringBuilder("Please enable the following:\n\n")
        if ("OVERLAY" in missing) {
            message.append("• Display over other apps (needed for the floating microphone bubble)\n")
        }
        if ("ACCESSIBILITY" in missing) {
            message.append("• Accessibility Service (needed to click on things for you and read the screen)\n")
        }
        if ("RECORD_AUDIO" in missing) {
            message.append("• Record Audio (needed to hear your commands)\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Setup Required")
            .setMessage(message.toString().trim())
            .setPositiveButton("Next Step") { _, _ ->
                when {
                    "OVERLAY" in missing -> {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        intent.data = android.net.Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                    "ACCESSIBILITY" in missing -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    "RECORD_AUDIO" in missing -> {
                        requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> isSettingUp = false }
            .show()
    }
}