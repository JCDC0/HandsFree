package com.example.handsfree.util

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.content.res.ResourcesCompat
import com.example.handsfree.R

/**
 * The visual style switch in Settings, added in 1.6.1 at JC's request. The
 * team preferred the standard look, so pixel style is off by default (1.6.2).
 *
 * Pixel style (the W3 look): pixel-art circles and frames, pixel icons, and
 * the Silkscreen and Pixelify Sans fonts. Standard style: plain round
 * buttons, rounded cards, the original smooth icons, and the phone's own
 * font. The layout, the Debug Screen drawer and the screen-edge glow are the
 * same in both.
 *
 * Stored in `handsfree_prefs` so the main screen, the settings screen and
 * the overlay service all read one value. Default: standard style.
 *
 * Also holds the floating mic button switch ([bubbleEnabled]), the other
 * display option on the settings screen. Default: shown.
 */
object UiStyle {
    private const val PREFS = "handsfree_prefs"
    const val PREF_PIXEL = "pixel_style"

    const val PREF_BUBBLE = "bubble_enabled"
    const val PREF_BUBBLE_X = "bubble_x"
    const val PREF_BUBBLE_Y = "bubble_y"

    fun isPixel(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_PIXEL, false)

    fun bubbleEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_BUBBLE, true)

    const val PREF_SPEECH = "speech_enabled"

    /** W7 spoken feedback. Default on. */
    fun speechEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_SPEECH, true)

    fun setSpeechEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_SPEECH, on).apply()
    }

    fun setBubbleEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_BUBBLE, on).apply()
    }

    fun setPixel(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_PIXEL, on).apply()
    }

    fun micIcon(context: Context) = if (isPixel(context)) R.drawable.ic_mic else R.drawable.ic_mic_smooth
    fun stopIcon(context: Context) = if (isPixel(context)) R.drawable.ic_stop else R.drawable.ic_stop_smooth
    fun callSignIcon(context: Context) = if (isPixel(context)) R.drawable.ic_call_sign else R.drawable.ic_call_sign_smooth
    fun settingsIcon(context: Context) = if (isPixel(context)) R.drawable.ic_settings else R.drawable.ic_settings_smooth
    fun menuIcon(context: Context) = if (isPixel(context)) R.drawable.ic_menu_pixel else R.drawable.ic_menu_smooth

    /** Title and small labels: Silkscreen, or the phone's font in bold. */
    fun labelFont(context: Context): Typeface =
        if (isPixel(context)) ResourcesCompat.getFont(context, R.font.silkscreen) ?: Typeface.DEFAULT_BOLD
        else Typeface.DEFAULT_BOLD

    /** Captions and status: Pixelify Sans, or the phone's font. */
    fun bodyFont(context: Context): Typeface =
        if (isPixel(context)) ResourcesCompat.getFont(context, R.font.pixelify_sans) ?: Typeface.DEFAULT
        else Typeface.DEFAULT

    /** Activator or bubble background for the current style. */
    fun circle(context: Context, fill: Int, border: Int, shadow: Int, cells: Int = 20): Drawable =
        if (isPixel(context)) PixelCircleDrawable(fill, border, shadow, cells)
        else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }

    /** Card or popup background for the current style. */
    fun frame(context: Context, fill: Int, border: Int, shadow: Int, unit: Float, radiusDp: Float = 16f): Drawable =
        if (isPixel(context)) PixelFrameDrawable(fill, border, shadow, unit)
        else GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            setColor(fill)
        }
}
