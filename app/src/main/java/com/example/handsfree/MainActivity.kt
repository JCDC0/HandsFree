package com.example.handsfree

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.handsfree.pipeline.LevenshteinMatcher
import com.example.handsfree.service.OverlayService
import com.example.handsfree.util.Copy
import com.example.handsfree.util.CrashLog
import com.example.handsfree.util.UiStyle
import com.example.handsfree.util.pixelUnit
import com.example.handsfree.util.setPixelFill
import com.example.handsfree.util.applySystemBarInsets
import com.example.handsfree.util.hasAnswerCallPermission
import com.example.handsfree.util.isAccessibilityServiceEnabled
import com.example.handsfree.util.scrollingDialog
import com.example.handsfree.util.themed

/**
 * Main launcher activity for HandsFree.
 *
 * Debug screen showing the full pipeline result:
 *   - Raw transcription from the speech engine
 *   - Tagalog to English normalisation from TranslationLayer
 *   - Detected intent from LevenshteinMatcher
 *
 * The activity registers itself as the [OverlayService.PipelineListener]
 * while it is resumed and loads the intent vocabulary for local display. It
 * also owns the first-run flow: display language, consent, how-to, then the
 * permission chain.
 *
 * 1.5.2 moved every one-time control (microphone window, permissions route,
 * log viewers, export, how-to, display language) to [SettingsActivity] behind
 * the cogwheel. What is left here is the pipeline and the two round
 * activators, which is what a tester actually looks at while speaking.
 */
class MainActivity : AppCompatActivity(), OverlayService.PipelineListener {

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var transcribedText: TextView
    private lateinit var translatedText: TextView
    private lateinit var intentText: TextView
    private lateinit var warningCard: View
    private lateinit var warningText: TextView

    /**
     * The two round activators. They are `FrameLayout`s rather than `Button`s
     * because a circle here is a shape, not a label: the caption sits under it
     * in [startCaption] / [wakeWordCaption], where "Magsimulang makinig" fits
     * at a readable size.
     */
    private lateinit var startButton: View
    private lateinit var startIcon: ImageView
    private lateinit var startCaption: TextView
    private lateinit var wakeWordButton: View
    private lateinit var wakeWordIcon: ImageView
    private lateinit var wakeWordCaption: TextView

    private lateinit var settingsButton: ImageView
    private lateinit var drawer: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var debugButton: ImageView
    private lateinit var debugTitle: TextView

    private lateinit var subtitleText: TextView
    private lateinit var statusLabel: TextView
    private lateinit var transcribedLabel: TextView
    private lateinit var translatedLabel: TextView
    private lateinit var intentLabel: TextView

    /**
     * Set when the tester declines the consent screen, so it is not re-shown on
     * every resume for the rest of the session. Deliberately not persisted:
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
     * restart, and **off by default**: it keeps the microphone cycling, which
     * is a battery and privacy cost nobody should pay without asking for it.
     */
    private var wakeWordOn = false

    /**
     * Previous handler captured so we can delegate after logging our own crash
     * info. The default VM handler terminates the process. We keep that
     * behavior, just persist the stack trace to [CrashLog] first.
     */
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    /** True while the foreground service + mic capture are running. Drives the
     *  start/stop button state and guards against double-starts. */
    private var pipelineActive = false

    private var isSettingUp = false

    /**
     * Whether [statusText] is currently showing the missing-permissions line.
     * Tracked as state rather than by testing the text for a "Missing" prefix:
     * that prefix is now translated, so the old check silently stopped
     * matching in Tagalog and the line would never have been cleared.
     */
    private var statusShowsMissing = false

    /** Clears transient error messages after a short delay. */
    private val errorClearHandler = Handler(Looper.getMainLooper())
    private val errorClearRunnable = Runnable {
        statusText.setTextColor(themed(R.color.hf_text_primary))
        statusText.text = Copy.get(this, Copy.Key.ACTIVATOR_HINT)
        setStatusDot(R.color.hf_text_label)
    }

    /** Levenshtein-based intent classifier loaded from intent-vocabulary.json. */
    private var levenshteinMatcher: LevenshteinMatcher? = null

