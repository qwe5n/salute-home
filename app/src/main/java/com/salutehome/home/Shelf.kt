package com.salutehome.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.animation.PathInterpolator
import android.view.View
import android.view.ViewGroup
import com.salutehome.R
import com.salutehome.core.Fonts
import com.salutehome.core.Tokens
import com.salutehome.core.dp
import com.salutehome.core.dpi
import kotlin.math.roundToInt

/**
 * A poster on the shelf: the art (radius 16), its title (15, brightening with focus) and the details under
 * it (13, fully opaque with focus). With no art yet the art's place is a dark fill with a film slate,
 * lighter while focused.
 */
class PosterView(context: Context) : ArtView(context) {
    override val focusGrow = 0.04f
    private val titlePaint = context.textPaint(Fonts.medium(context), Tokens.T_BODY, Tokens.TEXT_2, -0.006f)
    private val metaPaint = context.textPaint(Fonts.medium(context), Tokens.T_LABEL, Tokens.TEXT_2)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glyphSize = context.dpi(36f)
    private val glyph: Bitmap = Glyphs.mask(context, R.drawable.ph_film_slate, glyphSize)

    private var title = ""
    private var meta = ""
    private var shownTitle: CharSequence = ""
    private var shownMeta: CharSequence = ""
    private var titleBaseline = 0f
    private var metaBaseline = 0f

    /** The art's height (px); the shelf sets it before measuring, so all of them share one. */
    var artHeight = 0f

    /** How much of it has been watched, 0 to 1; negative draws no bar (a recommendation). */
    var progress = -1f
        set(value) {
            field = value
            invalidate()
        }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }

    fun setText(title: String, meta: String) {
        this.title = title
        this.meta = meta
        contentDescription = listOf(title, meta).filter { it.isNotEmpty() }.joinToString(", ")
        fitText()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val c = context
        placeArt(ringSpace, ringSpace, w - 2 * ringSpace, artHeight, c.dp(16f))
        val titleTop = art.bottom + c.dp(Tokens.S3)
        titleBaseline = baselineIn(titlePaint, titleTop, c.dp(Tokens.T_BODY * 1.3f))
        metaBaseline = baselineIn(metaPaint, titleTop + c.dp(Tokens.T_BODY * 1.3f) + c.dp(2f), c.dp(Tokens.T_LABEL * 1.3f))
        fitText()
    }

    private fun fitText() {
        val width = art.width()
        if (width <= 0) return
        shownTitle = TextUtils.ellipsize(title, titlePaint, width, TextUtils.TruncateAt.END)
        shownMeta = TextUtils.ellipsize(meta, metaPaint, width, TextUtils.TruncateAt.END)
    }

    override fun drawEmptyArt(canvas: Canvas, focused: Float) {
        fillPaint.color = mixColor(Tokens.FILL, Tokens.POSTER_FOCUS, focused)
        canvas.drawRoundRect(art, radius, radius, fillPaint)
        // The film slate at 60%, its colour going from the secondary text grey to the text white.
        glyphPaint.color = mixColor(Tokens.TEXT_2, Tokens.TEXT, focused)
        glyphPaint.alpha = (255 * 0.6f).roundToInt()
        canvas.drawBitmap(glyph, art.centerX() - glyphSize / 2f, art.centerY() - glyphSize / 2f, glyphPaint)
    }

    override fun drawOverArt(canvas: Canvas) {
        if (progress < 0f) return
        val h = context.dp(4f)
        val inset = context.dp(10f)
        val y = art.bottom - inset - h
        val left = art.left + inset
        val right = art.right - inset
        canvas.drawRoundRect(left, y, right, y + h, h / 2, h / 2, trackPaint)
        canvas.drawRoundRect(left, y, left + (right - left) * progress.coerceIn(0f, 1f), y + h, h / 2, h / 2, barPaint)
    }

    override fun drawUnderArt(canvas: Canvas, focused: Float) {
        titlePaint.color = mixColor(Tokens.TEXT_2, Tokens.TEXT, focused)
        canvas.drawText(shownTitle, 0, shownTitle.length, art.left, titleBaseline, titlePaint)
        if (shownMeta.isNotEmpty()) {
            metaPaint.color = Tokens.TEXT_2
            metaPaint.alpha = (255 * (0.75f + 0.25f * focused)).roundToInt()
            canvas.drawText(shownMeta, 0, shownMeta.length, art.left, metaBaseline, metaPaint)
        }
    }

    companion object {
        /** Below the art: 12 to the title's 19.5 line, 2 to the details' 16.9 line. */
        fun textHeight(context: Context): Float =
            context.dp(Tokens.S3 + Tokens.T_BODY * 1.3f + 2f + Tokens.T_LABEL * 1.3f)
    }
}

/**
 * The shelf: its head ("New on your apps" on the left, the data's credit on the right, sharing a
 * baseline) over three posters, one wide and two 124 wide, 16 apart, their art one height so the titles
 * share a line. It fills the shelf slot and reaches 3 dp past it on three sides, so the posters' focus
 * rings stay inside it.
 */
