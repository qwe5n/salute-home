package com.salutehome.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.salutehome.R
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether an app is for watching films and series, so TMDB's popular titles belong under it on the shelf.
 * Any other app without rows of its own (a VPN, a store, a player for your own files) shows its logo.
 */
object WatchApps {
    private val KNOWN = listOf(
        "ru.more.play", "ru.kinopoisk", "ru.ivi.client", "ru.rt.video.app", "ru.mts.mtstv", "ru.start",
        "ru.premier", "ru.okko", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.stremio",
        "com.disney.disneyplus", "com.hbo", "com.wbd.stream", "com.apple.atve",
    )
    private val PLAYERS = setOf("org.videolan.vlc", "com.mxtech.videoplayer.ad", "org.xbmc.kodi", "com.cvte.tv.media")

    /** [category] is the app's ApplicationInfo.category. */
    fun isWatchApp(packageName: String, category: Int): Boolean = when {
        packageName in PLAYERS -> false
        KNOWN.any { packageName == it || packageName.startsWith("$it.") } -> true
        else -> category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO
    }

    fun isWatchApp(pm: android.content.pm.PackageManager, packageName: String): Boolean = try {
        isWatchApp(packageName, pm.getApplicationInfo(packageName, 0).category)
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }
}

/** An app in the dock: its package, its name, and the cover drawn for it ([cover] 0 for its own banner). */
data class DockApp(val packageName: String, val label: String, val cover: Int)

/**
 * Which apps the dock holds: [PINNED] in the owner's order, any that isn't installed skipped, then any other
 * app with a TV launcher entry, A to Z, with its own banner, except the TV's own helper screens and
 * settings, and home screens.
 */
object DockApps {
    // The Salute TV's dock, in the owner's order. Spotify here is the phone app (no TV entry), and the
    // Sber catalogue has no TV entry either: both are found by their phone-style launch intent.
    val PINNED = listOf(
        DockApp("com.google.android.youtube.tv", "YouTube", R.drawable.cover_youtube),
        DockApp("com.spotify.tv.android", "Spotify", R.drawable.cover_spotify),
        DockApp("com.stremio.one", "Stremio", 0),
        DockApp("su.happ.proxyutility", "Happ", 0),
        DockApp("io.github.romanvht.byedpi", "ByeDPI", 0),
        DockApp("ru.more.play", "Okko", 0),
        DockApp("ru.sberdevices.starmarket", "Каталог", 0),
    )

    /**
     * Our covers for well-known TV apps, used wherever they're installed (dock or library), pinned or
     * not; any other app shows its own banner.
     */
    private val COVERS = mapOf(
        "com.google.android.youtube.tv" to R.drawable.cover_youtube,
        "com.spotify.tv.android" to R.drawable.cover_spotify,
        "com.amazon.amazonvideo.livingroom" to R.drawable.cover_primevideo,
        "com.netflix.ninja" to R.drawable.cover_netflix,
        "com.cvte.tv.media" to R.drawable.cover_media,
    )

    /** Our cover for [packageName], or 0 for its own banner. */
    fun coverOf(packageName: String): Int = COVERS[packageName] ?: 0


    /** Focus starts here, as the prototype does. */
    const val FIRST_FOCUS = "com.google.android.youtube.tv"

    private val HIDDEN = setOf(
        "com.gathin.tvhome", // the old launcher
        "com.salutehome",
        "com.cvte.settings", // the TV's settings apps: the side dock opens them
        "com.cvte.tv.setting",
        "com.android.tv.settings",
        "com.android.settings",
        "com.sberbank.settings",
        "ru.sberdevices.settings",
        "ru.sberdevices.webview",
    )

    /** Factory and system helper screens (CVTE's factory menu above all) never get a tile. */
    fun hidden(packageName: String): Boolean =
        packageName.startsWith("tv.cloudwalker.") || packageName.startsWith("com.cvte.fac") ||
            packageName.startsWith("com.cvte.factory") || packageName == "com.stb.factorymenu" || packageName in HIDDEN

    private val PINNED_NAMES = PINNED.map { it.packageName }.toSet()

    /** One of the user's own dock apps, drawn with its cover (the rest show their own banners). */
    fun isPinned(packageName: String): Boolean = packageName in PINNED_NAMES

    /** The most apps the dock holds; it never scrolls. */
    const val DOCK_SIZE = 7

