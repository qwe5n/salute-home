package com.salutehome.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import com.salutehome.R
import com.salutehome.core.Fonts
import com.salutehome.core.NowPlaying
import com.salutehome.core.dp
import com.salutehome.home.textPaint
import com.salutehome.media.PlaybackMath
import com.salutehome.media.NowPlayingControls
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The music screen, as Apple Music's lyrics: the cover blurred into a slowly moving wash of its colours,
 * the cover, song and a small control centre on the left, the words on the right in white, the sung line
 * bright and a touch larger, the rest faint, kept a third of the way down. Left and Right walk
 * previous / play-pause / next, OK presses, Back leaves (MusicMode).
 */
internal class LyricsView(context: Context) : View(context) {
    private val c = context
    private val titlePaint = c.textPaint(Fonts.semibold(c), 22f, Color.WHITE, -0.01f)
    private val artistPaint = c.textPaint(Fonts.medium(c), 17f, 0x99FFFFFF.toInt())
    private val timePaint = c.textPaint(Fonts.medium(c), 12f, 0x99FFFFFF.toInt(), tabular = true)
    private val linePaint = c.textPaint(Fonts.bold(c), 32f, Color.WHITE, -0.015f)
    private val notePaint = c.textPaint(Fonts.semibold(c), 24f, 0x80FFFFFF.toInt())
    private val washPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val matrix = Matrix()
    private val glyphs = intArrayOf(R.drawable.ph_skip_back_fill, R.drawable.ph_play_fill, R.drawable.ph_pause_fill, R.drawable.ph_skip_forward_fill)
        .map { c.getDrawable(it)!!.mutate().apply { setTint(Color.WHITE) } }

    private var song: NowPlaying? = null
    private var art: Bitmap? = null
    private var wash: Bitmap? = null
    private var words: Lyrics.Words? = null
    private var wordsFor = ""
    private var layouts: List<StaticLayout> = emptyList()
    private var layoutWidth = 0
    private var scroll = 0f
    private var lit = FloatArray(0)
    private var frameAt = 0L

    /** 0 previous, 1 play/pause, 2 next. */
    private var control = 1
    private var optimistic: Boolean? = null
    private var optimisticUntil = 0L

    private fun dp(v: Float) = c.dp(v)

    @Suppress("UNUSED_PARAMETER")
    fun show(np: NowPlaying?, cover: Int) {
        song = np
        if (np?.art !== art) {
            art = np?.art
            // The cover at 8 x 8, stretched over the screen with smoothing: a deep blur for almost nothing.
            wash = art?.let { a -> try { Bitmap.createScaledBitmap(a, 8, 8, true) } catch (e: RuntimeException) { null } }
        }
        if (np != null && np.playing == optimistic) optimistic = null
        val key = np?.let { Lyrics.key(it.title, it.artist) } ?: ""
        if (key != wordsFor) {
            wordsFor = key
            words = null
            layouts = emptyList()
            scroll = 0f
            if (np != null) {
                Lyrics.cached(np.title, np.artist)?.let { setWords(it) } ?: Lyrics.find(np.title, np.artist, np.durationMs) {
                    if (wordsFor == key) setWords(it)
                }
            }
        }
        invalidate()
    }