    /**
     * Shown while the speech model loads. First launch unpacks a model from
     * assets, which can take several seconds. Without this the app looks
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
     * reports nothing: the app claimed "Call answered" while the phone kept
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
        getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(OverlayService.PREF_WAKE_WORD, wakeWordOn).apply()
        updateWakeWordButton()

        if (wakeWordOn && !pipelineActive) {
            if (!hasRecordAudioPermission()) {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            // Bring the service up, but do NOT call startOverlayService(): that
            // sends ACTION_START, which opens an immediate 8-second Whisper
            // capture nobody asked for and flips the button to "Stop
            // Listening". Turning on a call sign should start the keyword
            // spotter and nothing else -- the point of it is that the phone is
            // idle until it hears you.
            val boot = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_ENSURE_RUNNING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(boot)
            } else {
                startService(boot)
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
                "Say \"Hey Jarvis\" then your command. The mic stays on.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * The service owns this setting and saves it under the same key, so the
     * circle shows what the service will actually do after a restart.
     */
    private fun readWakeWordPref(): Boolean =
        getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
            .getBoolean(OverlayService.PREF_WAKE_WORD, false)

    /**
     * Paint the call-sign circle for its current state: green filled when on,
     * neutral when off, with the caption and the TalkBack description both
     * naming the state rather than the action. An icon-only control that
     * announces itself as "button" is not acceptable in an accessibility app.
     */
    private fun updateWakeWordButton() {
        val caption = Copy.get(
            this,
            if (wakeWordOn) Copy.Key.BTN_CALLSIGN_ON else Copy.Key.BTN_CALLSIGN_OFF
        )
        wakeWordCaption.text = caption
        wakeWordButton.contentDescription = caption
        wakeWordButton.setPixelFill(
            themed(if (wakeWordOn) R.color.hf_circle_on_bg else R.color.hf_circle_off_bg)
        )
        wakeWordIcon.imageTintList = ColorStateList.valueOf(
            themed(if (wakeWordOn) R.color.hf_circle_on_icon else R.color.hf_circle_off_icon)
        )
    }

    /**
     * Start/stop circle: microphone glyph when idle, stop square while the mic
     * is open, and the fill changes with it. The fill is the part a tester
     * sees from across a desk.
     */
    private fun setStartButtonState(running: Boolean) {
        val caption = Copy.get(this, if (running) Copy.Key.BTN_STOP else Copy.Key.BTN_START)
        startCaption.text = caption
        startButton.contentDescription = caption
        startIcon.setImageResource(if (running) UiStyle.stopIcon(this) else UiStyle.micIcon(this))
        startButton.setPixelFill(
            themed(if (running) R.color.hf_circle_listening_bg else R.color.hf_circle_idle_bg)
        )
        startIcon.imageTintList = ColorStateList.valueOf(
            themed(if (running) R.color.hf_circle_listening_icon else R.color.hf_circle_idle_icon)
        )
    }

    /**
     * A disabled `View` looks identical to an enabled one, unlike a `Button`,
     * so the dimming has to be explicit. Without it the circle silently
     * ignores taps while the model loads and reads as a dead button.
     */
    private fun setStartButtonEnabled(enabled: Boolean) {
        startButton.isEnabled = enabled
        startButton.alpha = if (enabled) 1f else 0.45f
    }