    /**
     * The dock from [every] (the library, in its order). [saved] is the dock the owner set on the TV
     * (DockOrder): those apps, in that order, skipping any that's gone. With nothing saved: the pinned apps
     * that are installed, then, on a TV that has few of them, the next apps in the library until it's full.
     */
    fun dock(every: List<DockApp>, saved: List<String> = emptyList()): List<DockApp> {
        if (saved.isNotEmpty()) {
            val byPackage = every.associateBy { it.packageName }
            val mine = saved.distinct().mapNotNull { byPackage[it] }
            if (mine.isNotEmpty()) return mine.take(DOCK_SIZE)
        }
        val pinned = every.filter { isPinned(it.packageName) }
        return pinned + every.filter { !isPinned(it.packageName) }.take((DOCK_SIZE - pinned.size).coerceAtLeast(0))
    }

    /** The dock with [pkg] added at the end (no change if it's there already, or the dock is full). */
    fun add(dock: List<String>, pkg: String): List<String> = if (pkg in dock || isFull(dock)) dock else dock + pkg

    fun isFull(dock: List<String>): Boolean = dock.size >= DOCK_SIZE

    /** The dock without [pkg]; the last app stays, so the dock is never empty. */
    fun remove(dock: List<String>, pkg: String): List<String> = if (dock.size <= 1) dock else dock - pkg

    /** The dock with [pkg] moved one place left ([by] -1) or right (+1), stopping at the ends. */
    fun move(dock: List<String>, pkg: String, by: Int): List<String> {
        val i = dock.indexOf(pkg)
        val j = i + by
        if (i < 0 || j !in dock.indices) return dock
        return dock.toMutableList().apply { add(j, removeAt(i)) }
    }

    /**
     * The dock's order from what's installed: [found] is every app with a TV launcher entry as
     * (package, name). [saved] is the order the user set from the phone (DockOrder): those apps lead, in
     * that order, and the rest follow in the usual order; a saved app that's gone or hidden is skipped.
     * Pure, so it's tested without a TV.
     */
    fun order(found: List<Pair<String, String>>, saved: List<String> = emptyList()): List<DockApp> {
        val installed = found.associate { it }
        val pinned = PINNED.filter { it.packageName in installed }
        val others = found
            .filter { (pkg, _) -> pkg !in PINNED_NAMES && !hidden(pkg) }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .map { (pkg, label) -> DockApp(pkg, label, coverOf(pkg)) }
        val usual = pinned + others
        if (saved.isEmpty()) return usual
        val byPackage = usual.associateBy { it.packageName }
        val first = saved.distinct().mapNotNull { byPackage[it] }
        val firstNames = first.map { it.packageName }.toSet()
        return first + usual.filter { it.packageName !in firstNames }
    }

    /**
     * What's installed with a TV launcher entry, plus pinned apps that only have a phone-style one.
     * Every app is visible to it: the home holds QUERY_ALL_PACKAGES (Android 11 and later hide apps otherwise).
     */
    @SuppressLint("QueryPermissionsNeeded")
    fun installed(pm: PackageManager): List<Pair<String, String>> {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val found = pm.queryIntentActivities(probe, 0).mapNotNull { ri ->
            val info = ri.activityInfo ?: return@mapNotNull null
            val pinned = PINNED.firstOrNull { it.packageName == info.packageName }
            // A pinned app's name is ours; anyone else's comes from the app (it loads its resources).
            info.packageName to (pinned?.label ?: ri.loadLabel(pm).toString())
        }.toMutableList()
        for (pin in PINNED) {
            if (found.none { it.first == pin.packageName } && pm.getLaunchIntentForPackage(pin.packageName) != null) {
                found += pin.packageName to pin.label
            }
        }
        return found
    }
}

/**
 * The dock the owner set on the TV (hold OK on a tile: add, remove, move), kept in the app's preferences
 * where the home screen reads it. Listeners hear about a new one on the main thread.
 */
object DockOrder {
    private const val PREFS = "dock"
    private const val KEY = "order"

    // Made on first use: the order's sums are tested on the JVM, where there's no main looper.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The saved dock, first to last; empty means the usual one. */
    fun load(context: Context): List<String> = load(prefs(context))

    fun load(prefs: SharedPreferences): List<String> = decode(prefs.getString(KEY, null))

    /** Saves [order] (empty goes back to the usual dock) and tells the home screen; false if it couldn't be written. */
    fun save(context: Context, order: List<String>): Boolean {
        val ok = save(prefs(context), order)
        if (ok) main.post { for (l in listeners) l() }
        return ok
    }

    /** Written at once (commit, not apply). */
    fun save(prefs: SharedPreferences, order: List<String>): Boolean {
        val clean = decode(encode(order))
        val edit = prefs.edit()
        if (clean.isEmpty()) edit.remove(KEY) else edit.putString(KEY, encode(clean))
        return edit.commit()
    }

    /** Package names never hold a comma, so the order is kept as one line. */
    fun encode(order: List<String>): String = order.joinToString(",") { it.trim() }

    fun decode(saved: String?): List<String> =
        saved?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /** [listener] runs on the main thread whenever a new order is saved. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
