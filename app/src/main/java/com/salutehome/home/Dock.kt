package com.salutehome.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import com.salutehome.core.Tokens
import com.salutehome.core.dp
import kotlin.math.roundToInt

/**
 * An app in the dock: its cover (radius 14, cropped to fill the tile) with a white 10% hairline just inside
 * the edge, so black covers keep their shape on the dark tray. Focus is the art's fine ring, nothing more.
 * Made in code for its app, never inflated, so it has no layout-tool constructors.
 */
@SuppressLint("ViewConstructor")
class AppTileView(context: Context, val app: DockApp) : ArtView(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.FILL }
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = 0x1AFFFFFF
    }
    private val hair = RectF()
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }

    // The focused tile grows 4% and wears the posters' light ring (its colour is the glow's); the others
    // sit at half brightness, so a white tile beside it never outshines it.
    override val ringSpace = context.dp(8f)
    override val focusGrow = 0.04f

    init {
        contentDescription = app.label
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        placeArt(ringSpace, ringSpace, w - 2 * ringSpace, h - 2 * ringSpace, context.dp(14f))
        val half = context.dp(0.5f)
        hair.set(art)
        hair.inset(half, half)
    }

    override fun drawEmptyArt(canvas: Canvas, focused: Float) {
        canvas.drawRoundRect(art, radius, radius, fillPaint)
    }

    override fun drawOverArt(canvas: Canvas) {
        val r = radius - context.dp(0.5f)
        canvas.drawRoundRect(hair, r, r, hairPaint)
        val dim = 0.5f * (1f - focus.value)
        if (dim > 0.01f) {
            dimPaint.alpha = (255 * dim).roundToInt()
            canvas.drawRoundRect(art, radius, radius, dimPaint)
        }
    }
}

/**
 * The app dock (app.css .dock): a tray of white at 7.5% (so the glow tints it), radius 26, 12 of padding,
 * holding up to seven apps ([DockApps.DOCK_SIZE]). Every tile is the size of one of the prototype's seven,
 * and the tray hugs however many there are, centred, so a dock of three is a short tray in the middle. It
 * never scrolls: what doesn't fit lives in the app library.
 *
 * Left and Right walk the row and stop at its ends; Down opens the app library ([onDown]). While a tile is
 * being moved ([startMove]) it sits raised, Left and Right carry it along the row, and OK or Back put it down.
 */
class DockView(context: Context) : ViewGroup(context) {
    var tiles: List<AppTileView> = emptyList()
        private set

