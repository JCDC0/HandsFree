package com.example.handsfree.util

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.handsfree.R
import com.example.handsfree.service.HandsFreeAccessibilityService

/**
 * The parts both screens need.
 *
 * 1.5.2 split the debug screen in two, and these five things were used by
 * both halves. Copying them would have been the start of two screens that
 * drift: the status card and the exported log both report whether the
 * accessibility service is on, and they have to agree.
 */

/**
 * Resolve a colour that has a `values-night` counterpart. Never hardcode a
 * colour int in a screen — the debug UI ignored the system dark setting for
 * exactly that reason.
 */
fun Context.themed(colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

/** True when HandsFree appears in the system's enabled-services list. */
fun Context.isAccessibilityServiceEnabled(): Boolean {
    val enabled = Settings.Secure.getString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val expected = ComponentName(this, HandsFreeAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}

/**
 * Whether `ANSWER_PHONE_CALLS` is actually held. Checked rather than assumed:
 * [android.telecom.TelecomManager.acceptRingingCall] returns void and, on some
 * OEMs, silently does nothing instead of throwing when the permission is
 * missing, so "it did not throw" is not evidence that a call was answered.
 */
fun Context.hasAnswerCallPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Pad a screen's root view by the system bars and set the bar icons to
 * contrast with our own background.
 *
 * targetSdk 35 forces edge-to-edge, so with the action bar removed the content
 * draws *behind* the status bar and the title is clipped by it. The icon
 * colours are the other half: our background follows the system theme, so dark
 * icons go on the light palette and light icons on the dark one. Getting that
 * backwards makes the clock invisible.
 */
fun Activity.applySystemBarInsets(rootViewId: Int) {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(rootViewId)) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
        insets
    }
    val darkTheme = resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
    }
}

/**
 * A dialog whose body is long enough to need scrolling. Every screen that uses
 * this one is — the consent text alone does not fit on a phone — and
 * `AlertDialog.setMessage` truncates rather than scrolls on some OEM skins.
 */
fun Activity.scrollingDialog(title: String, body: String): AlertDialog.Builder {
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
        .setView(ScrollView(this).apply { addView(text) })
}
