package com.example.handsfree.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Pixel-art frames for the W3 visual pass.
 *
 * Drawn in code rather than as 9-patch images so one class covers every size,
 * follows the light and dark palettes through plain colour ints, and stays
 * crisp at any density: every edge lands on a whole number of "pixels" of
 * [px] device pixels.
 *
 * Both drawables paint three layers: a hard drop shadow offset one pixel down
 * and right, a border in [border], and the fill in [fill] inset by one pixel.
 */
class PixelFrameDrawable(
    fill: Int,
    private val border: Int,
    private val shadow: Int,
    private val px: Float,
    private val steps: Int = 2
) : Drawable() {

    var fill: Int = fill
        set(value) { field = value; invalidateSelf() }

    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right - px
        val bt = b.bottom - px
        paint.color = shadow
        canvas.drawPath(stepped(l + px, t + px, r + px, bt + px), paint)
        paint.color = border
        canvas.drawPath(stepped(l, t, r, bt), paint)
        paint.color = fill
        canvas.drawPath(stepped(l + px, t + px, r - px, bt - px), paint)
    }

    /** Rectangle whose four corners are cut as a staircase of [steps] pixels. */
    private fun stepped(l: Float, t: Float, r: Float, b: Float): Path {
        val s = px
        val n = steps
        return Path().apply {
            moveTo(l + n * s, t)
            lineTo(r - n * s, t)
            for (i in 1..n) { lineTo(r - (n - i) * s, t + (i - 1) * s); lineTo(r - (n - i) * s, t + i * s) }
            lineTo(r, b - n * s)
            for (i in 1..n) { lineTo(r - (i - 1) * s, b - (n - i) * s); lineTo(r - i * s, b - (n - i) * s) }
            lineTo(l + n * s, b)
            for (i in 1..n) { lineTo(l + (n - i) * s, b - (i - 1) * s); lineTo(l + (n - i) * s, b - i * s) }
            lineTo(l, t + n * s)
            for (i in 1..n) { lineTo(l + (i - 1) * s, t + (n - i) * s); lineTo(l + i * s, t + (n - i) * s) }
            close()
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * A circle rasterised onto a coarse grid, the way a pixel-art sprite draws
 * one. The activators stay round and large (the target size is the reason
 * they are circles), but read as pixel art.
 *
 * A grid cell is filled when its centre lies inside the circle, and becomes
 * border when any of its four neighbours lies outside.
 */
class PixelCircleDrawable(
    fill: Int,
    private val border: Int,
    private val shadow: Int,
    private val cells: Int = 20
) : Drawable() {

    var fill: Int = fill
        set(value) { field = value; invalidateSelf() }

    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

    private fun inside(x: Int, y: Int): Boolean {
        val c = (cells - 1) / 2f
        val dx = x - c
        val dy = y - c
        val rad = cells / 2f - 0.5f
        return dx * dx + dy * dy <= rad * rad
    }

    override fun draw(canvas: Canvas) {
        // One spare cell on every side, and the circle itself centred in the
        // bounds. The shadow falls into the spare cell at the right and
        // bottom. Before 1.6.1 the grid was cells + 1 wide with the circle in
        // the top-left, so the circle sat half a cell up and left of the view
        // centre and the centred icon looked pushed to the lower right.
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        val cell = size / (cells + 2)
        val ox = bounds.left + (bounds.width() - cells * cell) / 2f
        val oy = bounds.top + (bounds.height() - cells * cell) / 2f
        fun cellAt(x: Int, y: Int, shift: Float, color: Int) {
            paint.color = color
            val l = ox + x * cell + shift
            val t = oy + y * cell + shift
            canvas.drawRect(l, t, l + cell + 0.5f, t + cell + 0.5f, paint)
        }
        for (y in 0 until cells) for (x in 0 until cells) {
            if (inside(x, y)) cellAt(x, y, cell, shadow)
        }
        for (y in 0 until cells) for (x in 0 until cells) {
            if (!inside(x, y)) continue
            val edge = !inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y - 1) || !inside(x, y + 1)
            cellAt(x, y, 0f, if (edge) border else fill)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** One pixel of the pixel-art grid, in device pixels. */
fun View.pixelUnit(dp: Float = 3f): Float = dp * resources.displayMetrics.density

/**
 * Set the fill of a view's pixel background. Replaces `backgroundTintList` on
 * the activators: a tint would flatten border, fill and shadow to one colour.
 */
fun View.setPixelFill(color: Int) {
    when (val bg = background) {
        is PixelCircleDrawable -> bg.fill = color
        is PixelFrameDrawable -> bg.fill = color
        is android.graphics.drawable.GradientDrawable -> bg.setColor(color)
        else -> backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }
}