class ShelfView(context: Context) : ViewGroup(context) {
    val posters = List(COUNT) { PosterView(context) }

    /** While something plays, the player takes the place of posters [PLAYER_AT] and the one after it. */
    val player = PlayerCardView(context)

    /** An app with nothing to show here: its logo, beside the player (or alone), in place of the posters. */
    val badge = AppBadgeView(context)

    /** The player's width (px) beside the [badge]: the bento's, so the two line up. */
    var soloPlayerWidth = 0

    var solo = false
        private set

    /** Down from the shelf: the dock tile whose shelf this is (not whatever sits under the focus). */
    var downTo: (() -> View?)? = null

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (direction == FOCUS_DOWN) downTo?.invoke()?.let { return it }
        return super.focusSearch(focused, direction)
    }

    private val headPaint = context.textPaint(Fonts.semibold(context), Tokens.T_LABEL, Tokens.TEXT_2)
    private val creditPaint = context.textPaint(Fonts.medium(context), Tokens.T_CAPTION, Tokens.TEXT_2, 0.012f).apply {
        alpha = (255 * 0.7f).roundToInt()
    }
    private var head = ""
    private var credit = ""
    private var head2 = ""
    private var head2At = -1
    private var creditWidth = 0f
    private var headBaseline = 0f
    private val headAlpha = Tween(1f)
    private val headBase = headPaint.alpha
    private val creditBase = creditPaint.alpha
    // Short slides (24 dp out, 32 in): flicking along the dock, the shelf shifts rather than swings.
    private val slide = context.dp(24f)
    private val slideIn = context.dp(32f)

    /** Out of sight after [leave], until [enter]; [outAt] is when it started going (uptime ms). */
    var isOut = false
        private set
    var outAt = 0L
        private set

    /** The ring's room round the posters: the shelf's own edge sits this far outside the slot. */
    val overhang: Int = posters[0].ringSpace.roundToInt()

    init {
        for (p in posters) addView(p)
        addView(player)
        addView(badge)
        player.onShownChanged = { syncPlayer() }
        setWillNotDraw(false)
        // The badge's glow reaches past the shelf; posters sliding past an edge have faded out by then.
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The head on the left, the credit on the right, and optionally a second head over poster [secondAt]. */
    fun setHead(head: String, credit: String, second: String = "", secondAt: Int = -1) {
        this.head = head
        this.credit = credit
        head2 = second
        head2At = secondAt
        creditWidth = creditPaint.measureText(credit)
        invalidate()
    }

    /**
     * Slides the posters out towards -[dir] (-1 when focus moved left along the dock, +1 right), fading
     * them and the heads, over [OUT_MS]. The player stays where it is.
     */
    fun leave(dir: Int, withPlayer: Boolean = false) {
        if (!isOut) outAt = android.os.SystemClock.uptimeMillis()
        isOut = true
        for (p in movers(withPlayer)) {
            p.animate().alpha(0f).translationX(-dir * slide).setStartDelay(0).setDuration(OUT_MS).setInterpolator(EASE_IN).start()
        }
        headAlpha.to(0f, OUT_MS)
        invalidate()
    }

    /** Brings the (newly filled) posters in from [dir]'s side one after another, and the heads back. */
    fun enter(dir: Int) {
        isOut = false
        var n = 0
        for (p in movers(withPlayer = true)) {
            // The player comes back only if it went (the shelf switching to or from an app's logo).
            if (p === player && p.alpha == 1f && p.translationX == 0f) continue
            p.animate().cancel()
            if (p.visibility != VISIBLE || !HomeMotion.enabled) {
                p.alpha = 1f
                p.translationX = 0f
                continue
            }
            p.alpha = 0f
            p.translationX = dir * slideIn
            p.animate().alpha(1f).translationX(0f).setStartDelay(n++ * STAGGER_MS).setDuration(IN_MS).setInterpolator(Tokens.EASE_OUT).start()
        }
        headAlpha.to(1f, IN_MS)
        invalidate()
    }

    private fun movers(withPlayer: Boolean): List<View> =
        if (withPlayer) posters + badge + player else posters + badge

    /** Swaps the posters for the [badge] (on), or back; the posters come back as the shelf fills them. */
    fun setSolo(on: Boolean) {
        if (on == solo) return
        solo = on
        badge.visibility = if (on) VISIBLE else GONE
        if (on) {
            for (p in posters) p.visibility = GONE
            setHead("", "")
        }
        requestLayout()
    }

    /** What the two posters under the player were before it hid them, to give back; -1 while not hidden. */
    private val underPlayer = intArrayOf(-1, -1)

    /** Hides the two posters under the player while it shows (call after the posters are filled). */
    fun syncPlayer() {
        if (solo) {
            requestLayout()
            return
        }
        val on = player.visibility == VISIBLE
        for (i in PLAYER_AT..PLAYER_AT + 1) {
            val p = posters[i]
            val k = i - PLAYER_AT
            if (on) {
                if (p.hasFocus()) player.post { player.requestFocus() }
                if (p.visibility != GONE) underPlayer[k] = p.visibility
                p.visibility = GONE
            } else if (underPlayer[k] >= 0) {
                // A short row's empty place stays invisible, so it can't take focus.
                if (p.visibility == GONE) p.visibility = underPlayer[k]
                underPlayer[k] = -1
            }
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val g = geometry(context, measuredWidth - 2f * overhang, measuredHeight - overhang.toFloat())
        measured = g
        for ((i, p) in posters.withIndex()) {
            p.artHeight = g.artHeight
            p.measure(
                MeasureSpec.makeMeasureSpec((g.widths[i] + 2 * overhang).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((overhang + g.artHeight + PosterView.textHeight(context)).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
        if (solo) {
            player.measure(
                MeasureSpec.makeMeasureSpec(soloPlayerWidth + 2 * overhang, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
            )
        } else {
            val span = g.lefts[PLAYER_AT + 1] + g.widths[PLAYER_AT + 1] - g.lefts[PLAYER_AT]
            player.measure(
                MeasureSpec.makeMeasureSpec((span + 2 * overhang).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((overhang + g.artHeight + PosterView.textHeight(context)).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
        badge.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth - badgeLeft(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
        )
    }

    /** The badge starts past the player, or at the shelf's left when nothing plays. */
    private fun badgeLeft(): Int =
        if (player.visibility == VISIBLE) soloPlayerWidth + 2 * overhang + context.dp(Tokens.S4).roundToInt() else 0

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val g = geometry(context, width - 2f * overhang, height - overhang.toFloat())
        for ((i, p) in posters.withIndex()) {
            // A poster's bounds start a ring's width before its art, which sits at the slot's own x.
            val x = g.lefts[i].roundToInt()
            val y = g.artTop.roundToInt()
            p.layout(x, y, x + p.measuredWidth, y + p.measuredHeight)
        }
        if (solo) {
            player.layout(0, 0, player.measuredWidth, player.measuredHeight)
        } else {
            val px = g.lefts[PLAYER_AT].roundToInt()
            val py = g.artTop.roundToInt()
            player.layout(px, py, px + player.measuredWidth, py + player.measuredHeight)
        }
        val bx = badgeLeft()
        badge.layout(bx, 0, bx + badge.measuredWidth, badge.measuredHeight)
        headBaseline = baselineIn(headPaint, overhang.toFloat(), context.dp(Tokens.T_LABEL * 1.3f))
    }

    class Geometry(val lefts: FloatArray, val widths: FloatArray, val artTop: Float, val artHeight: Float)

    companion object {
        /**
         * The prototype's grid in a slot [slotWidth] x [slotHeight] px (slot coordinates): the head's
         * 16.9 line and 12 under it, then columns 1fr 124 124 with gaps of 16, the art taking whatever
         * height the titles leave.
         */
        /** Posters on the shelf, all one size. */
        const val COUNT = 5

        /** The shelf changing apps: out, then in, each poster [STAGGER_MS] after the one before. */
        const val OUT_MS = 110L
        const val IN_MS = 240L
        const val STAGGER_MS = 25L
        private val EASE_IN = PathInterpolator(0.4f, 0f, 1f, 1f)

        /** The first of the two posters the player covers: the 2nd and 3rd. */
        const val PLAYER_AT = 1

        fun geometry(c: Context, slotWidth: Float, slotHeight: Float): Geometry {
            val gap = c.dp(Tokens.S4)
            val w = (slotWidth - (COUNT - 1) * gap) / COUNT
            val artTop = c.dp(Tokens.T_LABEL * 1.3f + Tokens.S3)
            val artHeight = slotHeight - artTop - PosterView.textHeight(c)
            return Geometry(
                lefts = FloatArray(COUNT) { it * (w + gap) },
                widths = FloatArray(COUNT) { w },
                artTop = artTop,
                artHeight = artHeight,
            )
        }
    }

    /** The geometry from the last measure, for onDraw. */
    private var measured: Geometry? = null

    override fun onDraw(canvas: Canvas) {
        if (headAlpha.step()) postInvalidateOnAnimation()
        headPaint.alpha = (headBase * headAlpha.value).roundToInt()
        creditPaint.alpha = (creditBase * headAlpha.value).roundToInt()
        val left = overhang.toFloat()
        canvas.drawText(head, left, headBaseline, headPaint)
        // Under the player the second part starts with the first poster after it.
        val at = if (player.visibility == VISIBLE && head2At in PLAYER_AT..PLAYER_AT + 1) PLAYER_AT + 2 else head2At
        if (head2.isNotEmpty() && at in posters.indices) {
            val g = measured ?: geometry(context, width - 2f * overhang, height - overhang.toFloat())
            val x = overhang + g.lefts[at]
            canvas.drawText(head2, x, headBaseline, headPaint)
        }
        if (credit.isNotEmpty()) canvas.drawText(credit, width - overhang - creditWidth, headBaseline, creditPaint)
    }
}
