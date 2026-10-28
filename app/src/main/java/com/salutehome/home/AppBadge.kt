package com.salutehome.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.salutehome.core.Tokens
import com.salutehome.core.dp
import kotlin.math.max
import kotlin.math.min

/**
 * The shelf of an app with nothing to show there (a VPN, a store, a file manager): its banner, nothing else.
 * The screen behind takes the app's colour ([GlowView.wash]); the dock tile under it already names the app
 * and holds the focus, so the banner wears no ring and no label.
 */
class AppBadgeView(context: Context) : View(context) {
    private val c = context
    private var banner: Bitmap? = null
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.FILL }
    // A banner in the screen's own colour still stands off it: a white 20% hairline just inside, and a soft
    // dark edge 4 dp out (a flat round rect, nothing blurred).
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = c.dp(1f)
        color = 0x33FFFFFF
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val hair = RectF()
    private val shade = RectF()
    private val shadeOut = c.dp(4f)
    private val box = RectF()

    init {
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(banner: Bitmap?) {
        this.banner = banner
        layoutBox()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutBox()

    private fun layoutBox() {
        if (width == 0) return
        val bw = min(width * 0.6f, c.dp(BANNER_W))
        val bh = bw * 9f / 16f
        val top = (height - bh) / 2f
        box.set((width - bw) / 2f, top, (width + bw) / 2f, top + bh)
        bannerPaint.shader = banner?.let { b ->
            BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val s = max(box.width() / b.width, box.height() / b.height)
                setLocalMatrix(Matrix().apply {
                    setScale(s, s)
                    postTranslate(box.left + (box.width() - b.width * s) / 2f, box.top + (box.height() - b.height * s) / 2f)
                })
            }
        }
        shade.set(box)
        shade.inset(-shadeOut, -shadeOut)
        val half = hairPaint.strokeWidth / 2f
        hair.set(box)
        hair.inset(half, half)
    }

    override fun onDraw(canvas: Canvas) {
        // The dock tiles' proportion (14 dp on about 107): a big tile, not a different shape.
        val radius = c.dp(28f)
        canvas.drawRoundRect(shade, radius + shadeOut, radius + shadeOut, shadePaint)
        canvas.drawRoundRect(box, radius, radius, if (banner != null) bannerPaint else fillPaint)
        val half = hairPaint.strokeWidth / 2f
        canvas.drawRoundRect(hair, radius - half, radius - half, hairPaint)
    }

    companion object {
        /** The banner's widest (dp), 16:9. */
        const val BANNER_W = 240f
    }
}