    private fun setWords(w: Lyrics.Words) {
        words = w
        layouts = emptyList()
        invalidate()
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> control = max(0, control - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> control = min(2, control + 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (event.repeatCount == 0) press()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { control = 1; press() }
            KeyEvent.KEYCODE_MEDIA_NEXT -> NowPlayingControls.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> NowPlayingControls.previous()
        }
        invalidate()
        return true
    }

    private fun press() {
        when (control) {
            0 -> NowPlayingControls.previous()
            2 -> NowPlayingControls.next()
            else -> {
                val playing = playingShown()
                if (playing) NowPlayingControls.pause() else NowPlayingControls.play()
                optimistic = !playing
                optimisticUntil = SystemClock.elapsedRealtime() + 2000
            }
        }
    }

    private fun playingShown(): Boolean {
        val o = optimistic
        if (o != null && SystemClock.elapsedRealtime() < optimisticUntil) return o
        return song?.playing == true
    }

    override fun onDraw(canvas: Canvas) {
        // The wash drifts and the words glide: every frame while it's on screen.
        if (isShown) postInvalidateOnAnimation()
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val dt = if (frameAt == 0L) 1000L else min(200L, now - frameAt)
        frameAt = now
        drawWash(canvas, w, h, now)
        val np = song ?: return
        val pos = PlaybackMath.position(np.positionMs, np.positionAtMs, np.speed, np.playing, np.durationMs, SystemClock.elapsedRealtime())

        // ---- Left: the cover, the song, the control centre ----
        val left = dp(72f)
        val coverSize = dp(240f)
        val coverTop = dp(84f)
        box.set(left, coverTop, left + coverSize, coverTop + coverSize)
        shadowPaint.color = Color.BLACK
        shadowPaint.setShadowLayer(dp(24f), 0f, dp(10f), 0x66000000)
        canvas.drawRoundRect(box, dp(10f), dp(10f), shadowPaint)
        val a = art
        if (a != null) {
            artPaint.shader = BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    val s = max(coverSize / a.width, coverSize / a.height)
                    setScale(s, s)
                    postTranslate(left, coverTop)
                })
            }
            canvas.drawRoundRect(box, dp(10f), dp(10f), artPaint)
        } else {
            fillPaint.color = 0x33FFFFFF
            canvas.drawRoundRect(box, dp(10f), dp(10f), fillPaint)
        }
        var y = coverTop + coverSize + dp(38f)
        canvas.drawText(ellipsize(np.title, titlePaint, coverSize), left, y, titlePaint)
        y += dp(24f)
        canvas.drawText(ellipsize(np.artist, artistPaint, coverSize), left, y, artistPaint)
        // Progress: a thin line, the times under its ends.
        y += dp(24f)
        box.set(left, y, left + coverSize, y + dp(3f))
        fillPaint.color = 0x40FFFFFF
        canvas.drawRoundRect(box, dp(1.5f), dp(1.5f), fillPaint)
        val f = PlaybackMath.progress(pos, np.durationMs)
        if (f > 0f) {
            box.right = left + coverSize * f
            fillPaint.color = 0xE6FFFFFF.toInt()
            canvas.drawRoundRect(box, dp(1.5f), dp(1.5f), fillPaint)
        }
        y += dp(19f)
        canvas.drawText(PlaybackMath.mmss(pos), left, y, timePaint)
        if (np.durationMs > 0) {
            val rest = PlaybackMath.left(pos, np.durationMs)
            canvas.drawText(rest, left + coverSize - timePaint.measureText(rest), y, timePaint)
        }
        // Previous, play/pause, next: white glyphs, the chosen one on a soft light disc.
        val cy = y + dp(40f)
        val mid = left + coverSize / 2f
        for (i in 0..2) {
            val cx = mid + (i - 1) * dp(72f)
            if (i == control) {
                fillPaint.color = 0x33FFFFFF
                canvas.drawCircle(cx, cy, dp(if (i == 1) 28f else 24f), fillPaint)
            }
            val g = glyphs[when (i) { 0 -> 0; 2 -> 3; else -> if (playingShown()) 2 else 1 }]
            val half = dp(if (i == 1) 17f else 13f)
            g.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
            g.draw(canvas)
        }

        // ---- Right: the words ----
        val lx = dp(392f)
        val lw = (w - lx - dp(72f)).roundToInt()
        val ww = words
        if (ww == null || ww.lines.isEmpty()) {
            val note = when {
                ww == null -> "Ищем текст…"
                ww.instrumental -> "Инструментал"
                else -> "Текст не найден"
            }
            canvas.drawText(note, lx, h / 2f, notePaint)
            return
        }
        if (layouts.isEmpty() || layoutWidth != lw) {
            layoutWidth = lw
            layouts = ww.lines.map {
                val t = it.text.ifEmpty { "♪" }
                StaticLayout.Builder.obtain(t, 0, t.length, linePaint, lw)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.04f).build()
            }
            lit = FloatArray(layouts.size)
        }
        val gap = dp(22f)
        val current = if (ww.synced) {
            var i = -1
            for (k in ww.lines.indices) if (ww.lines[k].atMs <= pos + 300) i = k else break
            i
        } else if (np.durationMs > 0) {
            (ww.lines.size * pos / np.durationMs).toInt().coerceIn(0, ww.lines.size - 1)
        } else 0
        var offset = 0f
        for (k in 0 until max(0, current)) offset += layouts[k].height + gap
        val target = offset - h / 3f + (if (current >= 0) layouts[current].height / 2f else 0f)
        // Apple's soft spring: the words glide to the sung line, and each line's light eases in and out.
        val k = min(1f, dt / 260f)
        scroll += (target - scroll) * k
        var ly = -scroll
        canvas.save()
        canvas.clipRect(lx - dp(24f), 0f, w, h)
        for (i in layouts.indices) {
            val l = layouts[i]
            val goal = if (!ww.synced || i == current) 1f else 0f
            lit[i] += (goal - lit[i]) * min(1f, dt / 180f)
            if (ly + l.height > -dp(40f) && ly < h + dp(40f)) {
                // Faint further from the sung line, the sung one white and 4% larger from its left edge.
                val far = if (current < 0) 3 else abs(i - current)
                val base = when {
                    !ww.synced -> 0.85f
                    far <= 1 -> 0.34f
                    far == 2 -> 0.26f
                    else -> 0.18f
                }
                val alpha = base + (1f - base) * lit[i]
                linePaint.alpha = (255 * alpha).roundToInt()
                val s = 1f + 0.04f * lit[i]
                canvas.save()
                canvas.translate(lx, ly)
                if (s != 1f) canvas.scale(s, s, 0f, l.height / 2f)
                l.draw(canvas)
                canvas.restore()
            }
            ly += l.height + gap
        }
        canvas.restore()
    }

    /** The cover's colours, blurred and slowly drifting round, darkened so white reads on any cover. */
    private fun drawWash(canvas: Canvas, w: Float, h: Float, now: Long) {
        canvas.drawColor(0xFF101012.toInt())
        val b = wash ?: return
        val t = now / 1000.0
        for (layer in 0..1) {
            val sign = if (layer == 0) 1 else -1
            val angle = (sign * (t * 3.0 % 360)).toFloat() + layer * 90f
            val scale = 1.9f + 0.15f * sin(t / 7.0 + layer).toFloat()
            matrix.reset()
            matrix.setScale(w / b.width, w / b.width)
            matrix.postTranslate(-w / 2f, -w / 2f)
            matrix.postScale(scale, scale)
            matrix.postRotate(angle)
            matrix.postTranslate(w / 2f + sign * w * 0.08f * sin(t / 11.0).toFloat(), h / 2f)
            washPaint.alpha = if (layer == 0) 255 else 140
            canvas.drawBitmap(b, matrix, washPaint)
        }
        canvas.drawColor(0x8C000000.toInt())
    }

    private fun ellipsize(s: String, p: android.text.TextPaint, room: Float) =
        TextUtils.ellipsize(s, p, room, TextUtils.TruncateAt.END).toString()
}
