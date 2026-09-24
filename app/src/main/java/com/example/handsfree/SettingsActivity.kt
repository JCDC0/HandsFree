package com.example.handsfree

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.handsfree.service.OverlayService
import com.example.handsfree.util.Copy
import com.example.handsfree.util.CrashLog
import com.example.handsfree.util.applySystemBarInsets
import com.example.handsfree.util.hasAnswerCallPermission
import com.example.handsfree.util.isAccessibilityServiceEnabled
import com.example.handsfree.util.scrollingDialog
import com.example.handsfree.util.themed
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the debug screen used to carry below the activator buttons.
 *
 * Added in 1.5.2. [MainActivity] had grown nine stacked bars under the
 * transcript cards, of which a tester touches seven exactly once: the
 * microphone-open cap, the route into Accessibility settings, two log
 * viewers, the export, the how-to, and the display language. They are all
 * here now, behind the cogwheel, and the debug screen keeps only the
 * pipeline and the two round activators.
 *
 * Nothing about the pipeline runs here. The screen reads and writes the same
 * `handsfree_prefs` file [MainActivity] does, and [OverlayService] re-reads
 * the microphone window on every activation, so a change takes effect on the
 * next command with no restart and nothing to notify.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsTitle: TextView
    private lateinit var sectionListening: TextView
    private lateinit var sectionLogs: TextView
    private lateinit var sectionHelp: TextView
    private lateinit var backButton: ImageView

    private lateinit var listenWindowLabel: TextView
    private lateinit var listenWindowHint: TextView
    private lateinit var listenWindowSeek: SeekBar

    private lateinit var permButton: Button
    private lateinit var showHistoryButton: Button
    private lateinit var showLogButton: Button
    private lateinit var howToButton: Button
    private lateinit var languageButton: Button
    private lateinit var buildStamp: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBarInsets(R.id.settings_scroll)

        settingsTitle = findViewById(R.id.settings_title)
        sectionListening = findViewById(R.id.section_listening)
        sectionLogs = findViewById(R.id.section_logs)
        sectionHelp = findViewById(R.id.section_help)
        backButton = findViewById(R.id.back_button)

        listenWindowLabel = findViewById(R.id.listen_window_label)
        listenWindowHint = findViewById(R.id.listen_window_hint)
        listenWindowSeek = findViewById(R.id.listen_window_seek)

        permButton = findViewById(R.id.perm_button)
        showHistoryButton = findViewById(R.id.show_history_button)
        showLogButton = findViewById(R.id.show_log_button)
        howToButton = findViewById(R.id.how_to_button)
        languageButton = findViewById(R.id.language_button)
        buildStamp = findViewById(R.id.build_stamp)

        backButton.setOnClickListener { finish() }
        permButton.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        showHistoryButton.setOnClickListener { showHistory() }
        showLogButton.setOnClickListener { showSystemLog() }
        howToButton.setOnClickListener { showHowTo() }
        languageButton.setOnClickListener { toggleLanguage() }

        setUpListenWindow()
        applyLanguage()
    }

    /**
     * Repaint every visible label in the tester's chosen language.
     *
     * Same rule as the debug screen: the layout ships these blank and this is
     * the only place that fills them, so a label nobody wired up shows as an
     * empty control instead of as stale text in the wrong language.
     */
    private fun applyLanguage() {
        settingsTitle.text = Copy.get(this, Copy.Key.SETTINGS_TITLE)
        sectionListening.text = Copy.get(this, Copy.Key.SECTION_LISTENING)
        sectionLogs.text = Copy.get(this, Copy.Key.SECTION_LOGS)
        sectionHelp.text = Copy.get(this, Copy.Key.SECTION_HELP)
        backButton.contentDescription = Copy.get(this, Copy.Key.BACK)

        permButton.text = Copy.get(this, Copy.Key.BTN_ACCESSIBILITY)
        showHistoryButton.text = Copy.get(this, Copy.Key.BTN_HISTORY)
        showLogButton.text = Copy.get(this, Copy.Key.BTN_SYSLOG)
        howToButton.text = Copy.get(this, Copy.Key.BTN_HOWTO)

        updateLanguageButton()
        renderListenWindow()
        buildStamp.text = "${BuildConfig.BUILD_STAMP} / ${BuildConfig.BUILD_DATE}"
    }

    // ---- display language ----

    /** Names the language you would switch **to**, not the one you are in. */
    private fun updateLanguageButton() {
        val other = if (Copy.lang(this) == Copy.Lang.TL) Copy.Lang.EN else Copy.Lang.TL
        languageButton.text = "${Copy.lang(this).label} → ${other.label}"
    }

    /**
     * Flip the display language. No activity recreation and no locale switch
     * (see [Copy] for why), so the visible text is re-read by hand here.
     * MainActivity repaints itself in `onResume`, which fires when this screen
     * is closed.
     */
    private fun toggleLanguage() {
        val next = if (Copy.lang(this) == Copy.Lang.TL) Copy.Lang.EN else Copy.Lang.TL
        Copy.setLang(this, next)
        applyLanguage()
        Toast.makeText(this, next.label, Toast.LENGTH_SHORT).show()
    }

    // ---- microphone-open cap ----

    /**
     * Slider for [OverlayService.PREF_LISTEN_WINDOW_S].
     *
     * This is the cap on how long the mic stays open when the VAD never works
     * out that the speaker stopped, not a delay every command pays. A normal
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
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    // Persist as it moves. OverlayService re-reads the pref on
                    // every activation, so there is nothing to notify and no
                    // restart needed: the next command uses the new value.
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt(
                            OverlayService.PREF_LISTEN_WINDOW_S,
                            progress + OverlayService.MIN_LISTEN_WINDOW_S
                        ).apply()
                    renderListenWindow()
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            }
        )
    }

    private fun listenWindowSeconds(): Int =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(OverlayService.PREF_LISTEN_WINDOW_S, OverlayService.DEFAULT_LISTEN_WINDOW_S)
            .coerceIn(OverlayService.MIN_LISTEN_WINDOW_S, OverlayService.MAX_LISTEN_WINDOW_S)

    private fun renderListenWindow() {
        listenWindowLabel.text =
            "${Copy.get(this, Copy.Key.LISTEN_WINDOW_LABEL)}: ${listenWindowSeconds()}s"
        listenWindowHint.text = Copy.get(this, Copy.Key.LISTEN_WINDOW_HINT)
    }

    // ---- logs ----

    private fun showHistory() {
        OverlayService.ensureHistoryLoaded(this)
        val history = if (OverlayService.transcriptHistory.isEmpty()) {
            Copy.get(this, Copy.Key.HISTORY_EMPTY)
        } else {
            OverlayService.transcriptHistory.joinToString("\n\n---\n\n")
        }
        val body = TextView(this).apply {
            text = history
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
            setTextColor(themed(R.color.hf_text_body))
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(body)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        lateinit var dialog: AlertDialog
        fun dialogButton(key: Copy.Key, bg: Int, fg: Int, onTap: () -> Unit) =
            Button(this).apply {
                text = Copy.get(this@SettingsActivity, key)
                contentDescription = text
                isAllCaps = false
                backgroundTintList = android.content.res.ColorStateList.valueOf(themed(bg))
                setTextColor(themed(fg))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(8, 4, 8, 4) }
                setOnClickListener { onTap() }
            }
        fun row(vararg buttons: Button) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(32, 0, 32, 0)
            buttons.forEach { addView(it) }
        }

        val copyButton = dialogButton(Copy.Key.DLG_COPY, R.color.hf_button_neutral_bg, R.color.hf_button_neutral_text) {
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("HandsFree Transcript History", history))
            Toast.makeText(this, Copy.get(this, Copy.Key.COPIED), Toast.LENGTH_SHORT).show()
        }
        val closeButton = dialogButton(Copy.Key.DLG_CLOSE, R.color.hf_button_danger_bg, R.color.hf_button_danger_text) {
            dialog.dismiss()
        }
        val exportButton = dialogButton(Copy.Key.DLG_EXPORT, R.color.hf_button_accent_bg, R.color.hf_button_accent_text) {
            confirmExport()
        }
        val clearButton = dialogButton(Copy.Key.DLG_CLEAR, R.color.hf_button_neutral_bg, R.color.hf_button_neutral_text) {
            confirmClear { dialog.dismiss() }
        }

        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
            addView(scroll)
            addView(row(copyButton, closeButton))
            addView(row(exportButton, clearButton))
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("${Copy.get(this, Copy.Key.DLG_HISTORY)} (${OverlayService.HISTORY_LIMIT})")
            .setView(content)
            .create()
        dialog.show()
    }

    /**
     * Ask before wiping the command history. The history is the tester's
     * evidence, so the confirm names what is lost and that export comes first.
     */
    private fun confirmClear(onCleared: () -> Unit) {
        if (OverlayService.transcriptHistory.isEmpty()) {
            Toast.makeText(this, Copy.get(this, Copy.Key.HISTORY_EMPTY), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(Copy.get(this, Copy.Key.CLEAR_TITLE))
            .setMessage(Copy.get(this, Copy.Key.CLEAR_BODY))
            .setPositiveButton(Copy.get(this, Copy.Key.DLG_CLEAR)) { _, _ ->
                OverlayService.transcriptHistory.clear()
                runCatching { File(filesDir, "transcript.log").delete() }
                CrashLog.info(this, "command history cleared by the tester")
                Toast.makeText(this, Copy.get(this, Copy.Key.CLEARED), Toast.LENGTH_SHORT).show()
                onCleared()
            }
            .setNegativeButton(Copy.get(this, Copy.Key.EXPORT_CANCEL), null)
            .show()
    }

    private fun showSystemLog() {
        showTextDialog(
            Copy.get(this, Copy.Key.DLG_SYSLOG),
            CrashLog.read(this) + "\n\n----- PREVIOUS START -----\n\n" + CrashLog.readPrevious(this),
            clipLabel = "HandsFree crash log"
        )
    }

    /** Scrollable, selectable, copyable. Both log viewers are the same dialog. */
    private fun showTextDialog(title: String, body: String, clipLabel: String) {
        val scrollView = android.widget.ScrollView(this)
        scrollView.addView(
            TextView(this).apply {
                text = body
                setPadding(48, 48, 48, 48)
                setTextIsSelectable(true)
            }
        )
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setNeutralButton(Copy.get(this, Copy.Key.DLG_COPY)) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(clipLabel, body))
                Toast.makeText(this, Copy.get(this, Copy.Key.COPIED), Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(Copy.get(this, Copy.Key.DLG_CLOSE), null)
            .show()
    }

    private fun showHowTo() {
        scrollingDialog(
            Copy.get(this, Copy.Key.HOWTO_TITLE),
            Copy.get(this, Copy.Key.HOWTO_BODY)
        ).setPositiveButton(Copy.get(this, Copy.Key.HOWTO_BUTTON), null).show()
    }

    // ---- log export ----

    /**
     * Testers hand their results back through a Google Form, and a form cannot
     * read app-private storage. The log therefore has to leave the app as a
     * real file the tester can attach.
     *
     * They are told what is in it *before* it is written, not after. The claim
     * on that screen (command text only, no audio, no personal data) is
     * exactly what [buildReport] assembles; if you add a field there, change
     * the copy in `strings.xml` in the same commit.
     */
    private fun confirmExport() {
        OverlayService.ensureHistoryLoaded(this)
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
            putExtra(Intent.EXTRA_SUBJECT, "HandsFree tester log: ${file.name}")
            putExtra(
                Intent.EXTRA_TEXT,
                "HandsFree tester log. Command text only. No audio, no personal data."
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

        val wakeWordOn = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
            .getBoolean(OverlayService.PREF_WAKE_WORD, false)
        val history = OverlayService.transcriptHistory
        return buildString {
            appendLine("HandsFree tester log export")
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
            appendLine("Display lang : ${Copy.lang(this@SettingsActivity).label}")
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
            appendLine("its own. This file moves only if the tester sends it.")
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
            appendLine(CrashLog.read(this@SettingsActivity))
            appendLine()
            appendLine("-".repeat(60))
            appendLine("PREVIOUS SYSTEM LOG (the service instance before this one)")
            appendLine("-".repeat(60))
            appendLine()
            appendLine(CrashLog.readPrevious(this@SettingsActivity))
            appendLine()
            appendLine("-".repeat(60))
            appendLine("END OF EXPORT. Salamat po!")
        }
    }

    private companion object {
        const val PREFS = OverlayService.PREFS
    }
}
