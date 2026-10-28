package com.salutehome.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import com.salutehome.R
import com.salutehome.core.Fonts
import com.salutehome.core.Tokens
import com.salutehome.core.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The app library ("Все приложения"), opened by Down in the dock: every app on the TV in a grid of six
 * columns over a near-black scrim, each tile with its name under it. It slides up from the bottom edge and
 * back down. Focus stays inside while it's open; OK opens the app, Back closes it and gives focus back to
 * the dock tile it came from.
 */
class AllAppsView(context: Context) : ViewGroup(context) {
    private val c = context
    private val scrim = Paint().apply { color = 0xF20A0808.toInt() }
    private val titlePaint = c.textPaint(Fonts.semibold(c), 24f, Tokens.TEXT, -0.01f)
    private val namePaint = c.textPaint(Fonts.medium(c), 13f, Tokens.TEXT_2)
    private var tiles: List<AppTileView> = emptyList()
    private var back: View? = null

    /** Told once it has closed (the dock's tab sinks back). */
    var onClosed: (() -> Unit)? = null

    /** Opens the app ([DockApp]) when its tile is pressed. */
    var onOpen: ((DockApp) -> Unit)? = null

    /** The cover for an app at a size (px), from the home screen's cache; null until it's decoded. */
    var coverFor: ((DockApp, Int, Int, (Bitmap?) -> Unit) -> Unit)? = null

    val isOpen: Boolean get() = visibility == VISIBLE

    init {
        setWillNotDraw(false)
        visibility = GONE
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    /** Holding OK on a tile: its menu. */
    var onMenu: ((AppTileView) -> Unit)? = null

    fun open(apps: List<DockApp>, returnTo: View?) {
        back = returnTo
        removeAllViews()
        tiles = apps.map { app ->
            AppTileView(c, app).also { t ->
                t.onPress = {
                    // Open first, so the app grows out of this tile, then let the library go.
                    onOpen?.invoke(app)
                    close()
                }
                t.onLongPress = { onMenu?.invoke(t) }
                t.setOnFocusChangeListener { _, _ -> invalidate() }
                addView(t)
            }
        }
        visibility = VISIBLE
        animate().cancel()
        alpha = 0f
        translationY = (parent as View).height.toFloat()
        animate().alpha(1f).translationY(0f).setDuration(380)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).withEndAction(null).start()
        requestLayout()
        post {
            val (w, h) = tileSize()
            for (t in tiles) coverFor?.invoke(t.app, w, h) { bmp -> t.setArt(bmp) }
            tiles.firstOrNull()?.requestFocus()
        }
    }

    fun close() {
        if (!isOpen || closing) return
        closing = true
        back?.requestFocus()
        animate().cancel()
        animate().alpha(0f).translationY(height.toFloat()).setDuration(260)
            .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                closing = false
                visibility = GONE
                removeAllViews()
                tiles = emptyList()
                onClosed?.invoke()
            }.start()
    }

    private var closing = false

    fun onBack(): Boolean {
        if (!isOpen) return false
        close()
        return true
    }

    private val cols = 6
    private val left get() = c.dp(Tokens.SAFE_SIDE)
    private val top get() = c.dp(92f)
    private val gapX get() = c.dp(Tokens.S4)
    private val rowH get() = tileSize().second + c.dp(34f)

    // From the width it's measured at, not the last laid-out one: opening from hidden, that is still 0,
    // and the tiles came out a negative size, which measures as huge (the library looked broken).
    private fun tileSize(total: Int = width): Pair<Int, Int> {
        val w = max(1f, (total - 2 * left - (cols - 1) * gapX) / cols)
        return w.roundToInt() to (w * 9f / 16f).roundToInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val (w, h) = tileSize(measuredWidth)
        for (t in tiles) {
            val ring = 2 * t.ringSpace
            t.measure(MeasureSpec.makeMeasureSpec((w + ring).roundToInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((h + ring).roundToInt(), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val (w, _) = tileSize()
        for ((i, tile) in tiles.withIndex()) {
            val x = (left + (i % cols) * (w + gapX) - tile.ringSpace).roundToInt()
            val y = (top + (i / cols) * rowH - tile.ringSpace).roundToInt()
            tile.layout(x, y, x + tile.measuredWidth, y + tile.measuredHeight)
        }
    }

    // Up from the top row closes the library, like Back, and focus goes back to the dock tile.
    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = FocusFinder.getInstance().findNextFocus(this, focused, direction)
        if (next == null && direction == FOCUS_UP && back != null) {
            close()
            return back
        }
        return next ?: focused
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        canvas.drawText(c.getString(R.string.home_all_apps), left, c.dp(64f), titlePaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val (w, h) = tileSize()
        for ((i, tile) in tiles.withIndex()) {
            val x = left + (i % cols) * (w + gapX)
            val y = top + (i / cols) * rowH + h + c.dp(22f)
            namePaint.color = if (tile.isFocused) Tokens.TEXT else Tokens.TEXT_2
            val name = TextUtils.ellipsize(tile.app.label, namePaint, w.toFloat(), TextUtils.TruncateAt.END)
            canvas.drawText(name, 0, name.length, x + (w - namePaint.measureText(name, 0, name.length)) / 2f, y, namePaint)
        }
    }
}
