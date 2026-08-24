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

        LISTEN_WINDOW_LABEL(R.string.listen_window_label_tl, R.string.listen_window_label_en),
        LISTEN_WINDOW_HINT(R.string.listen_window_hint_tl, R.string.listen_window_hint_en)
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
