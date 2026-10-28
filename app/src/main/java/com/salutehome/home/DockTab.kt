package com.salutehome.home

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.PathInterpolator
import com.salutehome.core.dp

/**
 * The little handle at the bottom centre that says the app library is under the screen's edge: a short
 * quiet pill [LIFT] above the edge. Down in the dock raises it, white, and the library slides up after it;
 * it sinks back when the library closes. No text and no icon, just the handle.
 */
class DockTab(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = REST }
    private val shape = Path()
    private val box = RectF()
    private val hidden = context.dp(H - LIFT - PILL)
    private var colour: ValueAnimator? = null

    init {
        translationY = hidden
    }

    /** Up (white, whole) while the library is open; down (peeking, quiet) otherwise. */
    fun raise(up: Boolean) {
        animate().cancel()
        animate().translationY(if (up) 0f else hidden).setDuration(if (up) 200 else 240)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        colour?.cancel()
        colour = ValueAnimator.ofObject(ArgbEvaluator(), paint.color, if (up) LIT else REST).apply {
            duration = 200
            addUpdateListener {
                paint.color = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A pill at the top of the view; the view's lower part only gives it room to rise.
        val pill = context.dp(PILL)
        box.set(0f, 0f, w.toFloat(), pill)
        shape.reset()
        shape.addRoundRect(box, pill / 2f, pill / 2f, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(shape, paint)
    }

    companion object {
        /** Its size (dp): the handle's width, and the height it rises through. */
        const val W = 64f
        const val H = 22f

        /** The pill's thickness, and how far above the screen's edge it rests (dp). */
        private const val PILL = 5f
        private const val LIFT = 10f
        private const val REST = 0x40FFFFFF // white at 25%
        private const val LIT = 0xFFFFFFFF.toInt()
    }
}
