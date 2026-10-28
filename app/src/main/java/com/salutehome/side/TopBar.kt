package com.salutehome.side

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import com.salutehome.media.NowPlayingListener
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.salutehome.R
import com.salutehome.core.Fonts
import com.salutehome.core.HomeHost
import com.salutehome.core.Tokens
import com.salutehome.core.dp
import com.salutehome.core.dpi
import com.salutehome.home.Glyphs
import com.salutehome.home.HomeMotion
import com.salutehome.home.Tween
import com.salutehome.home.baselineIn
import com.salutehome.home.mixColor
import com.salutehome.home.textPaint
import kotlin.math.roundToInt

/**
 * A frosted pill in the top bar: a glyph, a label, an optional quieter detail and an optional status dot.
 * Unfocused it is white at 8% with a hairline; focused it turns white with dark ink (200 ms).
 * [round] makes it a circle holding the glyph alone (Settings).
 */
/** The volume overlay's opening (VolumePillView, 320 ms): the top bar's number changes under it after that. */
private const val VOLUME_SETTLE_MS = 350L

/** TV Settings' "Remotes & accessories" page, and the action that opens a TV Settings slice. */
private const val SLICE_SETTINGS = "android.settings.SLICE_SETTINGS"
private const val ACCESSORIES_AUTHORITY = "com.android.tv.settings.accessories.sliceprovider"
private const val ACCESSORIES_SLICE = "content://$ACCESSORIES_AUTHORITY/general"

/** A pill easing to a new width. */
private const val WIDTH_MS = 260L

@SuppressLint("ViewConstructor")
class PillView(context: Context, iconRes: Int, private val round: Boolean = false) : View(context) {
    private val c = context
    private val pillHeight = c.dp(34f)
    private val padX = c.dp(14f)
    private val gap = c.dp(7f)
    private val iconSize = c.dpi(17f)
    private var icon: Bitmap = Glyphs.mask(c, iconRes, iconSize)
    private val textPaint = c.textPaint(Fonts.medium(c), 14f, Tokens.TEXT)
    private val subPaint = c.textPaint(Fonts.medium(c), 13f, Tokens.TEXT_2)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = c.dp(1f)
        color = 0x17FFFFFF
    }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val focus = Tween(0f)

    // The width eases to fit a new label (a device's name coming, the Inputs label folding away).
    private val shownWidth = Tween(-1f)
    private val labelAlpha = Tween(1f)

    /** Just the glyph, the label folded away: the top bar does this to Inputs when the pills don't fit. */
    var collapsed = false
        set(value) {
            if (field == value) return
            field = value
            labelAlpha.to(if (value) 0f else 1f, WIDTH_MS)
            // Set while the bar measures, which measures this pill next: no new layout to ask for.
            invalidate()
        }

    private var text = ""
    private var sub = ""
    private var dot: Int? = null

    var onPress: (() -> Unit)? = null

    /** The label's room is at least this text's width, so a changing number doesn't resize the pill ("100"). */
    var widestText: String = ""
        set(value) {
            field = value
            requestLayout()
        }

    init {
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
    }

    fun set(text: String, sub: String = "", dot: Int? = null, iconRes: Int = 0) {
        if (iconRes != 0) icon = Glyphs.mask(c, iconRes, iconSize)
        if (text == this.text && sub == this.sub && dot == this.dot && iconRes == 0) return
        this.text = text
        this.sub = sub
        this.dot = dot
        contentDescription = listOf(text, sub).filter { it.isNotEmpty() }.joinToString(", ")
        requestLayout()
        invalidate()
    }

    /** The pill's width (px) with its label showing. */
    fun fullWidth(): Float {
        if (round) return pillHeight
        var w = padX + iconSize
        if (dot != null) w += gap + c.dp(7f)
        if (text.isNotEmpty() || widestText.isNotEmpty()) w += gap + maxOf(textPaint.measureText(text), textPaint.measureText(widestText))
        if (sub.isNotEmpty()) w += c.dp(5f) + subPaint.measureText(sub)
        return w + padX
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = pillHeight.roundToInt()
        if (round) {
            setMeasuredDimension(h, h)
            return
        }
        val target = if (collapsed) 2 * padX + iconSize else fullWidth()
        if (shownWidth.value < 0f) shownWidth.snap(target) else shownWidth.to(target, WIDTH_MS)
        shownWidth.step(HomeMotion.now())
        setMeasuredDimension(shownWidth.value.roundToInt(), h)
        // Still easing to its width: measure again next frame. From here, not onDraw: a frame whose rounded
        // width doesn't change draws nothing, and the ease would stop a pixel short.
        if (shownWidth.running) {
            removeCallbacks(remeasure)
            postOnAnimation(remeasure)
        }
    }

    private val remeasure = Runnable { requestLayout() }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focus.to(if (gainFocus) 1f else 0f, 200)
        invalidate()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            performClick()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) true else super.onKeyDown(keyCode, event)

    override fun performClick(): Boolean {
        super.performClick()
        onPress?.invoke()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val now = HomeMotion.now()
        val moving = focus.step(now) or labelAlpha.step(now)
        val f = focus.value
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        bgPaint.color = mixColor(0x14FFFFFF, 0xFFF2F2F2.toInt(), f)
        canvas.drawRoundRect(rect, h / 2, h / 2, bgPaint)
        if (f < 1f) {
            hairPaint.alpha = ((1f - f) * 0x17).roundToInt()
            rect.inset(c.dp(0.5f), c.dp(0.5f))
            canvas.drawRoundRect(rect, h / 2, h / 2, hairPaint)
        }
        val ink = mixColor(Tokens.TEXT, 0xFF141414.toInt(), f)
        iconPaint.color = ink
        // A label narrower than the room kept for it ("5" where "88" fits) sits centred with its glyph.
        val slack = if (round || collapsed || widestText.isEmpty()) 0f
        else maxOf(0f, textPaint.measureText(widestText) - textPaint.measureText(text)) / 2f
        var x = if (round) (width - iconSize) / 2f else padX + slack
        canvas.drawBitmap(icon, x, (h - iconSize) / 2f, iconPaint)
        val la = labelAlpha.value
        if (!round && la > 0f) {
            // The label fades and is cut at the pill's edge while the pill narrows.
            canvas.save()
            canvas.clipRect(x + iconSize, 0f, width - padX / 2, h)
            x += iconSize
            dot?.let {
                x += gap
                dotPaint.color = it
                dotPaint.alpha = (Color.alpha(it) * la).roundToInt()
                val r = c.dp(3.5f)
                canvas.drawCircle(x + r, h / 2, r, dotPaint)
                x += 2 * r
            }
            if (text.isNotEmpty()) {
                x += gap
                textPaint.color = ink
                textPaint.alpha = (Color.alpha(ink) * la).roundToInt()
                canvas.drawText(text, x, baselineIn(textPaint, 0f, h), textPaint)
                x += textPaint.measureText(text)
            }
            if (sub.isNotEmpty()) {
                x += c.dp(5f)
                subPaint.color = mixColor(Tokens.TEXT_2, 0xFF5A5A5A.toInt(), f)
                subPaint.alpha = (Color.alpha(subPaint.color) * la).roundToInt()
                canvas.drawText(sub, x, baselineIn(subPaint, 0f, h), subPaint)
            }
            canvas.restore()
        }
        if (moving) postInvalidateOnAnimation()
    }
}

