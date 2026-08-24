package com.example.handsfree

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.handsfree.pipeline.LevenshteinMatcher
import com.example.handsfree.service.HandsFreeAccessibilityService
import com.example.handsfree.service.OverlayService
import com.example.handsfree.util.Copy
import com.example.handsfree.util.CrashLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var wakeWordButton: Button
    private lateinit var exportLogButton: Button
    private lateinit var howToButton: Button
    private lateinit var languageButton: Button
    private lateinit var warningCard: View
    private lateinit var warningText: TextView

    private lateinit var listenWindowLabel: TextView
    private lateinit var listenWindowHint: TextView
    private lateinit var listenWindowSeek: android.widget.SeekBar

    private lateinit var showHistoryButton: Button

    /**
     * Set when the tester declines the consent screen, so it is not re-shown on
     * every resume for the rest of the session. Deliberately not persisted —
     * reopening the app is a fresh decision, and the alternative is an app that
     * can never be consented to again without clearing its data.
     */
    private var introDeferred = false

    /**
     * The onboarding dialog currently on screen, if any. Returning from the
     * Settings app fires `onResume`, which can otherwise stack a second dialog
     * on top of one that never went away.
     */
    private var flowDialog: AlertDialog? = null

    /**
     * Always-on call-sign listening. Persisted so a tester's choice survives a
     * restart, and **off by default** — it keeps the microphone cycling, which
     * is a battery and privacy cost nobody should pay without asking for it.
     */
    private var wakeWordOn = false

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
        statusText.setTextColor(themed(R.color.hf_text_primary))
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
                requestCallPermission()
                startOverlayService()
            } else {
                Toast.makeText(this, "RECORD_AUDIO permission required", Toast.LENGTH_LONG).show()
            }
        }

    /**
     * ANSWER_PHONE_CALLS is a runtime permission. Without it
     * [android.telecom.TelecomManager.acceptRingingCall] does nothing and
     * reports nothing — the app claimed "Call answered" while the phone kept
     * ringing. Asked for separately so a refusal costs only CALL_ACCEPT and
     * still leaves the rest of the app usable.
     */
    private val requestCallPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ANSWER_PHONE_CALLS] != true) {
                Toast.makeText(
                    this,
                    "Without Phone permission, \"sagutin\" cannot answer calls",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private fun requestCallPermission() {
        if (!hasAnswerCallPermission()) {
            requestCallPermissions.launch(
                arrayOf(
                    Manifest.permission.ANSWER_PHONE_CALLS,
                    Manifest.permission.READ_PHONE_STATE
                )
            )
        }
    }

    /**
     * Flip always-on call-sign listening.
     *
     * The service is the owner of the flag; this only persists the choice and
     * tells the service. Turning it on when the pipeline is stopped starts it,
     * since a call sign nobody is listening for is just a button that does
     * nothing.
     */
    private fun toggleWakeWord() {
        // Always-on listening is the last thing that should ever precede the
        // consent screen. Turning it *off* is never blocked.
        if (!wakeWordOn && !consented()) { requireConsent(); return }

        wakeWordOn = !wakeWordOn
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_WAKE_WORD, wakeWordOn).apply()
        updateWakeWordButton()

        if (wakeWordOn && !pipelineActive) {
            if (hasRecordAudioPermission()) startOverlayService() else {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }
        startService(
            Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SET_WAKE_WORD
                putExtra(OverlayService.EXTRA_ENABLED, wakeWordOn)
            }
        )
        if (wakeWordOn) {
            Toast.makeText(
                this,
                "Say \"Hey HandsFree\" then your command. The mic stays on.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateWakeWordButton() {
        wakeWordButton.text = if (wakeWordOn) "👂 Call sign: ON" else "👂 Call sign: OFF"
        wakeWordButton.backgroundTintList = ColorStateList.valueOf(
            themed(if (wakeWordOn) R.color.hf_toggle_on_bg else R.color.hf_button_neutral_bg)
        )
    }

    /**
     * Resolve a colour that has a `values-night` counterpart. Never hardcode a
     * colour int here — the screen ignored the system dark setting for exactly
     * that reason.
     */
    private fun themed(colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

    private fun hasAnswerCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 35 forces edge-to-edge, so with the action bar removed the
        // content draws *behind* the status bar and the header text is clipped
        // by it. Consume the system bar insets as padding rather than painting
        // a coloured block over them.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_scroll)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
        // System bar icons must contrast with our own background, which now
        // follows the system theme: dark icons on the light palette, light icons
        // on the dark one. Getting this backwards makes the clock invisible.
        val darkTheme = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }

        installCrashHandler()

        statusText = findViewById(R.id.status_text)
        transcribedText = findViewById(R.id.transcribed_text)
        translatedText = findViewById(R.id.translated_text)
        intentText = findViewById(R.id.intent_text)
        startButton = findViewById(R.id.start_button)
        permButton = findViewById(R.id.perm_button)
        showLogButton = findViewById(R.id.show_log_button)
        showHistoryButton = findViewById(R.id.show_history_button)
        wakeWordButton = findViewById(R.id.wake_word_button)
        exportLogButton = findViewById(R.id.export_log_button)
        howToButton = findViewById(R.id.how_to_button)
        languageButton = findViewById(R.id.language_button)
        warningCard = findViewById(R.id.warning_card)
        warningText = findViewById(R.id.warning_text)

        wakeWordOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_WAKE_WORD, false)
        updateWakeWordButton()
        wakeWordButton.setOnClickListener { toggleWakeWord() }

        listenWindowLabel = findViewById(R.id.listen_window_label)
        listenWindowHint = findViewById(R.id.listen_window_hint)
        listenWindowSeek = findViewById(R.id.listen_window_seek)
        setUpListenWindow()

        exportLogButton.setOnClickListener { confirmExport() }
        howToButton.setOnClickListener { showHowTo(continueAfter = false) }
        languageButton.setOnClickListener { toggleLanguage() }
        updateLanguageButton()

        // Load LevenshteinMatcher for intent display in the debug UI
        levenshteinMatcher = runCatching {
            val json = assets.open("intent-vocabulary.json").bufferedReader().use { it.readText() }
            LevenshteinMatcher.fromAssets(json)
        }.getOrNull()

        // Fresh start: clear the debug fields and reset state.
        transcribedText.text = "..."
        translatedText.text = "..."
        intentText.text = "—"
        statusText.text = Copy.get(this, Copy.Key.ACTIVATOR_HINT)
        statusText.setTextColor(themed(R.color.hf_text_primary))
        pipelineActive = false
        startButton.text = "Start Listening"
        startButton.isEnabled = true

        startButton.setOnClickListener {
            if (pipelineActive) {
                stopOverlayService()
            } else if (!consented()) {
                // Nothing reaches the microphone before consent. Stopping is
                // always allowed — a consent gate that traps a running mic is
                // worse than no gate.
                requireConsent()
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

        showHistoryButton.setOnClickListener {
            val history = if (OverlayService.transcriptHistory.isEmpty()) "No history recorded yet." else OverlayService.transcriptHistory.joinToString("\n\n---\n\n")
            val scrollView = android.widget.ScrollView(this)
            val textView = TextView(this).apply {
                text = history
                setPadding(48, 48, 48, 48)
                setTextIsSelectable(true)
            }
            scrollView.addView(textView)

            AlertDialog.Builder(this)
                .setTitle("Transcript History (Last ${OverlayService.HISTORY_LIMIT})")
                .setView(scrollView)
                .setNeutralButton("Copy to Clipboard") { _, _ ->
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("HandsFree Transcript History", history)
                    )
                    Toast.makeText(this, "History copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setPositiveButton("Close", null)
                .show()
        }

        showLogButton.setOnClickListener {
            val log = CrashLog.read(this)
            val scrollView = android.widget.ScrollView(this)
            val textView = TextView(this).apply {
                text = log
                setPadding(48, 48, 48, 48)
                setTextIsSelectable(true)
            }
            scrollView.addView(textView)

            AlertDialog.Builder(this)
                .setTitle("System Log")
                .setView(scrollView)
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
        advanceOnboarding()
    }

    /**
     * One place decides which screen a first-run tester sees, because
     * `onResume` fires every time they come back from the Settings app and the
     * steps have to resume mid-flow rather than restart.
     *
     * Strict order: language → consent → how-to → remaining permissions. The
     * how-to is owed the moment the **accessibility service** goes on, not when
     * every permission is complete — a tester who refuses the Phone permission
     * would otherwise finish setup having never been told how to talk to the
     * app at all.
     */
    private fun advanceOnboarding() {
        if (flowDialog?.isShowing == true) return

        if (!Copy.isChosen(this)) { showLanguageChooser(); return }

        // Consent does NOT depend on which permissions happen to be missing.
        // It gated on that once, so a tester who had already granted everything
        // was never asked, and — worse — the consent screen surfaced later,
        // attached to whatever action next triggered onResume.
        // A tester who declined is not walked through the permission chain
        // anyway — the whole flow stops here until they either accept or
        // uninstall, which is what the consent screen told them.
        if (!introAccepted()) {
            if (!introDeferred) showIntro()
            return
        }

        if (isAccessibilityServiceEnabled() && !howToShown()) {
            markHowToShown()
            showHowTo(continueAfter = true)
            return
        }

        val missing = missingSetup()
        if (missing.isNotEmpty()) {
            isSettingUp = true
            showSetupGuide(missing)
        } else if (isSettingUp) {
            isSettingUp = false
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Move to the next onboarding screen from inside the current one's button
     * handler.
     *
     * A plain `advanceOnboarding()` call there does nothing: `AlertDialog`
     * dismisses *after* the click listener returns, so the guard at the top
     * still sees the dialog it is being dismissed from and bails. That is what
     * made the consent screen appear on "Start Listening" instead of straight
     * after the language choice — it was waiting for the next `onResume`, and
     * the thing that produced one was the microphone permission prompt.
     */
    private fun continueFlow() {
        flowDialog?.let {
            it.setOnDismissListener(null)
            runCatching { it.dismiss() }
        }
        flowDialog = null
        advanceOnboarding()
    }

    /** Show [builder], replacing any onboarding dialog already on screen. */
    private fun showFlowDialog(builder: AlertDialog.Builder): AlertDialog {
        flowDialog?.takeIf { it.isShowing }?.let {
            // Clear first: a dismiss listener firing mid-replacement would
            // re-enter advanceOnboarding() before the new dialog is recorded.
            it.setOnDismissListener(null)
            runCatching { it.dismiss() }
        }
        return builder.show().also { flowDialog = it }
    }

    /**
     * A dialog whose body is long enough to need scrolling. Every onboarding
     * screen here is — the consent text alone does not fit on a phone — and
     * `AlertDialog.setMessage` truncates rather than scrolls on some OEM skins.
     */
    private fun scrollingDialog(title: String, body: String): AlertDialog.Builder {
        val text = TextView(this).apply {
            this.text = body
            textSize = 15f
            setTextColor(themed(R.color.hf_text_body))
            setPadding(56, 40, 56, 40)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        return AlertDialog.Builder(this)
            .setTitle(title)
            .setView(android.widget.ScrollView(this).apply { addView(text) })
    }

    // ---- step 0: display language ----

    /**
     * Asked before anything else, because the consent screen is the first thing
     * after it and consent nobody could read is not consent.
     *
     * Tagalog and English only. The app still hears Taglish — that is what the
     * lexicon does — but there is no coherent Taglish register to write an
     * interface in, so it is not offered as a display language. The dialog body
     * says so in both languages, since a tester who has not chosen yet cannot
     * be assumed to read either one.
     */
    private fun showLanguageChooser() {
        showFlowDialog(
            scrollingDialog(getString(R.string.lang_title), getString(R.string.lang_body))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.lang_tagalog)) { _, _ ->
                    pickLanguage(Copy.Lang.TL)
                }
                .setNegativeButton(getString(R.string.lang_english)) { _, _ ->
                    pickLanguage(Copy.Lang.EN)
                }
        )
    }

    private fun pickLanguage(lang: Copy.Lang) {
        Copy.setLang(this, lang)
        applyLanguage()
        continueFlow()
    }

    /**
     * Flip the display language from the debug screen. No activity recreation
     * and no locale switch — see [Copy] for why — so the visible text is
     * re-read by hand here.
     */
    private fun toggleLanguage() {
        val next = if (Copy.lang(this) == Copy.Lang.TL) Copy.Lang.EN else Copy.Lang.TL
        Copy.setLang(this, next)
        applyLanguage()
        Toast.makeText(this, "🌐 ${next.label}", Toast.LENGTH_SHORT).show()
    }

    private fun applyLanguage() {
        updateLanguageButton()
        renderListenWindow()
        // Only the hint follows the toggle; the transcript panes hold pipeline
        // output, which is whatever the tester actually said.
        val shown = statusText.text.toString()
        if (shown.isBlank() || shown == "..." || shown.startsWith("Ready") || shown.startsWith("Handa")) {
            statusText.text = Copy.get(this, Copy.Key.ACTIVATOR_HINT)
        }
    }

    /** Names the language you would switch **to**, not the one you are in. */
    private fun updateLanguageButton() {
        val other = if (Copy.lang(this) == Copy.Lang.TL) Copy.Lang.EN else Copy.Lang.TL
        languageButton.text = "🌐 ${Copy.lang(this).label} → ${other.label}"
    }

    // ---- step 1: introduction and consent ----

    private fun showIntro() {
        showFlowDialog(
            scrollingDialog(
                Copy.get(this, Copy.Key.INTRO_TITLE),
                Copy.get(this, Copy.Key.INTRO_BODY)
            )
                .setCancelable(false)
                .setPositiveButton(Copy.get(this, Copy.Key.INTRO_ACCEPT)) { _, _ ->
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(PREF_INTRO_ACCEPTED, true).apply()
                    CrashLog.info(this, "consent accepted (lang=${Copy.lang(this).tag})")
                    continueFlow()
                }
                .setNegativeButton(Copy.get(this, Copy.Key.INTRO_DECLINE)) { _, _ ->
                    // Not persisted: reopening the app is a fresh decision.
                    introDeferred = true
                    Toast.makeText(
                        this,
                        Copy.get(this, Copy.Key.INTRO_DECLINED_TOAST),
                        Toast.LENGTH_LONG
                    ).show()
                }
        )
    }

    private fun introAccepted(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_INTRO_ACCEPTED, false)

    /**
     * Cleared to use the microphone: a language has been picked *and* the
     * consent screen has been accepted. `introDeferred` is not consulted —
     * declining defers the dialog, it does not grant anything.
     */
    private fun consented(): Boolean = Copy.isChosen(this) && introAccepted()

    /**
     * Asking to listen is itself a request to be asked again. Clears the
     * deferral so a tester who tapped "Hindi muna" and then pressed Start gets
     * the consent screen back, rather than a permission dialog they never
     * agreed to reach.
     */
    private fun requireConsent() {
        introDeferred = false
        advanceOnboarding()
    }

    // ---- step 2: how to operate it ----

    private fun howToShown(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_HOWTO_SHOWN, false)

    private fun markHowToShown() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_HOWTO_SHOWN, true).apply()
    }

    /**
     * @param continueAfter true when this fired automatically as part of first
     *        run, so the permission guide picks up where it left off on dismiss.
     *        False when the tester opened it from the button and is not mid-flow.
     */
    private fun showHowTo(continueAfter: Boolean) {
        val builder = scrollingDialog(
            Copy.get(this, Copy.Key.HOWTO_TITLE),
            Copy.get(this, Copy.Key.HOWTO_BODY)
        ).setPositiveButton(Copy.get(this, Copy.Key.HOWTO_BUTTON), null)

        if (continueAfter) {
            showFlowDialog(builder.setCancelable(false))
                .setOnDismissListener { advanceOnboarding() }
        } else {
            builder.show()
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
            statusText.text = Copy.get(this, Copy.Key.ACTIVATOR_HINT)
        }
    }

    private fun missingSetup(): List<String> = buildList {
        if (!hasRecordAudioPermission()) add("RECORD_AUDIO")
        if (!Settings.canDrawOverlays(this@MainActivity)) add("OVERLAY")
        if (!isAccessibilityServiceEnabled()) add("ACCESSIBILITY")
        if (!hasAnswerCallPermission()) add("PHONE")
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

    override fun onTranscriptionResult(rawTranscribed: String, normalized: String) {
        runOnUiThread {
            if (rawTranscribed.isNotBlank()) {
                val match = levenshteinMatcher?.classify(normalized)
                transcribedText.text = rawTranscribed
                
                translatedText.text = normalized
                intentText.text = if (match != null) {
                    "${match.first}  (matched: \"${match.second}\")"
                } else {
                    "No match"
                }
            } else {
                transcribedText.text = "—"
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
                    // A new utterance starts clean — the last command's warning
                    // says nothing about this one.
                    warningCard.visibility = View.GONE
                    hideLoading()
                    startButton.isEnabled = true
                    startButton.text = "Stop Listening"
                    pipelineActive = true
                    statusText.setTextColor(themed(R.color.hf_status_active))
                    statusText.text = "Listening — speak your command"
                }
                "STOPPED", "IDLE" -> {
                    hideLoading()
                    startButton.isEnabled = true
                    startButton.text = "Start Listening"
                    pipelineActive = false
                    statusText.setTextColor(themed(R.color.hf_text_primary))
                    statusText.text = Copy.get(this, Copy.Key.ACTIVATOR_HINT)
                }
                "MODEL_ERROR" -> {
                    hideLoading()
                    startButton.isEnabled = true
                    startButton.text = "Start Listening"
                    pipelineActive = false
                    statusText.setTextColor(themed(R.color.hf_status_error))
                }
            }
        }
    }

    /**
     * Report whether the matched command actually did anything. Without this
     * the debug UI shows a correct intent while nothing happens on screen,
     * which is indistinguishable from a recognition failure during testing.
     */
    override fun onCommandExecuted(
        intent: String,
        success: Boolean,
        message: String,
        warning: String?
    ) {
        runOnUiThread {
            intentText.text = if (success) "$intent — $message" else "$intent — FAILED: $message"
            intentText.setTextColor(
                themed(if (success) R.color.hf_status_success else R.color.hf_status_error)
            )
            // Above the cards rather than inside one: the command succeeded, so
            // nothing in the pipeline panes is wrong and colouring them would
            // say otherwise.
            warningCard.visibility = if (warning != null) View.VISIBLE else View.GONE
            warningText.text = warning.orEmpty()
            Toast.makeText(this, warning ?: message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPipelineError(message: String) {
        runOnUiThread {
            hideLoading()
            pipelineActive = false
            startButton.isEnabled = true
            startButton.text = "Start Listening"
            statusText.setTextColor(themed(R.color.hf_status_error))
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
        if ("PHONE" in missing) {
            message.append("• Phone (needed to answer or reject calls by voice)\n")
        }

        showFlowDialog(
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
                    "PHONE" in missing -> requestCallPermission()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> isSettingUp = false }
        )
    }

    // ---- mic-open cap ----

    /**
     * Slider for [OverlayService.PREF_LISTEN_WINDOW_S].
     *
     * This is the cap on how long the mic stays open when the VAD never works
     * out that the speaker stopped — not a delay every command pays. A normal
     * command ends on trailing silence well before it. It was a hardcoded 8s,
     * and a tester sat through the whole of it in silence after finishing a
     * command, which is what prompted exposing it.
     *
     * The SeekBar is 0-based, so progress maps to seconds by adding MIN.
     */
    private fun setUpListenWindow() {
        listenWindowSeek.max = OverlayService.MAX_LISTEN_WINDOW_S - OverlayService.MIN_LISTEN_WINDOW_S
        listenWindowSeek.progress = listenWindowSeconds() - OverlayService.MIN_LISTEN_WINDOW_S
        renderListenWindow()

        listenWindowSeek.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: android.widget.SeekBar, progress: Int, fromUser: Boolean
                ) {
                    // Persist as it moves. OverlayService re-reads the pref on
                    // every activation, so there is nothing to notify and no
                    // restart needed — the next command uses the new value.
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt(
                            OverlayService.PREF_LISTEN_WINDOW_S,
                            progress + OverlayService.MIN_LISTEN_WINDOW_S
                        ).apply()
                    renderListenWindow()
                }

                override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar) {}
            }
        )
    }

    private fun listenWindowSeconds(): Int =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(OverlayService.PREF_LISTEN_WINDOW_S, OverlayService.DEFAULT_LISTEN_WINDOW_S)
            .coerceIn(OverlayService.MIN_LISTEN_WINDOW_S, OverlayService.MAX_LISTEN_WINDOW_S)

    private fun renderListenWindow() {
        listenWindowLabel.text =
            "⏱ ${Copy.get(this, Copy.Key.LISTEN_WINDOW_LABEL)}: ${listenWindowSeconds()}s"
        listenWindowHint.text = Copy.get(this, Copy.Key.LISTEN_WINDOW_HINT)
    }

    // ---- log export ----

    /**
     * Testers hand their results back through a Google Form, and a form cannot
     * read app-private storage. The log therefore has to leave the app as a
     * real file the tester can attach.
     *
     * They are told what is in it *before* it is written, not after. The claim
     * on that screen — command text only, no audio, no personal data — is
     * exactly what [buildReport] assembles; if you add a field there, change
     * the copy in `strings.xml` in the same commit.
     */
    private fun confirmExport() {
        if (OverlayService.transcriptHistory.isEmpty()) {
            Toast.makeText(this, Copy.get(this, Copy.Key.EXPORT_EMPTY), Toast.LENGTH_LONG).show()
            return
        }
        scrollingDialog(
            Copy.get(this, Copy.Key.EXPORT_TITLE),
            Copy.get(this, Copy.Key.EXPORT_PRIVACY)
        )
            .setPositiveButton(Copy.get(this, Copy.Key.EXPORT_CONFIRM)) { _, _ -> exportLog() }
            .setNegativeButton(Copy.get(this, Copy.Key.EXPORT_CANCEL), null)
            .show()
    }

    private fun exportLog() {
        val file = runCatching { writeReport() }.getOrElse {
            CrashLog.exception(this, "log export", it)
            Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
            return
        }

        // A file:// Uri throws FileUriExposedException on anything modern, so
        // the share goes through the FileProvider declared in the manifest.
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "HandsFree tester log — ${file.name}")
            putExtra(
                Intent.EXTRA_TEXT,
                "HandsFree tester log. Command text only — no audio, no personal data."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(send, Copy.get(this, Copy.Key.EXPORT_CHOOSER)))
        }.onFailure {
            Toast.makeText(this, "No app available to share the file", Toast.LENGTH_LONG).show()
        }
    }

    private fun writeReport(): File {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "handsfree-log-$stamp.txt")
        file.writeText(buildReport(stamp))
        return file
    }

    /**
     * The exported file. Header first so whoever opens it in the Form response
     * sheet knows what device produced it and what it is allowed to contain,
     * then the command history, then the system log.
     *
     * Everything here is either something the tester spoke to the app or a
     * property of the build. No audio, no contacts, no identifiers.
     */
    private fun buildReport(stamp: String): String {
        val version = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrElse { "unknown" }

        val history = OverlayService.transcriptHistory
        return buildString {
            appendLine("HandsFree — tester log export")
            appendLine("=".repeat(60))
            appendLine("HandsFree: An Offline Hands-Free Android App Using Voice Commands")
            appendLine("in Filipino, English, and Taglish")
            appendLine("School of Computing, Holy Angel University")
            appendLine()
            appendLine("Exported     : $stamp")
            appendLine("App version  : $version")
            // The line that actually maps a log back to a commit. versionName
            // is human-facing and gets forgotten; this does not.
            appendLine("Build        : ${BuildConfig.BUILD_STAMP} @ ${BuildConfig.BUILD_DATE}")
            appendLine("Device       : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android      : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Display lang : ${Copy.lang(this@MainActivity).label}")
            appendLine("Accessibility: ${if (isAccessibilityServiceEnabled()) "on" else "OFF"}")
            appendLine("Phone perm   : ${if (hasAnswerCallPermission()) "granted" else "DENIED"}")
            appendLine("Call sign    : ${if (wakeWordOn) "on" else "off"}")
            // The tester's own choice of mic-open cap. Recorded because we
            // have no data on the right value and this round is how we get it.
            appendLine("Mic window   : ${listenWindowSeconds()}s")
            appendLine("Entries      : ${history.size} of ${OverlayService.HISTORY_LIMIT}")
            appendLine()
            appendLine("PRIVACY: command text only. No audio is recorded or stored at any")
            appendLine("point. No names, numbers, contacts, messages, passwords, photos or")
            appendLine("location. The app holds no INTERNET permission and sends nothing on")
            appendLine("its own — this file moves only if the tester sends it.")
            appendLine()
            appendLine("Narinig = what the app heard   Salin = normalised to English")
            appendLine("Intent  = the classified command   Result = whether it ran")
            appendLine()
            appendLine("-".repeat(60))
            appendLine("COMMAND HISTORY (newest first)")
            appendLine("-".repeat(60))
            appendLine()
            if (history.isEmpty()) appendLine("(none recorded)")
            else history.forEach { appendLine(it); appendLine(); appendLine("---"); appendLine() }
            appendLine("-".repeat(60))
            appendLine("SYSTEM LOG")
            appendLine("-".repeat(60))
            appendLine()
            appendLine(CrashLog.read(this@MainActivity))
            appendLine()
            appendLine("-".repeat(60))
            appendLine("END OF EXPORT — salamat po! 🙌")
        }
    }

    private companion object {
        const val PREFS = "handsfree_prefs"
        const val PREF_WAKE_WORD = "wake_word_enabled"
        const val PREF_INTRO_ACCEPTED = "intro_accepted"
        const val PREF_HOWTO_SHOWN = "howto_shown"
    }
}