    private val pad = context.dp(Tokens.S3)
    private val gap = context.dp(Tokens.S3)
    private val trayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.BAND }
    private val tray = RectF()
    private val trayRadius = context.dp(26f)

    /** What OK on a tile does: open its app. */
    var onOpen: ((DockApp) -> Unit)? = null

    /** Down on a tile (given): the app library. */
    var onDown: ((View) -> Unit)? = null

    init {
        setWillNotDraw(false)
        // A moving tile rises out of the tray.
        clipChildren = false
        // Each tile reports its own focus; the tray itself never takes it.
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    /** Holding OK on a tile: its menu. */
    var onMenu: ((AppTileView) -> Unit)? = null

    /** A moving tile took its new place (its name follows it). */
    var onShifted: ((AppTileView) -> Unit)? = null

    /** A moved tile was put down: the dock's packages in their new order. */
    var onMoved: ((List<String>) -> Unit)? = null

    private var moving: AppTileView? = null
    private val lift = context.dp(10f)

    /** Raises [tile] so Left and Right move it; OK or Back ([endMove]) put it down. */
    fun startMove(tile: AppTileView) {
        if (tile !in tiles) return
        moving = tile
        tile.animate().translationY(-lift).setDuration(160).start()
        tile.requestFocus()
    }

    /** Puts the moving tile down and saves the order; false if no tile was moving. */
    fun endMove(): Boolean {
        val tile = moving ?: return false
        moving = null
        tile.animate().translationY(0f).setDuration(160).start()
        onMoved?.invoke(tiles.map { it.app.packageName })
        return true
    }

    /** Swaps the moving tile with its neighbour [by] (-1 left, +1 right), both sliding to their places. */
    private fun shift(tile: AppTileView, by: Int) {
        val i = tiles.indexOf(tile)
        val j = i + by
        if (j !in tiles.indices) return
        val other = tiles[j]
        tiles = tiles.toMutableList().apply { this[i] = other; this[j] = tile }
        val step = tileWidth(width) + gap
        tile.translationX = -by * step
        other.translationX = by * step
        tile.animate().translationX(0f).setDuration(200).start()
        other.animate().translationX(0f).setDuration(200).start()
        requestLayout()
        postDelayed({ onShifted?.invoke(tile) }, 220)
    }

    /** Shows [apps] in order, keeping focus on the same app if it's still here, else on its neighbour. */
    fun setApps(apps: List<DockApp>) {
        if (apps == tiles.map { it.app }) return
        moving?.let { it.animate().cancel(); it.translationY = 0f }
        moving = null
        val focusedIndex = tiles.indexOfFirst { it.isFocused }
        val focusedApp = tiles.getOrNull(focusedIndex)?.app?.packageName
        android.util.Log.i("SaluteHome", "dock: ${apps.size} apps, focus was ${focusedApp ?: "elsewhere"}")
        val old = tiles.associateBy { it.app.packageName }
        removeAllViews()
        tiles = apps.map { app -> old[app.packageName]?.takeIf { it.app == app } ?: AppTileView(context, app) }
        for (t in tiles) {
            t.onPress = { if (moving === t) endMove() else onOpen?.invoke(t.app) }
            t.onLongPress = { onMenu?.invoke(t) }
            addView(t)
        }
        if (focusedApp != null) {
            (tiles.firstOrNull { it.app.packageName == focusedApp } ?: tiles.getOrNull(focusedIndex.coerceAtMost(tiles.size - 1)))
                ?.requestFocus()
        }
    }

    /** Where [tile]'s centre sits (px from the dock's left edge). */
    fun restingCentre(tile: AppTileView): Float = tile.left + tile.width / 2f

    fun tileFor(packageName: String): AppTileView? = tiles.firstOrNull { it.app.packageName == packageName }

    /** A tile's width (px) in a slot [slotWidth] wide: one of the prototype's seven columns, however many apps. */
    private fun tileWidth(slotWidth: Int): Float =
        (slotWidth - 2 * pad - (DockApps.DOCK_SIZE - 1) * gap) / DockApps.DOCK_SIZE

    /** A tile's height (px): 16:9 of its width. */
    private fun tileHeight(slotWidth: Int): Float = tileWidth(slotWidth) * 9f / 16f

    /** The size (px) a cover is decoded at in a slot [trayWidth] wide; the same for any [count]. */
    @Suppress("UNUSED_PARAMETER")
    fun tileSize(trayWidth: Int, count: Int): Pair<Int, Int> =
        tileWidth(trayWidth).roundToInt() to tileHeight(trayWidth).roundToInt()

    /** The tray's width (px) around [count] tiles. */
    private fun trayWidth(slotWidth: Int, count: Int): Float =
        2 * pad + count * tileWidth(slotWidth) + (count - 1).coerceAtLeast(0) * gap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
        val ring = 2 * (tiles.firstOrNull()?.ringSpace ?: 0f)
        for (t in tiles) {
            t.measure(
                MeasureSpec.makeMeasureSpec((tileWidth(w) + ring).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((tileHeight(w) + ring).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val tileW = tileWidth(width)
        val trayW = trayWidth(width, tiles.size)
        val x0 = (width - trayW) / 2f
        for ((i, tile) in tiles.withIndex()) {
            val x = (x0 + pad + i * (tileW + gap) - tile.ringSpace).roundToInt()
            val y = (pad - tile.ringSpace).roundToInt()
            tile.layout(x, y, x + tile.measuredWidth, y + tile.measuredHeight)
        }
        tray.set(x0, 0f, x0 + trayW, height.toFloat())
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val i = tiles.indexOf(focused)
        if (i >= 0 && focused === moving) {
            when (direction) {
                FOCUS_LEFT -> shift(moving!!, -1)
                FOCUS_RIGHT -> shift(moving!!, +1)
            }
            return focused
        }
        if (i >= 0) {
            when (direction) {
                FOCUS_LEFT -> return tiles.getOrNull(i - 1) ?: focused
                FOCUS_RIGHT -> return tiles.getOrNull(i + 1) ?: focused
                FOCUS_DOWN -> {
                    onDown?.invoke(focused!!)
                    return focused
                }
            }
        }
        return super.focusSearch(focused, direction)
    }

    override fun onDraw(canvas: Canvas) {
        if (tiles.isNotEmpty()) canvas.drawRoundRect(tray, trayRadius, trayRadius, trayPaint)
    }
}
