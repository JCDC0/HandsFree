package com.example.handsfree.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The screen-edge glow shown while HandsFree listens (W3, JC's request, after
 * the Gemini overlay).
 *
 * A rounded-rectangle stroke along the display edge, filled with a sweep
 * gradient that rotates. Its colour tells the state at a glance from anywhere
 * on the phone, which the small popup at the bottom cannot:
 *   LISTENING    blue to violet, rotating
 *   TRANSCRIBING amber, rotating faster
 *   SUCCESS      green, still
 *   FAILURE      red, still
 *
 * The glow is three strokes of falling alpha and rising width, not a blur:
 * a BlurMaskFilter needs a software layer, and redrawing a full-screen
 * software layer every frame costs more than this effect is worth.
 *
 * The window that holds this view never takes touches (see OverlayService).
 * When the system "Remove animations" setting is on, the gradient does not
 * rotate.
 */
class EdgeGlowView(context: Context) : View(context) {

    enum class State { LISTENING, TRANSCRIBING, SUCCESS, FAILURE }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val matrix = Matrix()
    private var angle = 0f
    private var cornerRadius = 0f

    var state: State = State.LISTENING
        set(value) {
            field = value
            gradient = null
            updateAnimator()
            invalidate()
        }

    private var gradient: SweepGradient? = null

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    private fun colors(): IntArray = when (state) {
        State.LISTENING -> intArrayOf(0xFF4C6EF5.toInt(), 0xFF9775FA.toInt(), 0xFF22B8CF.toInt(), 0xFF4C6EF5.toInt())
        State.TRANSCRIBING -> intArrayOf(0xFFFAB005.toInt(), 0xFFFF922B.toInt(), 0xFFFFE066.toInt(), 0xFFFAB005.toInt())
        State.SUCCESS -> intArrayOf(0xFF40C057.toInt(), 0xFF69DB7C.toInt(), 0xFF40C057.toInt())
        State.FAILURE -> intArrayOf(0xFFFA5252.toInt(), 0xFFFF8787.toInt(), 0xFFFA5252.toInt())
    }

    private fun updateAnimator() {
        val moving = state == State.LISTENING || state == State.TRANSCRIBING
        if (moving && ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow) {
            animator.duration = if (state == State.TRANSCRIBING) 1200L else 2600L
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = null
        cornerRadius = displayCornerRadius()
    }

    /** The display's own corner radius where Android reports it (API 31+). */
    private fun displayCornerRadius(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val corner = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
            if (corner != null && corner.radius > 0) return corner.radius.toFloat()
        }
        return 28f * density
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val g = gradient ?: SweepGradient(cx, cy, colors(), null).also { gradient = it }
        matrix.setRotate(angle, cx, cy)
        g.setLocalMatrix(matrix)
        paint.shader = g

        val passes = arrayOf(18f to 50, 10f to 110, 4f to 255)
        for ((widthDp, alpha) in passes) {
            val w = widthDp * density
            paint.strokeWidth = w
            paint.alpha = alpha
            rect.set(w / 2f, w / 2f, width - w / 2f, height - w / 2f)
            val r = (cornerRadius - w / 2f).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, r, r, paint)
        }
        paint.alpha = 255
        paint.shader = null
        paint.color = Color.TRANSPARENT
    }
}
