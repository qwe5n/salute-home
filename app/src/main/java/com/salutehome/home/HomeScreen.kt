package com.salutehome.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.salutehome.BuildConfig
import com.salutehome.R
import com.salutehome.core.HomeHost
import com.salutehome.core.Tokens
import com.salutehome.core.dpi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The home screen's own parts, in the slots activity_home.xml keeps for them: the glow behind everything,
 * the bento clock, the shelf, the app dock and the toast. It loads their data and keeps it fresh: the
 * weather every 30 minutes, the shelf every 4 hours, both from the cache first so they show at once after a
 * reboot and stay up offline; nothing touches the disk or the network on the main thread.
 *
 * The glow follows focus: the focused poster's or app's art, the now-playing art while focus is in the
 * island, and it fades out while focus is in the side dock.
 *
 * [live] false (snapshot tests) loads nothing: the test hands it data with [showWeather], [showShelf] and
 * [showApps], and [clock] and [zone] fix the time.
 */
class HomeScreen(
    private val context: Context,
    private val host: HomeHost,
    private val root: View,
    private val live: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    zone: TimeZone = TimeZone.getDefault(),
) {
    val glow = GlowView(context)
    val bento = BentoView(context)
    val shelf = ShelfView(context)
    val dock = DockView(context)
    val toast = ToastView(context)
    val apps = AllAppsView(context)
    val dockTab = DockTab(context)
    val weatherPanel = WeatherPanel(context)
    val idle = IdleView(context)
    private var shownItems: List<ShelfItem> = emptyList()

    /** The focused dock app's name, centred under its tile. */
    private val dockName = android.widget.TextView(context).apply {
        typeface = com.salutehome.core.Fonts.medium(context)
        textSize = 15f
        setTextColor(Tokens.TEXT)
        isSingleLine = true
    }

    private val topBarSlot: View = root.findViewById(R.id.topBarSlot)
    private val content: View = root.findViewById(R.id.content)

    // Night's dimming: black at 22% over the content (a plain fill), not content.alpha, which costs a
    // full-screen layer each frame and is what onRestart's fade-in animates.
    private val nightShade = View(context).apply {
        setBackgroundColor(0x38000000)
        visibility = View.GONE
    }
    private val shelfSlot: FrameLayout = root.findViewById(R.id.shelfSlot)
    private val dockSlot: FrameLayout = root.findViewById(R.id.dockSlot)

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = Http()

    // Made on first use, which is only ever in a live home screen (the cache directory is the app's).
    private val weatherSource by lazy { WeatherSource(context.cacheDir, http) }
    private val shelfSource by lazy { ShelfSource(context.cacheDir, http, BuildConfig.TMDB_KEY) }
    private val youtube by lazy { YouTube(context, http) }
    private val shelfKind = if (BuildConfig.TMDB_KEY.isNotEmpty()) ShelfData.Source.TMDB else ShelfData.Source.TVMAZE
    private var resumed = false

    // ---- Lifecycle --------------------------------------------------------------------------------------

    /** After every part is in place (onCreate): the apps, focus on YouTube, then the caches and network. */
    fun start() {
        if (!live) return
        loadApps()
        focusStart()
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(packageReceiver, packages, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(packageReceiver, packages)
        DockOrder.addListener(dockOrderListener)
        connectivity?.registerDefaultNetworkCallback(networkCallback)
        loadWeather()
        loadShelf()
        loadTvRows()
    }

    /** On screen: the clock ticks and the data is checked; nothing runs while another app is in front. */
    fun resume() {
        resumed = true
        restartIdle()
        startClock()
        if (live) {
            loadWeather()
            loadShelf()
            loadTvRows()
            handler.removeCallbacks(refresher)
            handler.postDelayed(refresher, CHECK_MS)
        }
    }

    fun pause() {
        resumed = false
        handler.removeCallbacks(idleStart)
        idle.hide()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(refresher)
        handler.removeCallbacks(retryWeather)
        handler.removeCallbacks(retryShelf)
    }

    fun destroy() {
        pause()
        scope.cancel()
        root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        DockOrder.removeListener(dockOrderListener)
        if (live) {
            try {
                context.unregisterReceiver(packageReceiver)
            } catch (e: IllegalArgumentException) {
                // Never registered: start() didn't run.
            }
            try {
                connectivity?.unregisterNetworkCallback(networkCallback)
            } catch (e: IllegalArgumentException) {
                // Never registered.
            }
        }
    }

    fun toast(text: CharSequence) = toast.show(text)

    /**
     * Something covers the whole screen while the activity stays resumed (music mode's black, the
     * screensaver): the clock stops ticking under it, and starts again on the second when it goes.
     */
    private var covered = false

    fun setCovered(covered: Boolean) {
        this.covered = covered
        if (covered) idle.hide()
        handler.removeCallbacks(ticker)
        if (!covered && resumed) startClock()
    }

    private fun startClock() {
        handler.removeCallbacks(ticker)
        // Back from another app: the second hand goes straight to the time, not round the missed seconds,
        // and the time zone is read again in case it was changed in Settings meanwhile.
        bento.dial.reset()
        if (live) {
            calendar.timeZone = TimeZone.getDefault()
            minuteStamp = -1
            dayStamp = -1
        }
        handler.post(ticker)
    }

    /**
     * Focus on YouTube, as the prototype starts (the first app if YouTube isn't here). Before the window
     * is up no focus news goes out, so the glow is told here; it waits for the cover, so it starts in
     * YouTube's red rather than grey.
     */
    fun focusStart() {
        val tile = dock.tileFor(DockApps.FIRST_FOCUS) ?: dock.tiles.firstOrNull() ?: return
        tile.requestFocus()
        if (tile.hasArt) glow.show(tile.glowColor)
        // Before the window is up the focus listener hears nothing: name the tile once laid out.
        tile.post { nameFor(tile) }
    }

    /** Moves focus to [view] by hand, the glow with it (needed before the window is up, as in tests). */
    fun focusOn(view: View) {
        view.requestFocus()
        glowFor(view)
    }

    // ---- The glow follows focus ---------------------------------------------------------------------------

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused -> glowFor(focused) }

    private fun glowFor(focused: View?) {
        when {
            focused == null -> Unit
            focused is ArtView -> glow.show(focused.glowColor)
            focused.isInside(topBarSlot) -> Unit
        }
        nameFor(focused)
    }

    /** The dock app's name under its tile while a dock tile has focus; nothing otherwise. */
    private fun nameFor(focused: View?) {
        val tile = focused as? AppTileView
        if (tile == null || !tile.isInside(dockSlot)) {
            dockName.animate().alpha(0f).setDuration(120).start()
            return
        }
        // The shelf follows the dock once focus rests on an app for a moment.
        dockFocus = tile.app.packageName
        handler.removeCallbacks(follow)
        handler.postDelayed(follow, FOLLOW_MS)
        dockName.text = tile.app.label
        dockName.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // From where the row is scrolling to, not where it is: with more than seven apps it may still be moving.
        val at = IntArray(2)
        val slotAt = IntArray(2)
        dock.getLocationInWindow(at)
        (dockName.parent as View).getLocationInWindow(slotAt)
        dockName.translationX = at[0] - slotAt[0] + dock.restingCentre(tile) - dockName.measuredWidth / 2f
        dockName.animate().alpha(1f).setDuration(160).start()
    }

    private fun View.isInside(ancestor: View): Boolean {
        var v: View? = this
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    // ---- The clock --------------------------------------------------------------------------------------

    private val calendar = Calendar.getInstance(zone)
    private val hourFormat = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).apply { timeZone = Weather.ZONE }
    private var minuteStamp = -1L
    private var dayStamp = -1
    private var hourKey = ""
    private var forecast: Forecast? = null
    private var reading: WeatherReading? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            // Next on the second, a few ms late so it lands in the new second.
            handler.postDelayed(this, 1000 - clock() % 1000 + 5)
        }
    }

    private fun updateClock() {
        val now = clock()
        calendar.timeInMillis = now
        val h = calendar.get(Calendar.HOUR_OF_DAY)
        val m = calendar.get(Calendar.MINUTE)
        bento.dial.tick(h, m, calendar.get(Calendar.SECOND))
        val minute = now / 60_000
        if (minute == minuteStamp) return
        minuteStamp = minute
        bento.time.setTime(h, m)
        // Late evening (23:00 to 6:00): the screen is a little darker and the glow softer, so a white
        // banner isn't a torch in a dark room.
        val night = h >= 23 || h < 6
        nightShade.visibility = if (night) View.VISIBLE else View.GONE
        // Under the shade too: 0.64 of the glow, then 78% of that, is the glow at half.
        glow.dim = if (night) 0.64f else 1f
        var lineChanged = false
        val key = hourFormat.format(now)
        if (key != hourKey) {
            // A new hour: the forecast already holds it, so "now" and "next" move on without a fetch.
            hourKey = key
            reading = forecast?.readingAt(key)
            bento.sky.setWeather(reading)
            idle.weather = reading
            lineChanged = true
        }
        val day = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
        if (day != dayStamp) {
            dayStamp = day
            lineChanged = true
        }
        if (lineChanged) {
            val weekday = SimpleDateFormat("EEE", RU).apply { timeZone = calendar.timeZone }.format(now).trimEnd('.').uppercase(RU)
            bento.line.setLine(weekday, calendar.get(Calendar.DAY_OF_MONTH), reading)
        }
    }

    // ---- Weather ----------------------------------------------------------------------------------------

    /** Shows [forecast] (null: none yet) for the hour it is now. */
    fun showWeather(forecast: Forecast?) {
        this.forecast = forecast
        hourKey = ""
        minuteStamp = -1
        updateClock()
    }

    private var weatherJob: Job? = null
    private val retryWeather = Runnable { loadWeather() }

    private fun loadWeather() {
        if (weatherJob?.isActive == true) return
        weatherJob = scope.launch {
            if (forecast == null) withContext(Dispatchers.IO) { weatherSource.cached() }?.let { showWeather(it) }
            val age = withContext(Dispatchers.IO) { weatherSource.age(clock()) }
            if (age < Weather.EVERY_MS || !online()) return@launch
            try {
                showWeather(withContext(Dispatchers.IO) { weatherSource.fetch() })
            } catch (e: IOException) {
                // Offline or Open-Meteo hiccuped: keep what's shown; with nothing yet, try again soon.
                android.util.Log.w(LOG, "weather: $e")
                if (reading == null && resumed) handler.postDelayed(retryWeather, RETRY_MS)
            }
        }
    }

    // ---- The shelf --------------------------------------------------------------------------------------

    private var shelfData: ShelfData? = null
    private var shelfComplete = false
    private var shelfJob: Job? = null
    private val retryShelf = Runnable { loadShelf() }

    /**
     * Shows [data]'s posters with their [pictures] and the pictures' vivid [colors]; null data shows the
     * calm empty state: the film slates and one quiet line.
     */
    fun showShelf(
        data: ShelfData?,
        pictures: List<Bitmap?>,
        colors: List<Int>,
        heads: Heads? = null,
    ) {
        shelf.setSolo(false)
        glow.wash(false)
        val source = data?.source ?: shelfKind
        val head = heads?.head ?: context.getString(if (source == ShelfData.Source.TMDB) R.string.home_shelf_trending else R.string.home_shelf_new)
        val credit = heads?.credit ?: when {
            data == null -> ""
            source == ShelfData.Source.TMDB -> context.getString(R.string.home_credit_tmdb)
            else -> context.getString(R.string.home_credit_tvmaze)
        }
        shelf.setHead(head, credit, heads?.second ?: "", heads?.secondAt ?: -1)
        shownItems = data?.items.orEmpty()
        for ((i, poster) in shelf.posters.withIndex()) {
            val item = data?.items?.getOrNull(i)
            if (item == null) {
                poster.setText(if (i == 0) context.getString(R.string.home_shelf_waiting) else "", "")
                poster.onPress = null
                poster.progress = -1f
                // A short row (an app with three things to show) leaves the rest of the shelf empty.
                poster.visibility = if (i == 0 || data == null) View.VISIBLE else View.INVISIBLE
            } else {
                poster.visibility = View.VISIBLE
                poster.setText(item.title, item.meta)
                poster.progress = item.progress
                poster.onPress = { open(item) }
            }
            poster.setArt(pictures.getOrNull(i))
            poster.glowColor = colors.getOrNull(i) ?: VividColor.FALLBACK
            if (poster.isFocused) glow.show(poster.glowColor)
        }
        shelf.syncPlayer()
    }

    /** The shelf's heads: the first on the left, the credit on the right, a second over poster [secondAt]. */
    class Heads(val head: String, val credit: String, val second: String = "", val secondAt: Int = -1)

    /** A poster's title in its own app if the app said how, else the app itself. */
    private fun open(item: ShelfItem) {
        if (item.intent == YouTube.SIGN_IN) {
            signInYouTube()
            return
        }
        if (item.intent == SpotifyLiked.OPEN) {
            if (!SpotifyLiked.play(context) { host.launch(it) }) host.launchApp(item.packageName)
            return
        }
        if (item.intent.isNotEmpty()) {
            val intent = try {
                Intent.parseUri(item.intent, Intent.URI_INTENT_SCHEME)
            } catch (e: java.net.URISyntaxException) {
                null
            }
            if (intent != null && host.launch(intent)) return
        }
        host.launchApp(item.packageName)
    }

    /** The art size (px) of each poster, from the slot's size, so pictures are cut before layout. */
    private fun posterSizes(): List<Pair<Int, Int>> {
        val slot = shelfSlot.layoutParams
        val g = ShelfView.geometry(context, slot.width.toFloat(), slot.height.toFloat())
        return List(ShelfView.COUNT) { g.widths[it].roundToInt() to g.artHeight.roundToInt() }
    }

    private fun loadShelf() {
        if (shelfJob?.isActive == true) return
        shelfJob = scope.launch {
            if (shelfData == null) withContext(Dispatchers.IO) { shelfSource.cached() }?.let { present(it, online = false) }
            val now = clock()
            val stale = withContext(Dispatchers.IO) { shelfSource.age(now) } >= ShelfSource.EVERY_MS
            val quiet = shelfQuietUntil - now in 1..QUIET_MS
            android.util.Log.i(LOG, "shelf: stale=$stale complete=$shelfComplete quiet=$quiet online=${online()}")
            if ((!stale && shelfComplete) || quiet || !online()) return@launch
            try {
                val data = shelfData.takeUnless { stale }
                    ?: withContext(Dispatchers.IO) { shelfSource.fetch(now, installedPackages, shelfApp()) }
                present(data, online = true)
                // A picture that still won't come (gone from its server) isn't asked for again for a while.
                if (!shelfComplete) shelfQuietUntil = clock() + QUIET_MS
            } catch (e: ShelfSource.NotEnoughShows) {
                // Too few titles this week: asking again in a minute won't change that.
                android.util.Log.w(LOG, "shelf: ${e.message}")
                shelfQuietUntil = clock() + QUIET_MS
            } catch (e: IOException) {
                // Offline or the service hiccuped: keep the shelf that's up; with none yet, try again soon.
                android.util.Log.w(LOG, "shelf: $e")
                if (shelfData == null && resumed) handler.postDelayed(retryShelf, RETRY_MS)
            } catch (e: RuntimeException) {
                // An answer this code didn't expect: keep what's up rather than crash the home screen.
                android.util.Log.e(LOG, "shelf", e)
                if (shelfData == null && resumed) handler.postDelayed(retryShelf, RETRY_MS)
            }
        }
    }

    private var shelfQuietUntil = 0L

    // ---- Idle mode ----------------------------------------------------------------------------------------

    private val idleStart = Runnable { startIdle() }
    private var idleJob: Job? = null

    /** A key or touch: idle mode goes (true if it was showing, so that key only wakes) and the wait starts again. */
    fun onUserActivity(): Boolean {
        val woke = idle.isOn
        idle.hide()
        idleJob?.cancel()
        restartIdle()
        return woke
    }

    private fun restartIdle() {
        handler.removeCallbacks(idleStart)
        if (live && resumed) handler.postDelayed(idleStart, IdleView.AFTER_MS)
    }

    /** The shelf's titles at screen size (TMDB's larger backdrops), then idle mode, if nothing is open on Home. */
    private fun startIdle() {
        if (!resumed || covered || apps.isOpen || weatherPanel.isOpen || shownItems.isEmpty()) return
        val items = shownItems
        idleJob?.cancel()
        idleJob = scope.launch {
            val w = root.width / 2
            val h = root.height / 2
            val slides = withContext(Dispatchers.IO) {
                items.map { item ->
                    val big = item.copy(image = item.image.replace("/w780/", "/w1280/"))
                    IdleView.Slide(item.title, item.meta, rowPicture(big, w, h) ?: rowPicture(item, w, h))
                }.filter { it.picture != null }
            }
            if (resumed && !covered && !apps.isOpen && !weatherPanel.isOpen) idle.show(slides)
        }
    }

    // ---- The shelf follows the dock ----------------------------------------------------------------------

    private var tmdbPictures: List<Bitmap?> = emptyList()
    private var tmdbColors: List<Int> = emptyList()
    private var tvRows: Map<String, List<TvRows.Row>> = emptyMap()

    /** The app whose own rows are on the shelf, or null for TMDB's popular titles. */
    private var shelfApp: String? = null
    private var rowsJob: Job? = null
    private var appShelfJob: Job? = null
    private val follow = Runnable { followDock() }
    private var dockFocus: String? = null

    /** Where on the dock the shelf last followed, so it knows which way to slide. */
    private var followedAt = -1

    /** Reads what the apps publish to the TV listings (on start and each time Home comes back). */
    private fun loadTvRows() {
        if (rowsJob?.isActive == true) return
        rowsJob = scope.launch {
            val rows = withContext(Dispatchers.IO) { TvRows.read(context) }
            // Back from an app or a settings panel with the same rows: leave the shelf as it is (redrawing
            // it blanked the posters for a moment while their pictures loaded again).
            loadYouTube()
            if (rows == listingRows) return@launch
            listingRows = rows
            publishRows()
        }
    }

    /** The apps' own rows plus YouTube's (which YouTube doesn't publish here), and the shelf if it shows them. */
    private fun publishRows() {
        var rows = if (youtubeRows.isEmpty()) listingRows else listingRows + (YouTube.PACKAGE to youtubeRows)
        // Spotify's shelf opens with Liked Songs, ahead of what Spotify itself recommends.
        if (library.any { it.packageName == SpotifyLiked.PACKAGE }) {
            rows = rows + (SpotifyLiked.PACKAGE to listOf(SpotifyLiked.row(context)) + rows[SpotifyLiked.PACKAGE].orEmpty())
        }
        if (rows == tvRows) return
        tvRows = rows
        if (dockFocus != null) followDock(force = true)
    }

    // ---- YouTube's rows -----------------------------------------------------------------------------------

    private var listingRows: Map<String, List<TvRows.Row>> = emptyMap()
    private var youtubeRows: List<TvRows.Row> = emptyList()
    private var youtubeAt = 0L
    private var youtubeJob: Job? = null
    private var signInJob: Job? = null
    private var youtubeCode: YouTube.Code? = null

    /** The half-watched video and four from the feed, at most every [YOUTUBE_MS]; a sign-in card until signed in. */
    private fun loadYouTube() {
        if (!live || youtubeJob?.isActive == true) return
        if (!youtube.signedIn) {
            youtubeRows = listOf(signInRow())
            publishRows()
            return
        }
        val now = clock()
        if (now - youtubeAt < YOUTUBE_MS || !online()) return
        youtubeJob = scope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { youtube.rows(now) }
                youtubeAt = now
                if (rows.isNotEmpty()) youtubeRows = rows
            } catch (e: Exception) {
                // Offline, YouTube changed its answers, or the sign-in was taken back: keep what's up.
                android.util.Log.w(LOG, "youtube: $e")
                if (!youtube.signedIn) youtubeRows = listOf(signInRow())
            }
            publishRows()
        }
    }

    private fun signInRow(): TvRows.Row {
        val code = youtubeCode
        return TvRows.Row(
            packageName = YouTube.PACKAGE,
            title = code?.user ?: context.getString(R.string.youtube_sign_in),
            meta = if (code != null) context.getString(R.string.youtube_sign_in_where, code.url.substringAfter("://").removePrefix("www."))
            else context.getString(R.string.youtube_sign_in_why),
            image = "android.resource://${context.packageName}/drawable/cover_youtube",
            intentUri = YouTube.SIGN_IN,
            progress = -1f,
            engaged = 0L,
        )
    }

    /** OK on the sign-in card: a code on the card, then waiting (up to its half hour) for it to be typed. */
    private fun signInYouTube() {
        if (signInJob?.isActive == true) return
        signInJob = scope.launch {
            try {
                val code = withContext(Dispatchers.IO) { youtube.requestCode(clock()) }
                youtubeCode = code
                youtubeRows = listOf(signInRow())
                publishRows()
                while (clock() < code.until) {
                    kotlinx.coroutines.delay(code.everyMs)
                    if (withContext(Dispatchers.IO) { youtube.poll(code, clock()) }) {
                        youtubeCode = null
                        youtubeAt = 0L
                        loadYouTube()
                        return@launch
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(LOG, "youtube sign-in: $e")
                toast.show(context.getString(R.string.youtube_sign_in_failed))
            }
            youtubeCode = null
            youtubeRows = listOf(signInRow())
            publishRows()
        }
    }

    /** Focus settled on a dock app: show its own rows, or TMDB's when it publishes none. */
    private fun followDock(force: Boolean = false) {
        val pkg = dockFocus?.takeIf { tvRows[it]?.isNotEmpty() == true }
        // An app with no rows that isn't for films and series: its logo, not TMDB's popular titles.
        val soloApp = dockFocus?.takeIf { pkg == null && !WatchApps.isWatchApp(context.packageManager, it) }
            ?.let { dock.tileFor(it)?.app }
        val key = pkg ?: soloApp?.let { SOLO + it.packageName }
        val at = dock.tiles.indexOfFirst { it.app.packageName == dockFocus }
        val dir = if (at < followedAt) -1 else 1
        if (at >= 0) followedAt = at
        if (key == shelfApp && !force) return
        // Another app's shelf slides in; the same app's rows refreshing just update in place.
        val slide = key != shelfApp
        shelfApp = key
        appShelfJob?.cancel()
        // The player moves too when the shelf goes to or from a logo.
        if (slide) shelf.leave(dir, withPlayer = (soloApp != null) != shelf.solo)
        // Shows the new shelf once the old one is out of sight. Checked on the shelf, not [slide]: a refresh
        // can cancel the job that would have brought a sliding shelf back.
        suspend fun swap(show: () -> Unit) {
            if (shelf.isOut) delay(ShelfView.OUT_MS - (android.os.SystemClock.uptimeMillis() - shelf.outAt))
            show()
            if (shelf.isOut) shelf.enter(dir)
        }
        if (soloApp != null) {
            appShelfJob = scope.launch {
                val w = context.dpi(AppBadgeView.BANNER_W)
                val (banner, _) = withContext(Dispatchers.IO) { cover(soloApp, w, w * 9 / 16) }
                if (shelfApp == key) swap {
                    shelf.setSolo(true)
                    shelf.badge.show(banner)
                    glow.wash(true)
                }
            }
            return
        }
        if (pkg == null) {
            appShelfJob = scope.launch { swap { showShelf(shelfData, tmdbPictures, tmdbColors) } }
            return
        }
        val rows = tvRows[pkg].orEmpty().take(ShelfView.COUNT)
        val label = dock.tileFor(pkg)?.app?.label ?: ""
        val own = rows.map { ShelfItem(it.title, it.meta, it.image, label, pkg, it.progress, it.intentUri) }
        // An app with only a title or two of its own: TMDB's popular titles fill the rest of the row.
        val fill = shelfData?.items.orEmpty().take(ShelfView.COUNT - own.size)
        val items = own + fill
        val watching = rows.count { it.progress >= 0f }
        val recommended = own.size - watching
        val popular = context.getString(R.string.home_shelf_trending)
        val heads = when {
            watching == 0 -> Heads(context.getString(R.string.home_shelf_recommended), label, if (fill.isNotEmpty()) popular else "", own.size)
            recommended > 0 -> Heads(context.getString(R.string.home_shelf_continue), label, context.getString(R.string.home_shelf_recommended), watching)
            fill.isNotEmpty() -> Heads(context.getString(R.string.home_shelf_continue), label, popular, watching)
            else -> Heads(context.getString(R.string.home_shelf_continue), label)
        }
        val data = ShelfData(ShelfData.Source.TMDB, items)
        // The pictures load while the old shelf slides out, so the new one comes in whole; pictures that are
        // slow to come (an app's own provider can take seconds) fill in after the posters are in.
        appShelfJob = scope.launch {
            val sizes = posterSizes()
            val loading = async(Dispatchers.IO) {
                items.mapIndexed { i, item -> rowPicture(item, sizes[i].first, sizes[i].second) }
            }
            val early = withTimeoutOrNull(PICTURES_WAIT_MS) { loading.await() }
            if (early == null) swap { showShelf(data, emptyList(), emptyList(), heads) }
            val pictures = early ?: loading.await()
            val colors = withContext(Dispatchers.Default) { pictures.map { VividColor.of(it) } }
            if (shelfApp != pkg) return@launch
            if (early != null) swap { showShelf(data, pictures, colors, heads) } else showShelf(data, pictures, colors, heads)
        }
    }

    /** A row's picture: from the web like TMDB's, or from the app's own provider (content:, android.resource:). */
    private fun rowPicture(item: ShelfItem, width: Int, height: Int): Bitmap? {
        if (item.image.startsWith("http")) return shelfSource.picture(item, width, height, online())
        val key = Images.keyFor(item.image, width, height)
        Images.remembered(key)?.let { return it }
        // Its own file: the screensaver and the shelf fetch pictures at the same time.
        val tmp = try {
            java.io.File.createTempFile("row", ".part", context.cacheDir)
        } catch (e: java.io.IOException) {
            return null
        }
        return try {
            val stream = context.contentResolver.openInputStream(android.net.Uri.parse(item.image)) ?: return null
            stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            Images.decodeFile(tmp, width, height, opaque = true)?.also { Images.remember(key, it) }
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    /** Cuts [data]'s pictures to size (from disk, or downloaded when [online]) and shows it all at once. */
    private suspend fun present(data: ShelfData, online: Boolean) {
        val sizes = posterSizes()
        val pictures = withContext(Dispatchers.IO) {
            data.items.mapIndexed { i, item -> shelfSource.picture(item, sizes[i].first, sizes[i].second, online) }
        }
        val colors = withContext(Dispatchers.Default) { pictures.map { VividColor.of(it) } }
        shelfData = data
        shelfComplete = pictures.all { it != null }
        tmdbPictures = pictures
        tmdbColors = colors
        // Not while the shelf slides out: the swap waiting for it shows these when the shelf comes back.
        if (shelfApp == null && !shelf.isOut) showShelf(data, pictures, colors)
        // An app's short row, filled with TMDB's titles when it was shown: fill it again with these.
        val app = shelfApp
        if (app != null && !app.startsWith(SOLO) && tvRows[app].orEmpty().size < ShelfView.COUNT) followDock(force = true)
        if (online) {
            val keys = data.items.mapIndexed { i, item -> Images.keyFor(item.image, sizes[i].first, sizes[i].second) }.toSet()
            withContext(Dispatchers.IO) { shelfSource.prunePictures(keys) }
        }
    }

    /**
     * The app the trending shelf's posters open: shelf.app from local.properties if it's installed, else the
     * first app for films and series in the dock (YouTube aside), or null when there is none.
     */
    private fun shelfApp(): Streaming.Service? {
        val pkg = BuildConfig.SHELF_APP.takeIf { it in installedPackages }
            ?: dock.tiles.map { it.app.packageName }
                .firstOrNull { it != YouTube.PACKAGE && WatchApps.isWatchApp(context.packageManager, it) }
            ?: return null
        val label = dock.tileFor(pkg)?.app?.label ?: library.firstOrNull { it.packageName == pkg }?.label ?: pkg
        return Streaming.Service(label, pkg)
    }

    // ---- The app dock -----------------------------------------------------------------------------------

    private var installedPackages: Set<String> = emptySet()
    private var library: List<DockApp> = emptyList()
    private var appsJob: Job? = null

    /** Shows [apps] in the dock with their [covers] (by package) and the covers' vivid [colors]. */
    fun showApps(apps: List<DockApp>, covers: Map<String, Bitmap?> = emptyMap(), colors: Map<String, Int> = emptyMap()) {
        installedPackages = apps.map { it.packageName }.toSet()
        dock.setApps(apps)
        for (tile in dock.tiles) {
            covers[tile.app.packageName]?.let { tile.setArt(it) }
            colors[tile.app.packageName]?.let {
                tile.glowColor = it
                if (tile.isFocused) glow.show(it)
            }
        }
    }

    /** The covers' size for [count] apps in the dock slot (px). */
    fun coverSize(count: Int): Pair<Int, Int> = dock.tileSize(dockSlot.layoutParams.width, count)

    // A new dock from the tile menu: the dock re-lays itself at once.
    private val dockOrderListener: () -> Unit = { loadApps() }

    private fun loadApps() {
        val pm = context.packageManager
        val every = try {
            DockApps.order(DockApps.installed(pm))
        } catch (e: RuntimeException) {
            // The package manager died with the system: keep the dock as it is.
            return
        }
        // The dock holds the owner's apps; the library (Down from the dock) holds everything.
        library = every
        // Spotify's Liked Songs card shows only once we know Spotify is installed.
        publishRows()
        // The saved dock is one short preference, read once per process and then kept in memory.
        val apps = DockApps.dock(every, DockOrder.load(context))
        showApps(apps)
        appsJob?.cancel()
        appsJob = scope.launch {
            val (w, h) = coverSize(apps.size)
            val arts = withContext(Dispatchers.IO) { apps.map { cover(it, w, h) } }
            showApps(apps, apps.indices.associate { apps[it].packageName to arts[it].first }, apps.indices.associate { apps[it].packageName to arts[it].second })
        }
    }

    /** An app's cover (ours for the pinned apps, its own banner otherwise) cut to the tile, and its colour. */
    private fun cover(app: DockApp, width: Int, height: Int): Pair<Bitmap?, Int> {
        val key = "cover:${app.packageName}:${width}x$height"
        val bitmap = Images.remembered(key) ?: try {
            if (app.cover != 0) Images.decodeResource(context.resources, app.cover, width, height, opaque = true)
            else banner(app.packageName, width, height)
        } catch (e: RuntimeException) {
            null
        }?.also { Images.remember(key, it) }
        return bitmap to VividColor.of(bitmap)
    }

    private fun banner(packageName: String, width: Int, height: Int): Bitmap? {
        val pm = context.packageManager
        return try {
            val component = pm.getLeanbackLaunchIntentForPackage(packageName)?.component
            val banner = component?.let { pm.getActivityBanner(it) } ?: pm.getApplicationBanner(packageName)
            if (banner != null) Images.render(banner, width, height) else iconTile(pm.getApplicationIcon(packageName), width, height)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** An app with no TV banner: its icon in the middle of a dark tile. */
    private fun iconTile(icon: Drawable, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Tokens.FILL)
        val side = (height * 0.56f).roundToInt()
        val left = (width - side) / 2
        val top = (height - side) / 2
        icon.setBounds(left, top, left + side, top + side)
        icon.draw(canvas)
        return out
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadApps()
        }
    }

    // ---- Keeping fresh ----------------------------------------------------------------------------------

    private val connectivity: ConnectivityManager? = if (live) context.getSystemService(ConnectivityManager::class.java) else null

    private fun online(): Boolean = connectivity?.activeNetwork != null

    /** The network coming up (at boot it lags the home screen) brings anything stale or missing. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (resumed) {
                    loadWeather()
                    loadShelf()
                }
            }
        }
    }

    private val refresher = object : Runnable {
        override fun run() {
            loadWeather()
            loadShelf()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    // Last, so every part above exists before it runs.
    init {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.findViewById<FrameLayout>(R.id.glowSlot).addView(glow, FrameLayout.LayoutParams(match, match))
        root.findViewById<FrameLayout>(R.id.bentoSlot).addView(bento, FrameLayout.LayoutParams(match, match))
        // The shelf reaches a ring's width past its slot on three sides, so focus rings stay inside it.
        val o = shelf.overhang
        val slot = shelfSlot.layoutParams
        shelf.soloPlayerWidth = root.findViewById<FrameLayout>(R.id.bentoSlot).layoutParams.width
        shelf.downTo = { dockFocus?.let { dock.tileFor(it) } }
        shelfSlot.addView(shelf, FrameLayout.LayoutParams(slot.width + 2 * o, slot.height + o).apply {
            leftMargin = -o
            topMargin = -o
        })
        dockSlot.addView(dock, FrameLayout.LayoutParams(match, match))
        dock.onShifted = { tile -> if (tile.isFocused) nameFor(tile) }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        root.findViewById<FrameLayout>(R.id.toastSlot).addView(toast, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = context.dpi(Tokens.S5)
        })
        dock.onOpen = { app -> host.launchApp(app.packageName) }
        // Down in the dock: the tab rises, then the library slides up after it, and Back returns to the
        // same tile.
        dock.onDown = { from ->
            if (!apps.isOpen) {
                dockTab.raise(true)
                handler.postDelayed({ if (!apps.isOpen) apps.open(library, from) }, TAB_LEAD_MS)
            }
        }
        apps.onClosed = { dockTab.raise(false) }
        root.findViewById<FrameLayout>(R.id.content).addView(
            dockTab,
            FrameLayout.LayoutParams(context.dpi(DockTab.W), context.dpi(DockTab.H), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        )
        // Last in the content, over everything in it.
        root.findViewById<FrameLayout>(R.id.content).addView(nightShade, FrameLayout.LayoutParams(match, match))
        apps.onOpen = { host.launchApp(it.packageName) }
        apps.coverFor = { app, w, h, done ->
            scope.launch { done(withContext(Dispatchers.IO) { cover(app, w, h).first }) }
        }
        root.findViewById<FrameLayout>(R.id.appsSlot).addView(apps, FrameLayout.LayoutParams(match, match))
        root.findViewById<FrameLayout>(R.id.appsSlot).addView(weatherPanel, FrameLayout.LayoutParams(match, match))
        root.findViewById<FrameLayout>(R.id.appsSlot).addView(idle, FrameLayout.LayoutParams(match, match))
        bento.onPress = { weatherPanel.open(forecast, hourKey, bento) }
        root.findViewById<FrameLayout>(R.id.dockNameSlot).addView(dockName, FrameLayout.LayoutParams(wrap, wrap))
        dockName.alpha = 0f
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        showShelf(null, emptyList(), emptyList())
        updateClock()
    }

    private companion object {
        val RU: Locale = Locale("ru")
        const val LOG = "SaluteHome"
        const val FOLLOW_MS = 200L

        /** How long the shelf waits for an app's pictures before sliding its posters in without them. */
        const val PICTURES_WAIT_MS = 300L

        /** [shelfApp] for an app shown by its logo. */
        private const val SOLO = "solo:"

        /** How long the tab rises before the library follows it. */
        const val TAB_LEAD_MS = 120L

        /** How often a resumed home screen checks whether its data is due (each source has its own age). */
        const val CHECK_MS = 5 * 60 * 1000L
        const val RETRY_MS = 60 * 1000L
        const val YOUTUBE_MS = 2 * 60 * 1000L

        /** How long the shelf leaves its service alone after an answer that asking again won't improve. */
        const val QUIET_MS = 30 * 60 * 1000L
    }
}