    /**
     * W3: the Debug Screen drawer and the pixel-art frames.
     *
     * The frames are drawables built in code (util/PixelDrawables.kt), so they
     * are set here rather than in the layout. The two activators get a
     * rasterised circle; the drawer cards get a stepped-corner frame. The
     * system Back key closes an open drawer before it leaves the screen.
     */
    private fun setUpDrawerAndPixelFrames() {
        drawer = findViewById(R.id.main_drawer)
        debugButton = findViewById(R.id.debug_button)
        debugTitle = findViewById(R.id.debug_title)
        debugButton.setOnClickListener {
            drawer.openDrawer(androidx.core.view.GravityCompat.START)
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
                    drawer.closeDrawer(androidx.core.view.GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        styleSwitch = findViewById(R.id.style_switch)
        styleSwitch.isChecked = UiStyle.isPixel(this)
        styleSwitch.setOnCheckedChangeListener { _, on ->
            UiStyle.setPixel(this, on)
            CrashLog.info(this, "display style set to ${if (on) "pixel" else "standard"}")
            applyUiStyle()
        }

        applyUiStyle()
    }

    private lateinit var styleSwitch: androidx.appcompat.widget.SwitchCompat

    /** The style the screen was last painted in, so onResume repaints only on a change. */
    private var appliedPixelStyle: Boolean? = null

    /**
     * Paint the screen in the chosen style ([UiStyle]): pixel-art frames,
     * icons and fonts, or plain shapes, the original icons and the phone's
     * font. Called from onCreate, from the drawer switch, and from onResume.
     */
    private fun applyUiStyle() {
        val pixel = UiStyle.isPixel(this)
        if (appliedPixelStyle == pixel) return
        appliedPixelStyle = pixel

        val border = themed(R.color.hf_px_border)
        val shadow = themed(R.color.hf_px_shadow)
        val start = findViewById<View>(R.id.start_button)
        val wake = findViewById<View>(R.id.wake_word_button)
        start.background = UiStyle.circle(this, themed(R.color.hf_circle_idle_bg), border, shadow)
        wake.background = UiStyle.circle(this, themed(R.color.hf_circle_off_bg), border, shadow)
        val elevation = if (pixel) 0f else 4f * resources.displayMetrics.density
        start.elevation = elevation
        wake.elevation = elevation

        val unit = start.pixelUnit()
        listOf(R.id.status_card, R.id.pipeline_card).forEach { id ->
            findViewById<View>(id).background =
                UiStyle.frame(this, themed(R.color.hf_card), border, shadow, unit)
        }
        findViewById<View>(R.id.warning_card).background =
            UiStyle.frame(this, themed(R.color.hf_warning_bg), border, shadow, unit)

        debugButton.setImageResource(UiStyle.menuIcon(this))
        findViewById<ImageView>(R.id.settings_button).setImageResource(UiStyle.settingsIcon(this))
        findViewById<ImageView>(R.id.wake_word_icon).setImageResource(UiStyle.callSignIcon(this))

        val label = UiStyle.labelFont(this)
        val body = UiStyle.bodyFont(this)
        listOf(
            R.id.app_title, R.id.debug_title, R.id.status_label,
            R.id.transcribed_label, R.id.translated_label, R.id.intent_label
        ).forEach { findViewById<TextView>(it).typeface = label }
        listOf(
            R.id.subtitle_text, R.id.start_caption, R.id.wake_word_caption,
            R.id.status_text, R.id.intent_text, R.id.style_switch
        ).forEach { findViewById<TextView>(it).typeface = body }
        findViewById<TextView>(R.id.app_title).letterSpacing = if (pixel) 0f else 0.02f

        if (::startIcon.isInitialized) {
            setStartButtonState(running = pipelineActive)
            updateWakeWordButton()
        }
    }

    /** The state light on the status card. */
    private fun setStatusDot(colorRes: Int) {
        statusDot.backgroundTintList = ColorStateList.valueOf(themed(colorRes))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets(R.id.root_scroll)
        applySystemBarInsets(R.id.debug_drawer)

        installCrashHandler()
        setUpDrawerAndPixelFrames()

        statusText = findViewById(R.id.status_text)
        statusDot = findViewById(R.id.status_dot)
        transcribedText = findViewById(R.id.transcribed_text)
        translatedText = findViewById(R.id.translated_text)
        intentText = findViewById(R.id.intent_text)
        warningCard = findViewById(R.id.warning_card)
        warningText = findViewById(R.id.warning_text)

        startButton = findViewById(R.id.start_button)
        startIcon = findViewById(R.id.start_icon)
        startCaption = findViewById(R.id.start_caption)
        wakeWordButton = findViewById(R.id.wake_word_button)
        wakeWordIcon = findViewById(R.id.wake_word_icon)
        wakeWordCaption = findViewById(R.id.wake_word_caption)
        settingsButton = findViewById(R.id.settings_button)

        subtitleText = findViewById(R.id.subtitle_text)
        statusLabel = findViewById(R.id.status_label)
        transcribedLabel = findViewById(R.id.transcribed_label)
        translatedLabel = findViewById(R.id.translated_label)
        intentLabel = findViewById(R.id.intent_label)

        wakeWordOn = readWakeWordPref()
        wakeWordButton.setOnClickListener { toggleWakeWord() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Load LevenshteinMatcher for intent display in the debug UI
        levenshteinMatcher = runCatching {
            val json = assets.open("intent-vocabulary.json").bufferedReader().use { it.readText() }
            LevenshteinMatcher.fromAssets(json)
        }.getOrNull()

        // Fresh start: clear the debug fields and reset state.
        transcribedText.text = "..."
        translatedText.text = "..."
        intentText.text = "..."
        statusText.setTextColor(themed(R.color.hf_text_primary))
        pipelineActive = false
        setStartButtonEnabled(true)

        startButton.setOnClickListener {
            if (pipelineActive) {
                stopOverlayService()
            } else if (!consented()) {
                // Nothing reaches the microphone before consent. Stopping is
                // always allowed: a consent gate that traps a running mic is
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

        // Last, because it paints both circles and needs wakeWordOn loaded.
        applyLanguage()
    }

    /**
     * Install a global [Thread.setDefaultUncaughtExceptionHandler] that
     * persists the stack trace to [CrashLog] *before* delegating to the
     * previous handler (which terminates the process via RuntimeInit).
     *
     * This catches Java/Kotlin exceptions only. A native SIGSEGV from a
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
        wakeWordOn = readWakeWordPref()
        // The language toggle lives on the settings screen now, so the choice
        // can change while this activity is stopped. Repaint before anything
        // reads a label. The style switch lives there too.
        applyUiStyle()
        applyLanguage()
        advanceOnboarding()
    }

    /**
     * One place decides which screen a first-run tester sees, because
     * `onResume` fires every time they come back from the Settings app and the
     * steps have to resume mid-flow rather than restart.
     *
     * Strict order: language, consent, how-to, then remaining permissions. The
     * how-to is owed the moment the **accessibility service** goes on, not when
     * every permission is complete: a tester who refuses the Phone permission
     * would otherwise finish setup having never been told how to talk to the
     * app at all.
     */
    private fun advanceOnboarding() {
        if (flowDialog?.isShowing == true) return

        if (!Copy.isChosen(this)) { showLanguageChooser(); return }

        // Consent does NOT depend on which permissions happen to be missing.
        // It gated on that once, so a tester who had already granted everything
        // was never asked, and, worse, the consent screen surfaced later,
        // attached to whatever action next triggered onResume.
        // A tester who declined is not walked through the permission chain
        // anyway: the whole flow stops here until they either accept or
        // uninstall, which is what the consent screen told them.
        if (!introAccepted()) {
            if (!introDeferred) showIntro()
            return
        }

        if (isAccessibilityServiceEnabled() && !howToShown()) {
            markHowToShown()
            showHowTo()
            return
        }

        val missing = missingSetup()
        if (missing.isNotEmpty()) {
            isSettingUp = true
            showSetupGuide(missing)
        } else if (!backgroundStepShown()) {
            markBackgroundStepShown()
            showBackgroundStep()
        } else if (isSettingUp) {
            isSettingUp = false
            Toast.makeText(this, Copy.get(this, Copy.Key.SETUP_DONE), Toast.LENGTH_SHORT).show()
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
     * after the language choice: it was waiting for the next `onResume`, and
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

    // ---- step 0: display language ----

    /**
     * Asked before anything else, because the consent screen is the first thing
     * after it and consent nobody could read is not consent.
     *
     * Tagalog and English only. The app still hears Taglish, which is what the
     * lexicon does, but there is no coherent Taglish register to write an
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
     * Repaint every visible label in the tester's chosen language.
     *
     * The debug screen used to be permanently half-Tagalog ("Narinig, Salin &
     * Intent Log" stayed put whichever language you picked) so the toggle
     * looked broken to anyone who used it. The layout ships these blank and
     * this is the only place that fills them, which means a missed one shows up
     * as an empty control rather than as stale text in the wrong language that
     * nobody notices.
     */
    private fun applyLanguage() {
        subtitleText.text = Copy.get(this, Copy.Key.SUBTITLE)
        statusLabel.text = Copy.get(this, Copy.Key.CARD_STATUS)
        transcribedLabel.text = Copy.get(this, Copy.Key.CARD_HEARD)
        translatedLabel.text = Copy.get(this, Copy.Key.CARD_TRANSLATED)
        intentLabel.text = Copy.get(this, Copy.Key.CARD_INTENT)
        settingsButton.contentDescription = Copy.get(this, Copy.Key.OPEN_SETTINGS)
        debugTitle.text = Copy.get(this, Copy.Key.DEBUG_TITLE)
        debugButton.contentDescription = Copy.get(this, Copy.Key.DEBUG_OPEN)
        styleSwitch.text = Copy.get(this, Copy.Key.STYLE_SWITCH)

        setStartButtonState(running = pipelineActive)
        updateWakeWordButton()

        // statusText holds computed text, not a fixed label, so it needs
        // recomputing rather than reassigning.
        updatePermissionStatus()
        // Only the hint follows the toggle; the transcript panes hold pipeline
        // output, which is whatever the tester actually said.
        val shown = statusText.text.toString()
        if (shown.isBlank() || shown == "..." || shown.startsWith("Ready") || shown.startsWith("Handa")) {
            statusText.text = Copy.get(this, Copy.Key.ACTIVATOR_HINT)
        }
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
     * consent screen has been accepted. `introDeferred` is not consulted:
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
     * First-run how-to only. The re-openable copy is on the settings screen;
     * this one is part of the flow, so the permission guide has to pick up
     * where it left off when the tester dismisses it.
     */
    private fun showHowTo() {
        showFlowDialog(
            scrollingDialog(
                Copy.get(this, Copy.Key.HOWTO_TITLE),
                Copy.get(this, Copy.Key.HOWTO_BODY)
            )
                .setPositiveButton(Copy.get(this, Copy.Key.HOWTO_BUTTON), null)
                .setCancelable(false)
        ).setOnDismissListener { advanceOnboarding() }
    }

    // ---- step 3: keep the app alive in the background ----

    private fun backgroundStepShown(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_BACKGROUND_SHOWN, false)

    private fun markBackgroundStepShown() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_BACKGROUND_SHOWN, true).apply()
    }

    private fun ignoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true

    /**
     * Shown once, after every permission is granted. HiOS on the TECNO LJ9
     * killed the process when the app was swiped from Recents, leaving the
     * accessibility service enabled but not running. The battery exemption
     * and a Recents lock are the two things a tester can do about that. The
     * same screen warns against the volume-key shortcut, which on that phone
     * switched the whole service off.
     *
     * Shown once rather than until granted: a tester who refuses the
     * exemption must still be able to use the app.
     */
    private fun showBackgroundStep() {
        val builder = AlertDialog.Builder(this)
            .setTitle(Copy.get(this, Copy.Key.BACKGROUND_TITLE))
            .setMessage(Copy.get(this, Copy.Key.BACKGROUND_BODY))
            .setNegativeButton(Copy.get(this, Copy.Key.SETUP_CANCEL), null)
            .setCancelable(false)
        if (ignoringBatteryOptimizations()) {
            builder.setPositiveButton(Copy.get(this, Copy.Key.SETUP_NEXT), null)
        } else {
            builder.setPositiveButton(Copy.get(this, Copy.Key.BACKGROUND_ALLOW)) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                    )
                }.onFailure {
                    CrashLog.exception(this, "battery exemption request", it)
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        showFlowDialog(builder).setOnDismissListener { advanceOnboarding() }
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
     * user knows what to fix before pressing the start circle.
     */
    private fun updatePermissionStatus() {
        val missing = missingSetup().toMutableList()
        if (missing.isNotEmpty()) {
            statusText.text = getString(
                if (Copy.lang(this) == Copy.Lang.TL) R.string.ui_missing_tl
                else R.string.ui_missing_en,
                missing.joinToString()
            )
            setStatusDot(R.color.hf_status_error)
            statusShowsMissing = true
        } else if (statusShowsMissing) {
            statusText.text = Copy.get(this, Copy.Key.ACTIVATOR_HINT)
            setStatusDot(R.color.hf_text_label)
            statusShowsMissing = false
        }
    }

    private fun missingSetup(): List<String> = buildList {
        if (!hasRecordAudioPermission()) add("RECORD_AUDIO")
        if (!Settings.canDrawOverlays(this@MainActivity)) add("OVERLAY")
        if (!isAccessibilityServiceEnabled()) add("ACCESSIBILITY")
        if (!hasAnswerCallPermission()) add("PHONE")
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
        setStartButtonState(running = true)
        setStartButtonEnabled(false) // re-enabled once LISTENING fires
        Toast.makeText(this, "Pipeline starting...", Toast.LENGTH_SHORT).show()
        statusText.text = "PREPARING"
        setStatusDot(R.color.hf_status_active)
        transcribedText.text = "..."
        translatedText.text = "..."
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP_PIPELINE
        }
        startService(intent)
        pipelineActive = false
        setStartButtonState(running = false)
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
                intentText.setTextColor(themed(R.color.hf_intent))
                intentText.text = if (match != null) {
                    "${match.first}  (matched: \"${match.second}\")"
                } else {
                    "No match"
                }
            } else {
                transcribedText.text = "..."
                translatedText.text = "..."
                intentText.text = "..."
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
                "PREPARING" -> {
                    showLoading("Loading speech model...\nFirst launch copies offline files.")
                    setStatusDot(R.color.hf_status_active)
                }
                "LISTENING" -> {
                    // A new utterance starts clean: the last command's warning
                    // says nothing about this one.
                    warningCard.visibility = View.GONE
                    hideLoading()
                    setStartButtonEnabled(true)
                    setStartButtonState(running = true)
                    pipelineActive = true
                    statusText.setTextColor(themed(R.color.hf_status_active))
                    statusText.text = Copy.get(this, Copy.Key.STATUS_LISTENING)
                    setStatusDot(R.color.hf_status_active)
                }
                "STOPPED", "IDLE" -> {
                    hideLoading()
                    setStartButtonEnabled(true)
                    setStartButtonState(running = false)
                    pipelineActive = false
                    statusText.setTextColor(themed(R.color.hf_text_primary))
                    statusText.text = Copy.get(this, Copy.Key.ACTIVATOR_HINT)
                    setStatusDot(R.color.hf_text_label)
                }
                "MODEL_ERROR" -> {
                    hideLoading()
                    setStartButtonEnabled(true)
                    setStartButtonState(running = false)
                    pipelineActive = false
                    statusText.setTextColor(themed(R.color.hf_status_error))
                    setStatusDot(R.color.hf_status_error)
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
            intentText.text = if (success) "$intent: $message" else "$intent FAILED: $message"
            intentText.setTextColor(
                themed(if (success) R.color.hf_status_success else R.color.hf_status_error)
            )
            setStatusDot(if (success) R.color.hf_status_success else R.color.hf_status_error)
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
            setStartButtonEnabled(true)
            setStartButtonState(running = false)
            statusText.setTextColor(themed(R.color.hf_status_error))
            statusText.text = message
            setStatusDot(R.color.hf_status_error)
        }
    }

    private fun showSetupGuide(missing: List<String>) {
        if (missing.isEmpty()) {
            isSettingUp = false
            Toast.makeText(this, Copy.get(this, Copy.Key.SETUP_DONE), Toast.LENGTH_SHORT).show()
            return
        }

        isSettingUp = true

        val message = StringBuilder(Copy.get(this, Copy.Key.SETUP_INTRO) + "\n\n")
        if ("OVERLAY" in missing) {
            message.append(Copy.get(this, Copy.Key.SETUP_OVERLAY) + "\n")
        }
        if ("ACCESSIBILITY" in missing) {
            message.append(Copy.get(this, Copy.Key.SETUP_A11Y) + "\n")
        }
        if ("RECORD_AUDIO" in missing) {
            message.append(Copy.get(this, Copy.Key.SETUP_MIC) + "\n")
        }
        if ("PHONE" in missing) {
            message.append(Copy.get(this, Copy.Key.SETUP_PHONE) + "\n")
        }

        showFlowDialog(
            AlertDialog.Builder(this)
            .setTitle(Copy.get(this, Copy.Key.SETUP_TITLE))
            .setMessage(message.toString().trim())
            .setPositiveButton(Copy.get(this, Copy.Key.SETUP_NEXT)) { _, _ ->
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
            .setNegativeButton(Copy.get(this, Copy.Key.SETUP_CANCEL)) { _, _ -> isSettingUp = false }
        )
    }

    private companion object {
        const val PREFS = OverlayService.PREFS
        const val PREF_INTRO_ACCEPTED = "intro_accepted"
        const val PREF_HOWTO_SHOWN = "howto_shown"
        const val PREF_BACKGROUND_SHOWN = "background_step_shown"
    }
}
