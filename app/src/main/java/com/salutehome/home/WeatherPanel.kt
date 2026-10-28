package com.salutehome.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.KeyEvent
import android.view.View
import com.salutehome.core.Fonts
import com.salutehome.core.Tokens
import com.salutehome.core.dp
import com.salutehome.core.dpi
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The weather in full, opened from the bento: a card over a dark scrim with the place, the temperature now
 * (large), its words and what it feels like, humidity and wind; the next twelve hours; the week ahead with
 * each day's high and low on one shared scale. One view, drawn when it opens; OK or Back closes it.
 */
class WeatherPanel(context: Context) : View(context) {
    private val c = context
    private val ru = Locale("ru")
    private val scrim = Paint().apply { color = 0xE6000000.toInt() }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = c.dp(1f)
        color = 0x1CFFFFFF
    }
    private val placePaint = c.textPaint(Fonts.medium(c), 15f, Tokens.TEXT_2)
    private val bigPaint = c.textPaint(Fonts.regular(c), 72f, Tokens.TEXT, -0.03f, tabular = true)
    private val wordsPaint = c.textPaint(Fonts.semibold(c), 20f, Tokens.TEXT)
    private val detailPaint = c.textPaint(Fonts.medium(c), 14f, Tokens.TEXT_2)
    private val headPaint = c.textPaint(Fonts.semibold(c), 13f, Tokens.TEXT_2)
    private val smallPaint = c.textPaint(Fonts.medium(c), 13f, Tokens.TEXT_2, tabular = true)
    private val tempPaint = c.textPaint(Fonts.semibold(c), 16f, Tokens.TEXT, tabular = true)
    private val lowPaint = c.textPaint(Fonts.medium(c), 15f, Tokens.TEXT_2, tabular = true)
    private val glyphPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Tokens.TEXT }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0FFFFFFF }
    private val card = RectF()

    private var reading: WeatherReading? = null
    private var hours: List<WeatherHour> = emptyList()
    private var week: List<Forecast.Day> = emptyList()
    private var feels: Int? = null
    private var humidity: Int? = null
    private var wind: Int? = null
    private var back: View? = null

    val isOpen: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE
        isFocusable = true
        defaultFocusHighlightEnabled = false
    }

    /** Shows [forecast] as it reads at [hourKey] ("2026-10-20T15"); focus goes back to [returnTo] on close. */
    fun open(forecast: Forecast?, hourKey: String, returnTo: View?) {
        back = returnTo
        reading = forecast?.readingAt(hourKey)
        hours = forecast?.hoursAfter(hourKey, 12).orEmpty()
        week = forecast?.week().orEmpty()
        feels = forecast?.feelsLike
        humidity = forecast?.humidity
        wind = forecast?.wind
        val (top, bottom, _) = SkyTileView.SKY_COLORS.getValue(Weather.sky(reading))
        cardPaint.shader = null
        cardTop = top
        cardBottom = bottom
        closing = false
        visibility = VISIBLE
        requestFocus()
        invalidate()
        // In as the app library comes: fading up from 96%.
        animate().cancel()
        alpha = 0f
        scaleX = 0.96f
        scaleY = 0.96f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    }

    private var closing = false

    private var cardTop = 0
    private var cardBottom = 0

    fun close() {
        if (!isOpen || closing) return
        closing = true
        back?.requestFocus()
        animate().cancel()
        animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(200)
            .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                closing = false
                visibility = GONE
            }.start()
    }

    fun onBack(): Boolean {
        if (!isOpen) return false
        close()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
        // Nothing to move between in here: arrows stay put rather than reach the home screen behind.
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            close()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val w = c.dp(780f)
        // The content is CONTENT tall; the card adds the same padding above and below it.
        val h = c.dp(CONTENT + 2 * PAD)
        card.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        // The sky's own colours wash the top of the card and fade into the dark.
        cardPaint.shader = LinearGradient(0f, card.top, 0f, card.bottom, intArrayOf(darken(cardTop), darken(cardBottom), 0xFF141212.toInt()), floatArrayOf(0f, 0.35f, 0.8f), Shader.TileMode.CLAMP)
        val r = c.dp(28f)
        canvas.drawRoundRect(card, r, r, cardPaint)
        canvas.drawRoundRect(card, r, r, hairPaint)

        val pad = c.dp(PAD)
        val left = card.left + pad
        val top = card.top + pad
        val now = reading
        if (now == null) {
            canvas.drawText(Weather.PLACE, left, top + c.dp(12f), placePaint)
            canvas.drawText("Нет данных о погоде", left, top + c.dp(44f), wordsPaint)
            return
        }
        // The header: the temperature large on the left, its top level with the padding; beside it the
        // glyph, then a column of the place (small), the words and the details.
        val temp = "${now.temp}°"
        canvas.drawText(temp, left - c.dp(4f), top + c.dp(52f), bigPaint)
        val gx = left + bigPaint.measureText(temp) + c.dp(14f)
        val g = Glyphs.mask(c, Weather.glyph(now.icon), c.dpi(34f))
        canvas.drawBitmap(g, gx, top + c.dp(14f), glyphPaint)
        val tx = gx + c.dp(48f)
        canvas.drawText(Weather.PLACE, tx, top + c.dp(12f), placePaint)
        canvas.drawText(now.text, tx, top + c.dp(36f), wordsPaint)
        val details = listOfNotNull(
            feels?.let { "Ощущается как $it°" },
            "Макс. ${now.high}° · мин. ${now.low}°",
            humidity?.let { "Влажность $it%" },
            wind?.let { "Ветер ${Math.round(it / 3.6f)} м/с" },
        ).joinToString("  ·  ")
        canvas.drawText(details, tx, top + c.dp(58f), detailPaint)

        // The next twelve hours.
        var y = top + c.dp(96f)
        canvas.drawText("Ближайшие часы", left, y, headPaint)
        y += c.dp(12f)
        val inner = card.width() - 2 * pad
        if (hours.isNotEmpty()) drawChart(canvas, left, y, inner, c.dp(118f))

        // The week, each day's range on one shared scale; its cells end CONTENT below the top.
        y = top + c.dp(260f)
        canvas.drawText("На неделю", left, y, headPaint)
        y += c.dp(12f)
        if (week.isEmpty()) return
        val lo = week.minOf { it.low }
        val hi = week.maxOf { it.high }.coerceAtLeast(lo + 1)
        val gap = c.dp(8f)
        val dw = (inner - gap * (week.size - 1)) / week.size
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val wd = SimpleDateFormat("EE", ru)
        for ((i, d) in week.withIndex()) {
            val x = left + i * (dw + gap)
            val cell = RectF(x, y, x + dw, y + c.dp(108f))
            canvas.drawRoundRect(cell, c.dp(16f), c.dp(16f), cellPaint)
            val name = if (i == 0) "Сегодня" else try {
                wd.format(day.parse(d.date)!!).trimEnd('.').replaceFirstChar { it.uppercase(ru) }
            } catch (e: Exception) {
                d.date.takeLast(2)
            }
            centre(canvas, name, cell.centerX(), cell.top + c.dp(20f), smallPaint)
            val gs = c.dpi(22f)
            canvas.drawBitmap(Glyphs.mask(c, Weather.glyph(d.icon), gs), cell.centerX() - gs / 2f, cell.top + c.dp(28f), glyphPaint)
            centre(canvas, "${d.high}°", cell.centerX() - c.dp(14f), cell.top + c.dp(72f), tempPaint)
            centre(canvas, "${d.low}°", cell.centerX() + c.dp(16f), cell.top + c.dp(72f), lowPaint)
            // The range bar: where this day's low and high sit in the week's.
            val bx = cell.left + c.dp(12f)
            val bw = cell.width() - c.dp(24f)
            val by = cell.top + c.dp(88f)
            val bh = c.dp(4f)
            canvas.drawRoundRect(bx, by, bx + bw, by + bh, bh / 2, bh / 2, trackPaint)
            val a = bx + bw * (d.low - lo) / (hi - lo).toFloat()
            val b = bx + bw * (d.high - lo) / (hi - lo).toFloat()
            barPaint.shader = LinearGradient(bx, 0f, bx + bw, 0f, 0xFF6FB6FF.toInt(), 0xFFFFB35C.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(a, by, maxOf(b, a + bh), by + bh, bh / 2, bh / 2, barPaint)
        }
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = c.dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.TEXT }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14FFFFFF
        strokeWidth = c.dp(1f)
    }

    /**
     * The next hours as a temperature curve in a box [width] x [height] from ([left], [top]): degrees over
     * each point, the curve (warm where it's warmer) over a soft fill, then each hour's glyph and time below.
     */
    private fun drawChart(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        val n = hours.size
        val cw = width / n
        val lo = hours.minOf { it.temp }
        val hi = hours.maxOf { it.temp }
        val span = (hi - lo).coerceAtLeast(3)
        // The curve lives in the upper part; a flat day sits in the middle of it rather than on its floor.
        val curveTop = top + c.dp(30f)
        val curveBottom = top + c.dp(62f)
        val mid = (lo + hi) / 2f
        fun yOf(t: Int) = (curveTop + curveBottom) / 2f - (t - mid) / span * (curveBottom - curveTop)
        val xs = FloatArray(n) { left + cw * it + cw / 2f }
        val ys = FloatArray(n) { yOf(hours[it].temp) }

        val path = android.graphics.Path()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until n) {
            val cx = (xs[i - 1] + xs[i]) / 2f
            path.cubicTo(cx, ys[i - 1], cx, ys[i], xs[i], ys[i])
        }
        val area = android.graphics.Path(path)
        area.lineTo(xs[n - 1], curveBottom + c.dp(14f))
        area.lineTo(xs[0], curveBottom + c.dp(14f))
        area.close()
        areaPaint.shader = LinearGradient(0f, curveTop, 0f, curveBottom + c.dp(14f), 0x40FFB35C, 0x00FFB35C, Shader.TileMode.CLAMP)
        canvas.drawPath(area, areaPaint)
        linePaint.shader = LinearGradient(0f, curveTop, 0f, curveBottom, 0xFFFFB35C.toInt(), 0xFF6FB6FF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawPath(path, linePaint)

        val glyphSize = c.dpi(18f)
        for (i in 0 until n) {
            canvas.drawLine(xs[i], ys[i] + c.dp(6f), xs[i], curveBottom + c.dp(14f), gridPaint)
            canvas.drawCircle(xs[i], ys[i], c.dp(3.5f), dotPaint)
            centre(canvas, "${hours[i].temp}°", xs[i], ys[i] - c.dp(10f), tempPaint)
            canvas.drawBitmap(Glyphs.mask(c, Weather.glyph(hours[i].icon), glyphSize), xs[i] - glyphSize / 2f, curveBottom + c.dp(22f), glyphPaint)
            centre(canvas, hours[i].label, xs[i], curveBottom + c.dp(56f), smallPaint)
        }
    }

    private fun centre(canvas: Canvas, text: String, cx: Float, baseline: Float, paint: Paint) {
        canvas.drawText(text, cx - paint.measureText(text) / 2f, baseline, paint)
    }

    /** The sky colour at half strength over black, so white text stays readable on it. */
    private fun darken(color: Int): Int = mixColor(color, 0xFF000000.toInt(), 0.55f)

    private companion object {
        /** The card's padding, the same on every side (dp). */
        const val PAD = 32f

        /** The content's height inside it: header, the hours' chart, the week's cells (dp). */
        const val CONTENT = 380f
    }
}
