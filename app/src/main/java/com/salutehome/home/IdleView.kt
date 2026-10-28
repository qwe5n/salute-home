package com.salutehome.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextUtils
import android.view.View
import com.salutehome.core.Fonts
import com.salutehome.core.Tokens
import com.salutehome.core.dp
import com.salutehome.core.dpi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Idle mode, as the Apple TV's top shelf: after five minutes untouched the home screen gives way to the
 * shelf's titles one at a time, each picture filling the screen, dimmed, with its name large over the
 * bottom left and the time top right. A new title every [EVERY_MS], crossfading over a second, so no
 * picture sits still long enough to mark the panel. Any key brings the home screen back (the first only wakes).
 */
class IdleView(context: Context) : View(context) {
    private val c = context
    private val picPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val shade = Paint()
    private val titlePaint = c.textPaint(Fonts.semibold(c), 46f, Tokens.TEXT, -0.02f)
    private val metaPaint = c.textPaint(Fonts.medium(c), 17f, 0xFFD6D6D6.toInt())
    private val clockPaint = c.textPaint(Fonts.regular(c), 44f, Tokens.TEXT, -0.02f, tabular = true)
    private val tempPaint = c.textPaint(Fonts.semibold(c), 20f, Tokens.TEXT)
    private val wordsPaint = c.textPaint(Fonts.medium(c), 13f, 0xCCFFFFFF.toInt())
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = c.dp(1f)
        color = 0x29FFFFFF
    }
    private val glyphPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Tokens.TEXT }
    private val card = android.graphics.RectF()
    private val dayFormat = SimpleDateFormat("EEE d", Locale("ru"))

    /** The weather now, for the clock's card (the bento's reading). */
    var weather: WeatherReading? = null
        set(value) {
            field = value
            if (isOn) invalidate()
        }
    private var glyphRes = 0
    private var glyph: Bitmap? = null
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 10_000)
        }
    }
    private val matrix = Matrix()
    private val fade = Tween(0f)
    private val clockFormat = SimpleDateFormat("H:mm", Locale("ru"))

    /** A title to show: its name, the line under it, and its picture at screen size. */
    class Slide(val title: String, val meta: String, val picture: Bitmap?)

    private var slides: List<Slide> = emptyList()
    private var index = 0
    private var previous: Slide? = null

    val isOn: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE
    }

    fun show(list: List<Slide>) {
        if (list.isEmpty()) return
        slides = list
        index = 0
        previous = null
        fade.snap(1f)
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(900).start()
        removeCallbacks(next)
        postDelayed(next, EVERY_MS)
        removeCallbacks(tick)
        postDelayed(tick, 10_000)
        invalidate()
    }

    fun hide() {
        if (!isOn) return
        removeCallbacks(next)
        removeCallbacks(tick)
        animate().cancel()
        visibility = GONE
        slides = emptyList()
        previous = null
    }

    private val next = object : Runnable {
        override fun run() {
            if (slides.size > 1) {
                previous = slides[index]
                index = (index + 1) % slides.size
                fade.snap(0f)
                fade.to(1f, 1000)
            }
            invalidate()
            postDelayed(this, EVERY_MS)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val moving = fade.step(HomeMotion.now())
        canvas.drawColor(0xFF000000.toInt())
        val w = width.toFloat()
        val h = height.toFloat()
        previous?.let { if (fade.value < 1f) drawPicture(canvas, it.picture, 1f) }
        val now = slides.getOrNull(index) ?: return
        drawPicture(canvas, now.picture, fade.value)
        // Dark from the bottom left, where the name sits, and a little from the top for the clock.
        shade.shader = LinearGradient(0f, h, 0f, 0f, intArrayOf(0xF0000000.toInt(), 0x66000000, 0x33000000, 0x8C000000.toInt()), floatArrayOf(0f, 0.45f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, shade)
        val left = c.dp(Tokens.SAFE_SIDE)
        val maxW = w * 0.6f
        metaPaint.alpha = (255 * fade.value).roundToInt()
        titlePaint.alpha = metaPaint.alpha
        val title = TextUtils.ellipsize(now.title, titlePaint, maxW, TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, left, h - c.dp(72f), titlePaint)
        if (now.meta.isNotEmpty()) canvas.drawText(now.meta, left, h - c.dp(44f), metaPaint)
        drawClockCard(canvas, left)
        if (moving) postInvalidateOnAnimation()
    }

    /**
     * Top left, a glass card: the time large, a hairline, then the weather, its glyph and temperature over
     * its words and the day ("Облачно · ПН 26"). Without a forecast it holds the time and the day.
     */
    private fun drawClockCard(canvas: Canvas, left: Float) {
        val now = Date()
        val clock = clockFormat.format(now)
        val day = dayFormat.format(now).replace(".", "").uppercase(Locale("ru"))
        val wr = weather
        val padX = c.dp(18f)
        val h = c.dp(68f)
        val top = c.dp(40f)
        val clockW = clockPaint.measureText(clock)
        val gap = c.dp(16f)
        val glyphSize = c.dpi(20f)
        val tempText = wr?.let { "${it.temp}°" } ?: ""
        val words = if (wr != null) "${wr.text} · $day" else day
        val firstW = if (wr != null) glyphSize + c.dp(6f) + tempPaint.measureText(tempText) else 0f
        val rightW = maxOf(firstW, wordsPaint.measureText(words))
        card.set(left, top, left + padX + clockW + gap + c.dp(1f) + gap + rightW + padX, top + h)
        canvas.drawRoundRect(card, c.dp(20f), c.dp(20f), cardPaint)
        canvas.drawRoundRect(card, c.dp(20f), c.dp(20f), hairPaint)
        var x = left + padX
        canvas.drawText(clock, x, baselineIn(clockPaint, top, h), clockPaint)
        x += clockW + gap
        canvas.drawRect(x, top + c.dp(14f), x + c.dp(1f), top + h - c.dp(14f), hairPaint)
        x += c.dp(1f) + gap
        if (wr != null) {
            val res = Weather.glyph(wr.icon)
            if (res != glyphRes || glyph == null) {
                glyphRes = res
                glyph = Glyphs.mask(c, res, glyphSize)
            }
            val lineTop = top + c.dp(12f)
            glyph?.let { canvas.drawBitmap(it, x, lineTop + c.dp(2f), glyphPaint) }
            canvas.drawText(tempText, x + glyphSize + c.dp(6f), baselineIn(tempPaint, lineTop, c.dp(24f)), tempPaint)
            canvas.drawText(words, x, baselineIn(wordsPaint, top + h - c.dp(30f), c.dp(18f)), wordsPaint)
        } else {
            canvas.drawText(words, x, baselineIn(wordsPaint, top, h), wordsPaint)
        }
    }

    /** [bitmap] filling the screen (cropped as cover), at [opacity]. */
    private fun drawPicture(canvas: Canvas, bitmap: Bitmap?, opacity: Float) {
        val b = bitmap ?: return
        val scale = max(width / b.width.toFloat(), height / b.height.toFloat())
        matrix.setScale(scale, scale)
        matrix.postTranslate((width - b.width * scale) / 2f, (height - b.height * scale) / 2f)
        picPaint.alpha = (255 * opacity).roundToInt()
        canvas.drawBitmap(b, matrix, picPaint)
    }

    companion object {
        /** Idle this long on the home screen, and idle mode comes. */
        const val AFTER_MS = 5 * 60_000L
        const val EVERY_MS = 20_000L
    }
}