/** One row of a dropdown: a label, a quieter value on the right and an optional status dot. */
@SuppressLint("ViewConstructor")
class MenuRowView(context: Context, private val label: String, private val value: String, private val dot: Int?) : View(context) {
    private val c = context
    private val labelPaint = c.textPaint(Fonts.medium(c), 15f, Tokens.TEXT)
    private val valuePaint = c.textPaint(Fonts.medium(c), 13f, Tokens.TEXT_2)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val focus = Tween(0f)
    var onPress: (() -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        contentDescription = listOf(label, value).filter { it.isNotEmpty() }.joinToString(", ")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), c.dpi(40f))
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focus.to(if (gainFocus) 1f else 0f, 160)
        invalidate()
    }

    // Only a press that went down on this row: the release of the long press that opened an app's menu
    // lands here too, and must not choose the first row.
    private var down = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.repeatCount == 0) down = true
            true
        } else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (down && !event.isCanceled) performClick()
            down = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onPress?.invoke()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val moving = focus.step(HomeMotion.now())
        val f = focus.value
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (f > 0f) {
            bgPaint.color = mixColor(0x00FFFFFF, 0xFFF2F2F2.toInt(), f)
            canvas.drawRoundRect(rect, c.dp(12f), c.dp(12f), bgPaint)
        }
        val h = height.toFloat()
        var x = c.dp(14f)
        dot?.let {
            dotPaint.color = it
            canvas.drawCircle(x + c.dp(3.5f), h / 2, c.dp(3.5f), dotPaint)
            x += c.dp(15f)
        }
        labelPaint.color = mixColor(Tokens.TEXT, 0xFF141414.toInt(), f)
        canvas.drawText(label, x, baselineIn(labelPaint, 0f, h), labelPaint)
        if (value.isNotEmpty()) {
            valuePaint.color = mixColor(Tokens.TEXT_2, 0xFF5A5A5A.toInt(), f)
            val shown = TextUtils.ellipsize(value, valuePaint, width * 0.45f, TextUtils.TruncateAt.END)
            canvas.drawText(shown, 0, shown.length, width - c.dp(14f) - valuePaint.measureText(shown, 0, shown.length), baselineIn(valuePaint, 0f, h), valuePaint)
        }
        if (moving) postInvalidateOnAnimation()
    }
}

