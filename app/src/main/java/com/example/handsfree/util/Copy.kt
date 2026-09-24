package com.example.handsfree.util

import android.content.Context
import com.example.handsfree.R

/**
 * The tester's chosen **display** language, and the copy that follows it.
 *
 * Deliberately not Android's per-app locale machinery
 * (`AppCompatDelegate.setApplicationLocales` + a `values-tl/` folder). Three
 * reasons, in order of weight:
 *
 * 1. The choice is a research variable. It is written into the exported log, so
 *    a reviewer reading a tester's transcript knows which wording that tester
 *    actually read before consenting.
 * 2. `OverlayService` renders the same copy from a `Service`, and on `minSdk 30`
 *    the AppCompat locale backport only reliably covers AppCompat activities.
 *    Explicit pairs behave identically in both places.
 * 3. Switching locales recreates the activity. Doing that mid-onboarding, with
 *    a consent dialog on screen, is a state bug waiting to happen the night
 *    before a demo.
 *
 * **Tagalog and English are display languages only.** The app still understands
 * Taglish commands — that is the entire point of `translation-lexicon.json` —
 * but Taglish is not offered here, because an interface has to pick a register
 * and there is no consistent Taglish register to write one in. Saying "buksan
 * mo ang YouTube" works exactly the same with the interface set to English.
 */
object Copy {

    enum class Lang(val tag: String, val label: String) {
        TL("tl", "Tagalog"),
        EN("en", "English");

        companion object {
            fun fromTag(tag: String?): Lang = entries.firstOrNull { it.tag == tag } ?: TL
        }
    }

    /**
     * Every pair of strings that follows the toggle. Adding copy means adding a
     * `Key` with both halves — a missing translation is then a compile error
     * rather than a screen that silently reverts to the other language.
     */
    enum class Key(val tl: Int, val en: Int) {
        INTRO_TITLE(R.string.intro_title_tl, R.string.intro_title_en),
        INTRO_BODY(R.string.intro_body_tl, R.string.intro_body_en),
        INTRO_ACCEPT(R.string.intro_accept_tl, R.string.intro_accept_en),
        INTRO_DECLINE(R.string.intro_decline_tl, R.string.intro_decline_en),
        INTRO_DECLINED_TOAST(R.string.intro_declined_toast_tl, R.string.intro_declined_toast_en),

        HOWTO_TITLE(R.string.howto_title_tl, R.string.howto_title_en),
        HOWTO_BODY(R.string.howto_body_tl, R.string.howto_body_en),
        HOWTO_BUTTON(R.string.howto_button_tl, R.string.howto_button_en),

        EXPORT_TITLE(R.string.export_title_tl, R.string.export_title_en),
        EXPORT_PRIVACY(R.string.export_privacy_tl, R.string.export_privacy_en),
        EXPORT_CONFIRM(R.string.export_confirm_tl, R.string.export_confirm_en),
        EXPORT_CANCEL(R.string.export_cancel_tl, R.string.export_cancel_en),
        EXPORT_CHOOSER(R.string.export_chooser_tl, R.string.export_chooser_en),
        EXPORT_EMPTY(R.string.export_empty_tl, R.string.export_empty_en),

        ACTIVATOR_HINT(R.string.activator_hint_tl, R.string.activator_hint_en),

