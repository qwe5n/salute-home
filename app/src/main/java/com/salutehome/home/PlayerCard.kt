package com.salutehome.home

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
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import com.salutehome.R
import com.salutehome.core.Fonts
import com.salutehome.core.NowPlaying
import com.salutehome.core.NowPlayingHub
import com.salutehome.core.Tokens
import com.salutehome.core.dp
import com.salutehome.media.PlaybackMath
import com.salutehome.media.NowPlayingControls
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What's playing, as a card on the shelf in place of two posters (ShelfView makes the room): the cover
 * blurred behind it, the cover, the song, its progress and four buttons, previous, play/pause, next and the
 * lyrics screen. Focused, Left and Right walk the buttons and leave the card at either end; OK presses.
 * Hidden while nothing plays.
 */
class PlayerCardView(context: Context) : View(context) {
    private val c = context
    private fun dp(v: Float) = c.dp(v)

    private val labelPaint = c.textPaint(Fonts.semibold(c), 12f, 0xBFFFFFFF.toInt(), 0.06f)
    private val titlePaint = c.textPaint(Fonts.semibold(c), 17f, Color.WHITE, -0.01f)
    private val artistPaint = c.textPaint(Fonts.medium(c), 13f, 0xB3FFFFFF.toInt())
    private val timePaint = c.textPaint(Fonts.medium(c), 12f, 0x99FFFFFF.toInt(), tabular = true)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Tokens.FOCUS_RING }
    private val card = RectF()
    private val box = RectF()

    // Made again only when the cover, the text or the room changes: the card redraws every 500 ms while playing.
    private val nowPlayingLabel = c.getString(R.string.home_now_playing).uppercase()
    private val shaderMatrix = Matrix()
    private var bgShader: BitmapShader? = null
    private var bgShaderOf: Bitmap? = null
    private var coverShader: BitmapShader? = null
    private var coverShaderOf: Bitmap? = null
    private val titleFit = Fit()
    private val artistFit = Fit()
    private val glyphs = intArrayOf(
        R.drawable.ph_skip_back_fill, R.drawable.ph_play_fill, R.drawable.ph_pause_fill,
        R.drawable.ph_skip_forward_fill, R.drawable.ic_lyrics,
    ).map { c.getDrawable(it)!!.mutate() }

    /** Room round the card for the focus ring, as the posters keep. */
    val ringSpace = dp(3f)

    private var song: NowPlaying? = null
    private var art: Bitmap? = null
    private var blurred: Bitmap? = null

    /** 0 previous, 1 play/pause, 2 next, 3 lyrics. */
    private var control = 1
    private var optimistic: Boolean? = null
    private var optimisticUntil = 0L

    /** The lyrics button. */
    var onLyrics: (() -> Unit)? = null

    /** Told when the card comes (something plays) or goes. */
    var onShownChanged: ((Boolean) -> Unit)? = null

    private val listener: (NowPlaying?) -> Unit = { show(it) }
    private val tick = Runnable { invalidate() }

    init {
        isFocusable = true
        defaultFocusHighlightEnabled = false
        visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NowPlayingHub.addListener(listener)
    }

    override fun onDetachedFromWindow() {
        NowPlayingHub.removeListener(listener)
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun show(np: NowPlaying?) {
        song = np
        if (np != null && np.playing == optimistic) optimistic = null
        if (np?.art !== art) {
            art = np?.art
            // A few pixels of the cover, stretched over the card with smoothing: a soft blur for free.
            blurred = art?.let { a -> try { Bitmap.createScaledBitmap(a, 3, 3, true) } catch (e: RuntimeException) { null } }
        }
        contentDescription = np?.let { if (it.artist.isEmpty()) it.title else "${it.title}, ${it.artist}" }
        val shown = np != null
        if (shown != (visibility == VISIBLE)) {
            visibility = if (shown) VISIBLE else GONE
            onShownChanged?.invoke(shown)
        }
        invalidate()
    }

    // ---- Keys -------------------------------------------------------------------------------------------

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) {
            control = when (direction) {
                FOCUS_RIGHT -> 0
                FOCUS_LEFT -> 3
                else -> 1
            }
        }
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (control > 0) { control--; invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (control < 3) { control++; invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!event.isCanceled) press()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun press() {
        when (control) {
            0 -> NowPlayingControls.previous()
            2 -> NowPlayingControls.next()
            3 -> onLyrics?.invoke()
            else -> {
                val playing = playingShown()
                if (playing) NowPlayingControls.pause() else NowPlayingControls.play()
                optimistic = !playing
                optimisticUntil = SystemClock.elapsedRealtime() + 2000
            }
        }
        invalidate()
    }

    private fun playingShown(): Boolean {
        val o = optimistic
        if (o != null && SystemClock.elapsedRealtime() < optimisticUntil) return o
        return song?.playing == true
    }

    // ---- Drawing ----------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val np = song ?: return
        card.set(ringSpace, ringSpace, width - ringSpace, height - ringSpace)
        val r = dp(16f)
        // The cover's colours, blurred and darkened, as Apple Music's player sits on its artwork.
        val b = blurred
        if (b != null) {
            val shader = bgShader?.takeIf { bgShaderOf === b }
                ?: BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { bgShader = it; bgShaderOf = b }
            shaderMatrix.setScale(card.width() / b.width, card.height() / b.height)
            shaderMatrix.postTranslate(card.left, card.top)
            shader.setLocalMatrix(shaderMatrix)
            bgPaint.shader = shader
            canvas.drawRoundRect(card, r, r, bgPaint)
            // Dark enough that white text reads on any cover, a white one included (65%).
            fill.color = 0xA6000000.toInt()
            canvas.drawRoundRect(card, r, r, fill)
        } else {
            fill.color = Tokens.FILL
            canvas.drawRoundRect(card, r, r, fill)
        }
        if (isFocused) {
            // The posters' ring: 2.5 dp, 2.5 dp out.
            val out = dp(2.5f)
            ringPaint.strokeWidth = dp(2.5f)
            box.set(card.left - out, card.top - out, card.right + out, card.bottom + out)
            canvas.drawRoundRect(box, r + out, r + out, ringPaint)
        }

        // The cover, square, on the left. On a squarer card (beside an app's logo) it fills the card's height
        // and 42% of its width, cut to fit; the progress sits on the buttons, which spread across the column.
        val pad = dp(12f)
        val compact = card.width() < card.height() * 2.2f
        val coverW = if (compact) card.width() * 0.42f else card.height() - 2 * pad
        box.set(card.left + pad, card.top + pad, card.left + pad + coverW, card.bottom - pad)
        val a = art
        if (a != null) {
            val shader = coverShader?.takeIf { coverShaderOf === a }
                ?: BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { coverShader = it; coverShaderOf = a }
            val s = max(box.width() / a.width, box.height() / a.height)
            shaderMatrix.setScale(s, s)
            shaderMatrix.postTranslate(box.left + (box.width() - a.width * s) / 2f, box.top + (box.height() - a.height * s) / 2f)
            shader.setLocalMatrix(shaderMatrix)
            coverPaint.shader = shader
            canvas.drawRoundRect(box, dp(10f), dp(10f), coverPaint)
        } else {
            fill.color = 0x26FFFFFF
            canvas.drawRoundRect(box, dp(10f), dp(10f), fill)
        }

        // The song, its progress, the buttons.
        val x = box.right + dp(14f)
        val w = card.right - pad - x
        canvas.drawText(nowPlayingLabel, x, card.top + dp(26f), labelPaint)
        canvas.drawText(titleFit.of(np.title, titlePaint, w), x, card.top + dp(48f), titlePaint)
        canvas.drawText(artistFit.of(np.artist, artistPaint, w), x, card.top + dp(66f), artistPaint)
        val pos = PlaybackMath.position(np.positionMs, np.positionAtMs, np.speed, np.playing, np.durationMs, SystemClock.elapsedRealtime())
        val cy = card.bottom - pad - dp(15f)
        val py = if (compact) cy - dp(46f) else card.top + dp(80f)
        box.set(x, py, x + w, py + dp(3f))
        fill.color = 0x40FFFFFF
        canvas.drawRoundRect(box, dp(1.5f), dp(1.5f), fill)
        val f = PlaybackMath.progress(pos, np.durationMs)
        if (f > 0f) {
            box.right = x + w * f
            fill.color = Color.WHITE
            canvas.drawRoundRect(box, dp(1.5f), dp(1.5f), fill)
        }
        canvas.drawText(PlaybackMath.mmss(pos), x, py + dp(17f), timePaint)
        if (np.durationMs > 0) {
            val left = PlaybackMath.left(pos, np.durationMs)
            canvas.drawText(left, x + w - timePaint.measureText(left), py + dp(17f), timePaint)
        }
        for (i in 0..3) {
            // Three transport buttons from the column's left edge, the lyrics button at its right end; on a
            // compact card all four spread from edge to edge. The glyphs' edges line up with the text's.
            val edge = dp(9f)
            val cx = when {
                compact -> x + edge + (w - 2 * edge) * i / 3f
                i < 3 -> x + edge + i * dp(42f)
                else -> x + w - edge
            }
            val focused = isFocused && control == i
            if (focused) {
                fill.color = Color.WHITE
                canvas.drawCircle(cx, cy, dp(17f), fill)
            }
            val g = glyphs[when (i) { 0 -> 0; 2 -> 3; 3 -> 4; else -> if (playingShown()) 2 else 1 }]
            g.setTint(if (focused) Color.BLACK else Color.WHITE)
            val half = dp(if (i == 1) 11f else 9f)
            g.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
            g.draw(canvas)
        }
        removeCallbacks(tick)
        if (np.playing) postDelayed(tick, 500)
    }

    /** A line cut to its room with an ellipsis, cut again only when the text or the room changes. */
    private class Fit {
        private var text: String? = null
        private var room = -1f
        private var shown = ""

        fun of(s: String, p: android.text.TextPaint, room: Float): String {
            if (s != text || room != this.room) {
                text = s
                this.room = room
                shown = TextUtils.ellipsize(s, p, room, TextUtils.TruncateAt.END).toString()
            }
            return shown
        }
    }
}
