package com.salutehome.volume

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.View
import com.salutehome.core.Fonts
import com.salutehome.core.Tokens
import com.salutehome.side.Volume
import com.salutehome.side.glyph
import kotlin.math.roundToInt

/**
 * The volume as the home's top bar shows it, grown: the volume pill (white, as a focused pill) opening to
 * the left into a slider while the level changes, with the speaker on the left and the number on the
 * right. It sits exactly over the top bar's volume pill (VolumePlace), so on the home screen the pill
 * itself seems to open; over an app it's the same pill in the same corner.
 *
 * Measured in the layout's 960-wide units scaled to the view's own width, as it shows in the
 * accessibility service's window at the TV's own density.
 */
internal class VolumePillView(context: Context) : View(context) {
    private var u = 1f
    private val pill = RectF()
    private val track = RectF()
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2E000000 }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
    private val number = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Fonts.semibold(context)
        color = INK
        fontFeatureSettings = "'tnum'"
    }
    private val glyphs: Array<Drawable> = Array(3) { context.glyph(Volume.GLYPHS[it], INK) }

    private var shown = 0
    private var label = "0"
    private var fillFrac = 0f
    private var fillFrom = 0f
    private var fillTo = 0f
    private val fillAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 180
        interpolator = Tokens.EASE_OUT
        addUpdateListener {
            fillFrac = fillFrom + (fillTo - fillFrom) * it.animatedFraction
            invalidate()
        }
    }

    /** The top bar's volume pill's width (px), which the opening starts from; negative: the usual size. */
    var closedWidthPx = -1f

    // 0: the size of the top bar's volume pill; 1: open into the slider.
    private var open = 1f
    private val openAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 320
        interpolator = Tokens.EASE_OUT
        addUpdateListener {
            open = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Shows [level]; [animate] slides the fill there (already on screen), else it jumps. */
    fun setLevel(level: VolumeLevel, animate: Boolean) {
        shown = if (level.muted) 0 else level.percent.coerceIn(0, 100)
        label = shown.toString()
        fillFrom = fillFrac
        fillTo = shown / 100f
        fillAnim.cancel()
        if (animate) fillAnim.start() else fillFrac = fillTo
        invalidate()
    }

    /** Coming on screen: grows out of the top bar's pill. */
    fun reveal() {
        openAnim.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            open = 1f
            invalidate()
            return
        }
        openAnim.setFloatValues(0f, 1f)
        openAnim.start()
    }

    /** Leaving: closes back into the pill (the window fades meanwhile). */
    fun close() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        openAnim.cancel()
        openAnim.setFloatValues(open, 0f)
        openAnim.start()
    }

    /** Gone from the screen: nothing left running. */
    fun settle() {
        fillAnim.cancel()
        openAnim.cancel()
        fillFrac = fillTo
        open = 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        u = h / VolumePlace.PILL_H
        number.textSize = 15f * u
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val closedW = if (closedWidthPx > 0f) closedWidthPx else CLOSED_W * u
        val pw = closedW + (w - closedW) * open
        pill.set(w - pw, 0f, w, h)
        canvas.drawRoundRect(pill, h / 2, h / 2, pillPaint)
        val a = (255 * ((open - 0.25f) / 0.75f).coerceIn(0f, 1f)).roundToInt()
        // The speaker on the left, the number on the right.
        val g = glyphs[Volume.glyphIndex(shown)]
        val gs = (18f * u).roundToInt()
        val gx = (pill.left + 12f * u).roundToInt()
        val gy = ((h - gs) / 2f).roundToInt()
        g.setBounds(gx, gy, gx + gs, gy + gs)
        g.alpha = 255
        g.draw(canvas)
        val nx = w - 14f * u - number.measureText(label)
        number.alpha = 255
        canvas.drawText(label, nx, h / 2f + number.textSize * 0.36f, number)
        if (a == 0) return
        // The slider between them.
        val left = gx + gs + 10f * u
        val right = w - 14f * u - number.measureText("100") - 10f * u
        if (right <= left) return
        val th = 4f * u
        track.set(left, h / 2f - th / 2f, right, h / 2f + th / 2f)
        trackPaint.alpha = (0x2E * a / 255)
        canvas.drawRoundRect(track, th / 2, th / 2, trackPaint)
        if (fillFrac > 0f) {
            track.right = left + (right - left) * fillFrac
            fillPaint.alpha = a
            canvas.drawRoundRect(track, th / 2, th / 2, fillPaint)
        }
    }

    private companion object {
        const val INK = 0xFF111114.toInt()

        /** The top bar's volume pill's width, in the same units. */
        const val CLOSED_W = 68.5f
    }
}