        SUBTITLE(R.string.ui_subtitle_tl, R.string.ui_subtitle_en),
        CARD_STATUS(R.string.ui_card_status_tl, R.string.ui_card_status_en),
        CARD_HEARD(R.string.ui_card_heard_tl, R.string.ui_card_heard_en),
        CARD_TRANSLATED(R.string.ui_card_translated_tl, R.string.ui_card_translated_en),
        CARD_INTENT(R.string.ui_card_intent_tl, R.string.ui_card_intent_en),
        BTN_START(R.string.ui_btn_start_tl, R.string.ui_btn_start_en),
        BTN_STOP(R.string.ui_btn_stop_tl, R.string.ui_btn_stop_en),
        BTN_CALLSIGN_ON(R.string.ui_btn_callsign_on_tl, R.string.ui_btn_callsign_on_en),
        BTN_CALLSIGN_OFF(R.string.ui_btn_callsign_off_tl, R.string.ui_btn_callsign_off_en),
        BTN_ACCESSIBILITY(R.string.ui_btn_accessibility_tl, R.string.ui_btn_accessibility_en),
        BTN_HISTORY(R.string.ui_btn_history_tl, R.string.ui_btn_history_en),
        BTN_SYSLOG(R.string.ui_btn_syslog_tl, R.string.ui_btn_syslog_en),
        BTN_HOWTO(R.string.ui_btn_howto_tl, R.string.ui_btn_howto_en),
        DLG_HISTORY(R.string.ui_dlg_history_tl, R.string.ui_dlg_history_en),
        DLG_SYSLOG(R.string.ui_dlg_syslog_tl, R.string.ui_dlg_syslog_en),
        DLG_COPY(R.string.ui_dlg_copy_tl, R.string.ui_dlg_copy_en),
        DLG_CLOSE(R.string.ui_dlg_close_tl, R.string.ui_dlg_close_en),
        DLG_EXPORT(R.string.ui_dlg_export_tl, R.string.ui_dlg_export_en),
        DEBUG_TITLE(R.string.ui_debug_title_tl, R.string.ui_debug_title_en),
        DEBUG_OPEN(R.string.ui_debug_open_tl, R.string.ui_debug_open_en),
        STYLE_SWITCH(R.string.ui_style_switch_tl, R.string.ui_style_switch_en),
        BUBBLE_SWITCH(R.string.ui_bubble_switch_tl, R.string.ui_bubble_switch_en),
        DLG_CLEAR(R.string.ui_dlg_clear_tl, R.string.ui_dlg_clear_en),
        CLEAR_TITLE(R.string.ui_clear_title_tl, R.string.ui_clear_title_en),
        CLEAR_BODY(R.string.ui_clear_body_tl, R.string.ui_clear_body_en),
        CLEARED(R.string.ui_cleared_tl, R.string.ui_cleared_en),
        COPIED(R.string.ui_copied_tl, R.string.ui_copied_en),
        SETUP_TITLE(R.string.ui_setup_title_tl, R.string.ui_setup_title_en),
        SETUP_INTRO(R.string.ui_setup_intro_tl, R.string.ui_setup_intro_en),
        SETUP_OVERLAY(R.string.ui_setup_overlay_tl, R.string.ui_setup_overlay_en),
        SETUP_A11Y(R.string.ui_setup_a11y_tl, R.string.ui_setup_a11y_en),
        SETUP_MIC(R.string.ui_setup_mic_tl, R.string.ui_setup_mic_en),
        SETUP_PHONE(R.string.ui_setup_phone_tl, R.string.ui_setup_phone_en),
        SETUP_NEXT(R.string.ui_setup_next_tl, R.string.ui_setup_next_en),
        SETUP_CANCEL(R.string.ui_setup_cancel_tl, R.string.ui_setup_cancel_en),
        SETUP_DONE(R.string.ui_setup_done_tl, R.string.ui_setup_done_en),
        BACKGROUND_TITLE(R.string.ui_background_title_tl, R.string.ui_background_title_en),
        BACKGROUND_BODY(R.string.ui_background_body_tl, R.string.ui_background_body_en),
        BACKGROUND_ALLOW(R.string.ui_background_allow_tl, R.string.ui_background_allow_en),
        POPUP_RETRY(R.string.popup_retry_tl, R.string.popup_retry_en),
        HISTORY_EMPTY(R.string.ui_history_empty_tl, R.string.ui_history_empty_en),
        MISSING(R.string.ui_missing_tl, R.string.ui_missing_en),
        STATUS_LISTENING(R.string.ui_status_listening_tl, R.string.ui_status_listening_en),

        LISTEN_WINDOW_LABEL(R.string.listen_window_label_tl, R.string.listen_window_label_en),
        LISTEN_WINDOW_HINT(R.string.listen_window_hint_tl, R.string.listen_window_hint_en),

        SETTINGS_TITLE(R.string.ui_settings_title_tl, R.string.ui_settings_title_en),
        SECTION_LISTENING(R.string.ui_section_listening_tl, R.string.ui_section_listening_en),
        SECTION_LOGS(R.string.ui_section_logs_tl, R.string.ui_section_logs_en),
        SECTION_HELP(R.string.ui_section_help_tl, R.string.ui_section_help_en),
        OPEN_SETTINGS(R.string.ui_open_settings_tl, R.string.ui_open_settings_en),
        BACK(R.string.ui_back_tl, R.string.ui_back_en)
    }

    private const val PREFS = "handsfree_prefs"
    private const val PREF_LANG = "display_language"

    /** True once the tester has actually been asked, rather than defaulted. */
    fun isChosen(context: Context): Boolean =
        prefs(context).contains(PREF_LANG)

    /**
     * Defaults to Tagalog. The testers are Filipino and the consent text is the
     * one screen where being understood matters most; an English default would
     * make the more careful reading the one you have to go looking for.
     */
    fun lang(context: Context): Lang =
        Lang.fromTag(prefs(context).getString(PREF_LANG, Lang.TL.tag))

    fun setLang(context: Context, lang: Lang) {
        prefs(context).edit().putString(PREF_LANG, lang.tag).apply()
    }

    fun get(context: Context, key: Key): String =
        context.getString(if (lang(context) == Lang.TL) key.tl else key.en)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
