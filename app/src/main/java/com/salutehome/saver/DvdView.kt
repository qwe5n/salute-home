package com.salutehome.saver

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import com.salutehome.R

/**
 * The screensaver's screen: the logo on its own small hardware layer, moved by its translation each
 * frame, so a frame is one textured square in a new place (the logo redraws only when its colour
 * changes, and fades by the layer's alpha). The black is the dream window's own background. Everything is
 * laid out on the prototype's 960 x 540 screen and scaled to the real one, whatever the dream window's
 * density.
 */
@SuppressLint("ViewConstructor")
internal class DvdView(context: Context, val motion: DvdMotion) : ViewGroup(context) {
    val logo = Logo(context)
    private var scale = 1f

    init {
        addView(logo)
        if (!isInEditMode) logo.setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        scale = w / DvdMotion.SCREEN_W.toFloat()
        logo.measure(
            MeasureSpec.makeMeasureSpec((DvdMotion.LOGO_W * scale).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((DvdMotion.LOGO_H * scale).toInt(), MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        logo.layout(0, 0, logo.measuredWidth, logo.measuredHeight)
        place(0.0)
    }

    /** The logo where the motion has it at [now]: position, colour and the corner pulse. */
    fun place(now: Double) {
        logo.translationX = (motion.x * scale).toFloat()
        logo.translationY = (motion.y * scale).toFloat()
        val s = motion.scale(now)
        logo.scaleX = s
        logo.scaleY = s
        logo.colour = DvdMotion.COLOURS[motion.colour]
    }

    /** The DVD Video logo (res/drawable/dvd_video_logo.xml, the official artwork), tinted [colour]. */
    internal class Logo(context: Context) : View(context) {
        private val art = context.getDrawable(R.drawable.dvd_video_logo)!!.mutate()

        var colour = 0
            set(value) {
                if (field == value) return
                field = value
                art.setTint(value)
                invalidate()
            }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            pivotX = w / 2f
            pivotY = h / 2f
            art.setBounds(0, 0, w, h)
        }

        override fun onDraw(canvas: Canvas) {
            art.draw(canvas)
        }
    }
}