/**
 * A compact glass dropdown under a pill: a dark rounded panel holding [MenuRowView]s. Up and Down walk the
 * rows and stop at the ends; Left and Right stay; Back or a choice closes it and gives focus back to the pill.
 */
class DropdownView(context: Context) : LinearLayout(context) {
    private val c = context
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF21A1717.toInt() }
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = c.dp(1f)
        color = 0x1CFFFFFF
    }
    private val rect = RectF()
    private var anchor: View? = null
    val isOpen: Boolean get() = visibility == VISIBLE

    init {
        orientation = VERTICAL
        val p = c.dpi(6f)
        setPadding(p, p, p, p)
        setWillNotDraw(false)
        visibility = GONE
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    /** Opens under [pill], right edges aligned, with [rows] as (label, value, dot, action). */
    fun open(pill: View, rows: List<Row>) {
        removeAllViews()
        anchor = pill
        for (r in rows) {
            val v = MenuRowView(c, r.label, r.value, r.dot)
            v.onPress = {
                close()
                r.action()
            }
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val at = IntArray(2)
        val parentAt = IntArray(2)
        pill.getLocationInWindow(at)
        (parent as View).getLocationInWindow(parentAt)
        val w = c.dpi(260f)
        measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val parentView = parent as View
        val gap = c.dpi(8f)
        val below = at[1] - parentAt[1] + pill.height + gap
        val top = if (below + measuredHeight <= parentView.height - gap) below else at[1] - parentAt[1] - measuredHeight - gap
        val margin = c.dpi(16f)
        layoutParams = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (at[0] - parentAt[0] + pill.width - w).coerceIn(margin, maxOf(margin, parentView.width - w - margin))
            topMargin = top.coerceAtLeast(margin)
        }
        visibility = VISIBLE
        getChildAt(0)?.requestFocus()
    }

    fun close() {
        if (!isOpen) return
        visibility = GONE
        anchor?.requestFocus()
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val i = (0 until childCount).firstOrNull { getChildAt(it) === focused } ?: return super.focusSearch(focused, direction)
        return when (direction) {
            FOCUS_UP -> getChildAt(i - 1) ?: focused
            FOCUS_DOWN -> getChildAt(i + 1) ?: focused
            else -> focused
        }
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = c.dp(18f)
        canvas.drawRoundRect(rect, r, r, panelPaint)
        rect.inset(c.dp(0.5f), c.dp(0.5f))
        canvas.drawRoundRect(rect, r, r, hairPaint)
    }

    class Row(val label: String, val value: String = "", val dot: Int? = null, val action: () -> Unit)
}

/**
 * The top bar, right-aligned in its slot: Inputs (the input with a signal, its green dot), Wi-Fi (the
 * network's name, "VPN" while one is up), the volume, and Settings as a round button. Inputs and Settings
 * open a [DropdownView]; Wi-Fi opens Android's network settings; the volume pill opens the sound settings.
 */
@SuppressLint("ViewConstructor")
class TopBarView(context: Context, private val host: HomeHost, private val menu: DropdownView) : LinearLayout(context) {
    private val c = context
    private val worker = SideWorker()
    private val inputSource = TvInputSource(c, worker)
    private var inputs: List<TvInput> = emptyList()

    val inputsPill = PillView(c, R.drawable.ph_plug_bold)
    val wifiPill = PillView(c, R.drawable.ph_wifi_high_bold)
    val bluetoothPill = PillView(c, R.drawable.ph_bluetooth_bold)
    val volumePill = PillView(c, R.drawable.ph_speaker_simple_high_bold)
    val settingsPill = PillView(c, R.drawable.ph_gear_six_bold, round = true)
    private val pills = listOf(inputsPill, wifiPill, bluetoothPill, volumePill, settingsPill)
    private val bluetooth = BluetoothLink(c) { showBluetooth(it) }

