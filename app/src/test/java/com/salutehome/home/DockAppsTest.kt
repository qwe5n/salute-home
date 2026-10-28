package com.salutehome.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockAppsTest {
    /** What this TV lists with a launcher entry, plus settings and home screens that never get a tile. */
    private val tv = listOf(
        "ru.rutube.app" to "Rutube",
        "com.spotify.tv.android" to "Spotify",
        "com.google.android.youtube.tv" to "YouTube",
        "su.happ.proxyutility" to "Happ",
        "ru.more.play" to "Okko",
        "ru.sberdevices.starmarket" to "Каталог",
        "org.videolan.vlc" to "VLC",
        "com.aurora.store" to "Aurora Store",
        "com.cvte.tv.setting" to "TV Settings",
        "com.salutehome" to "Главный экран",
        "com.netflix.ninja" to "Netflix",
    )

    @Test
    fun pinnedAppsLeadInTheOwnersOrder() {
        assertEquals(
            listOf("YouTube", "Spotify", "Happ", "Okko", "Каталог"),
            DockApps.order(tv).take(5).map { it.label },
        )
    }

    @Test
    fun otherAppsFollowAToZ() {
        assertEquals(listOf("Aurora Store", "Netflix", "Rutube", "VLC"), DockApps.order(tv).drop(5).map { it.label })
    }

    @Test
    fun knownAppsGetOurCoverEvenUnpinned() {
        val apps = DockApps.order(tv).associateBy { it.packageName }
        assertTrue(apps.getValue("com.netflix.ninja").cover != 0)
        assertEquals(0, apps.getValue("org.videolan.vlc").cover)
    }

    @Test
    fun settingsHomesAndFactoryScreensAreHidden() {
        assertTrue(DockApps.hidden("com.cvte.tv.setting"))
        assertTrue(DockApps.hidden("com.salutehome"))
        assertTrue(DockApps.hidden("com.cvte.factory.menu"))
        assertFalse(DockApps.hidden("com.google.android.youtube.tv"))
    }

    @Test
    fun aMissingPinnedAppIsSkipped() {
        val noOkko = tv.filter { it.first != "ru.more.play" }
        assertFalse(DockApps.order(noOkko).any { it.label == "Okko" })
    }

    @Test
    fun theSavedOrderLeads() {
        val apps = DockApps.order(tv, saved = listOf("org.videolan.vlc", "ru.more.play"))
        assertEquals(listOf("VLC", "Okko"), apps.take(2).map { it.label })
    }

    @Test
    fun aTvWithFewPinnedAppsFillsTheDockFromTheLibrary() {
        val few = listOf("com.google.android.youtube.tv" to "YouTube", "com.netflix.ninja" to "Netflix", "org.videolan.vlc" to "VLC")
        assertEquals(listOf("YouTube", "Netflix", "VLC"), DockApps.dock(DockApps.order(few)).map { it.label })
        assertEquals(DockApps.DOCK_SIZE, DockApps.dock(DockApps.order(tv)).size)
    }

    @Test
    fun theSavedDockIsTheDock() {
        val dock = DockApps.dock(DockApps.order(tv), saved = listOf("org.videolan.vlc", "gone.app", "ru.more.play"))
        assertEquals(listOf("VLC", "Okko"), dock.map { it.label })
    }

    @Test
    fun aSavedDockOfOnlyGoneAppsFallsBackToTheUsualOne() {
        assertEquals(DockApps.dock(DockApps.order(tv)), DockApps.dock(DockApps.order(tv), saved = listOf("gone.app")))
    }

    @Test
    fun addRemoveAndMove() {
        val dock = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c", "d"), DockApps.add(dock, "d"))
        assertEquals(dock, DockApps.add(dock, "b"))
        assertEquals(listOf("a", "c"), DockApps.remove(dock, "b"))
        assertEquals(listOf("a"), DockApps.remove(listOf("a"), "a"))
        assertEquals(listOf("b", "a", "c"), DockApps.move(dock, "a", +1))
        assertEquals(listOf("a", "c", "b"), DockApps.move(dock, "c", -1))
        assertEquals(dock, DockApps.move(dock, "a", -1))
        assertEquals(dock, DockApps.move(dock, "c", +1))
    }

    @Test
    fun filmAppsGetTmdbAndToolsTheirLogo() {
        val video = android.content.pm.ApplicationInfo.CATEGORY_VIDEO
        assertTrue(WatchApps.isWatchApp("ru.more.play", -1))
        assertTrue(WatchApps.isWatchApp("ru.kinopoisk.tv", -1))
        assertTrue(WatchApps.isWatchApp("some.film.service", video))
        assertFalse(WatchApps.isWatchApp("org.videolan.vlc", video))
        assertFalse(WatchApps.isWatchApp("io.github.romanvht.byedpi", -1))
        assertFalse(WatchApps.isWatchApp("su.happ.proxyutility", -1))
    }

    @Test
    fun theDockHoldsSevenAtMost() {
        val seven = (1..7).map { "p$it" }
        assertTrue(DockApps.isFull(seven))
        assertEquals(seven, DockApps.add(seven, "p8"))
        val every = (1..9).map { DockApp("p$it", "P$it", 0) }
        assertEquals(7, DockApps.dock(every, saved = every.map { it.packageName }).size)
    }
}