    private val audio = c.getSystemService(AudioManager::class.java)
    private val connectivity = c.getSystemService(ConnectivityManager::class.java)
    private val wifi = c.applicationContext.getSystemService(WifiManager::class.java)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.END or Gravity.TOP
        clipChildren = false
        for ((i, p) in pills.withIndex()) {
            addView(p, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = c.dpi(8f)
            })
        }
        inputsPill.set(c.getString(R.string.top_inputs))
        wifiPill.set("Wi-Fi")
        inputsPill.onPress = { openInputs() }
        settingsPill.onPress = { openSettings() }
        wifiPill.onPress = { host.launch(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        volumePill.onPress = { openSound() }
        bluetoothPill.onPress = {
            // Android TV has no screen for ACTION_BLUETOOTH_SETTINGS; its "Remotes & accessories" page is a
            // slice, the one TV Settings opens itself (pairing, and every Bluetooth device the TV knows).
            // The slice only where TV Settings serves it: another firmware's slice screen would open empty.
            val pm = c.packageManager
            val slice = if (pm.resolveContentProvider(ACCESSORIES_AUTHORITY, 0) == null) null
            else Intent(SLICE_SETTINGS)
                .putExtra("slice_uri", ACCESSORIES_SLICE)
                .putExtra("slice_title", c.getString(R.string.top_accessories))
            val open = listOfNotNull(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), slice)
                .firstOrNull { it.resolveActivity(pm) != null } ?: Intent(Settings.ACTION_SETTINGS)
            host.launch(open)
        }
        bluetoothPill.visibility = if (bluetooth.present) VISIBLE else GONE
        // Room for two digits: 100 alone widens it a little.
        volumePill.widestText = "88"
    }

    /**
     * The pills right-aligned in the slot; when they would reach past its left edge (into the bento), Inputs
     * folds to its glyph, and unfolds when there's room again.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val shown = pills.filter { it.visibility != GONE }
        val need = shown.sumOf { it.fullWidth().toDouble() } + (shown.size - 1) * c.dp(8f)
        inputsPill.collapsed = need > MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Where the leftmost pill starts (px, in this view), for the island to centre itself beside it. */
    fun contentLeft(): Int = pills.first().left

    // ---- Lifecycle ----

    fun start() {
        worker.start()
        loadInputs()
        inputSource.listen { loadInputs() }
        refreshNetwork()
        refreshVolume()
        bluetooth.start()
        connectivity?.registerDefaultNetworkCallback(netCallback)
        NowPlayingListener.vpnListeners += vpnListener
        val f = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(volumeReceiver, f, Context.RECEIVER_EXPORTED)
        else c.registerReceiver(volumeReceiver, f)
        val rssi = IntentFilter(WifiManager.RSSI_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(rssiReceiver, rssi, Context.RECEIVER_EXPORTED)
        else c.registerReceiver(rssiReceiver, rssi)
    }

    fun stop() {
        inputSource.stopListening()
        NowPlayingListener.vpnListeners -= vpnListener
        bluetooth.stop()
        try {
            connectivity?.unregisterNetworkCallback(netCallback)
        } catch (e: IllegalArgumentException) {
            // Never registered.
        }
        try {
            c.unregisterReceiver(volumeReceiver)
        } catch (e: IllegalArgumentException) {
            // Never registered.
        }
        try {
            c.unregisterReceiver(rssiReceiver)
        } catch (e: IllegalArgumentException) {
            // Never registered.
        }
        worker.stop()
    }

    /** Back closes an open dropdown; true if it did. */
    fun onBack(): Boolean {
        if (!menu.isOpen) return false
        menu.close()
        return true
    }

    // ---- Inputs ----

    private fun loadInputs() {
        inputSource.load { list ->
            inputs = list
            val live = list.firstOrNull { Inputs.live(it) }
            if (live != null) inputsPill.set(live.label, dot = Tokens.LIVE_GREEN, iconRes = R.drawable.ph_plugs_connected_bold)
            else inputsPill.set(c.getString(R.string.top_inputs), iconRes = R.drawable.ph_plug_bold)
        }
    }

    private fun openInputs() {
        val rows = inputs.map { input ->
            val state = when {
                Inputs.live(input) -> c.getString(R.string.top_input_live)
                Inputs.idle(input) -> c.getString(R.string.top_input_none)
                else -> ""
            }
            DropdownView.Row(input.label, state, if (Inputs.live(input)) Tokens.LIVE_GREEN else null) {
                inputSource.switchTo(input, host)
            }
        }
        if (rows.isNotEmpty()) menu.open(inputsPill, rows)
    }

    // ---- Settings ----

    /** Sber's Sound panel, else Android's sound settings, else its settings (PictureSettings asks off the main thread). */
    private fun openSound() {
        worker.post {
            val i = PictureSettings.find(c, PictureSettings.soundCandidates())
            worker.toMain { if (i != null) host.launch(i) }
        }
    }

    private fun openSettings() {
        menu.open(
            settingsPill,
            listOf(
                DropdownView.Row(c.getString(R.string.top_picture)) {
                    worker.post {
                        val i = PictureSettings.find(c)
                        worker.toMain { if (i != null) host.launch(i) }
                    }
                },
                DropdownView.Row(c.getString(R.string.top_sound)) { openSound() },
                DropdownView.Row(c.getString(R.string.top_network)) { host.launch(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                DropdownView.Row(c.getString(R.string.top_apps)) { host.launch(Intent(Settings.ACTION_APPLICATION_SETTINGS)) },
                DropdownView.Row(c.getString(R.string.top_all_settings)) { host.launch(Intent(Settings.ACTION_SETTINGS)) },
            ),
        )
    }

    // ---- Network ----

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            post { refreshNetwork() }
        }

        override fun onLost(network: Network) {
            post { refreshNetwork() }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            post { refreshNetwork() }
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshNetwork() {
        val cm = connectivity ?: return
        var vpn = false
        var wifiUp = false
        var ethernet = false
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifiUp = true
        }
        // Which app it is (Happ, ByeDPI), by the notification it keeps; plain VPN if none says. Only while a
        // VPN is up: these apps keep their notification after a reboot, with nothing connected.
        val names = if (vpn) NowPlayingListener.runningVpnApps(c).map { vpnName(it) } else emptyList()
        val sub = when {
            names.isNotEmpty() -> "· " + names.joinToString(" + ")
            vpn -> "· VPN"
            else -> ""
        }
        when {
            ethernet -> wifiPill.set("Ethernet", sub)
            wifiUp -> {
                val name = try {
                    WifiMath.clean(wifi?.connectionInfo?.ssid)
                } catch (e: SecurityException) {
                    null
                }
                wifiPill.set(name ?: "Wi-Fi", sub, iconRes = signalGlyph())
            }
            else -> wifiPill.set(c.getString(R.string.top_offline), iconRes = R.drawable.ph_wifi_slash_bold)
        }
    }

    /** The Wi-Fi glyph for the signal now: four bars to none, as Android's own status bar counts them. */
    @Suppress("DEPRECATION")
    private fun signalGlyph(): Int {
        val rssi = wifi?.connectionInfo?.rssi ?: return R.drawable.ph_wifi_high_bold
        return when (WifiMath.level(rssi)) {
            3 -> R.drawable.ph_wifi_high_bold
            2 -> R.drawable.ph_wifi_medium_bold
            1 -> R.drawable.ph_wifi_low_bold
            else -> R.drawable.ph_wifi_none_bold
        }
    }

    /** The signal moves without the network changing: redraw the glyph when it crosses a bar. */
    private var signalLevel = -1
    private val rssiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = WifiMath.level(intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -100))
            if (level != signalLevel) {
                signalLevel = level
                refreshNetwork()
            }
        }
    }

    private fun vpnName(pkg: String): String {
        val label = try {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.')
        }
        // ByeByeDPI's name is long for the pill: its short form.
        return label.replace("ByeByeDPI", "ByeDPI", ignoreCase = true)
    }

    private val vpnListener: () -> Unit = { refreshNetwork() }

    // ---- Bluetooth ----

    /** The connected speaker's or headphones' name; the glyph alone when none is; struck through when off. */
    private fun showBluetooth(state: BluetoothLink.State) {
        bluetoothPill.set(state.device ?: "", iconRes = if (state.on) R.drawable.ph_bluetooth_bold else R.drawable.ph_bluetooth_slash_bold)
        bluetoothPill.contentDescription = state.device ?: c.getString(if (state.on) R.string.top_bluetooth else R.string.top_bluetooth_off)
    }

    // ---- Volume ----

    private val volumeReceiver = object : BroadcastReceiver() {
        // The volume pill that opens over this one (volume/VolumeOverlay) shows the change; this one takes
        // the new number while that covers it, so it's already right when the overlay closes.
        override fun onReceive(context: Context, intent: Intent) {
            removeCallbacks(refreshVolumeLater)
            postDelayed(refreshVolumeLater, VOLUME_SETTLE_MS)
        }
    }

    private val refreshVolumeLater = Runnable { refreshVolume() }

    private fun refreshVolume() {
        val a = audio ?: return
        val v = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = v == 0 || a.isStreamMute(AudioManager.STREAM_MUSIC)
        volumePill.set(if (muted) "0" else v.toString(), iconRes = if (muted) R.drawable.ph_speaker_simple_x_bold else R.drawable.ph_speaker_simple_high_bold)
    }
}
