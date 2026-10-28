package com.salutehome.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The shelf's data: TVmaze's web schedule for the week to 27 October 2026 (Russia's half and the global
 * half, trimmed to the fields read), the shows' picture lists, and TMDB's shape for the keyed build.
 */
class ShelfDataTest {
    private val prime = "com.amazon.amazonvideo.livingroom"
    private val netflix = "com.netflix.ninja"
    private val player = Streaming.Service("Player", "com.example.player")

    private fun fixture(name: String) = javaClass.getResource("/home/$name")!!.readText()

    private val week: List<Tvmaze.Candidate> = (21..27).flatMap { day ->
        listOf("ru", "global").flatMap { half -> Tvmaze.parseSchedule(fixture("tvmaze-web-2026-10-$day-$half.json")) }
    }

    private fun backgroundOf(c: Tvmaze.Candidate): String? =
        javaClass.getResource("/home/tvmaze-images-${c.showId}.json")?.readText()?.let { Tvmaze.background(it) }

    @Test
    fun aShowOpensItsOwnAppElseTheShelfApp() {
        val ranked = Tvmaze.rank(week, setOf(netflix), player)
        val own = ranked.first { it.name == "Nobody Wants This" }
        assertEquals(netflix, own.packageName)
        assertEquals("Netflix", own.app)
        // Prime Video isn't installed: its show opens the shelf's app, and still says where it streams.
        val other = ranked.first { it.name == "The Terminal List" }
        assertEquals(player.packageName, other.packageName)
        assertEquals("Prime Video", other.service)
        assertTrue(ranked.filter { it.service != "Netflix" }.all { it.packageName == player.packageName })
    }

    @Test
    fun withNoAppToOpenOnlyInstalledServicesCount() {
        assertTrue(Tvmaze.rank(week, emptySet(), null).isEmpty())
        assertTrue(Tvmaze.rank(week, setOf(prime, netflix), null).all { it.service == "Prime Video" || it.service == "Netflix" })
    }

    @Test
    fun ranksTheWeekByWhatsWatched() {
        val ranked = Tvmaze.rank(week, emptySet(), player)
        // Out: live sport, news, and shows in neither English nor Russian.
        assertEquals(
            listOf("VisionQuest", "Tulsa King", "Dark Matter", "MobLand"),
            ranked.take(4).map { it.name },
        )
        assertTrue(ranked.none { it.type == "Sports" || it.type == "News" })
        assertTrue(ranked.all { it.language == null || it.language == "English" || it.language == "Russian" })
        // Russia's own services are in it too.
        assertTrue(ranked.any { it.language == "Russian" })
        // One entry a show, however many episodes it had.
        assertEquals(ranked.size, ranked.map { it.showId }.toSet().size)
    }

    @Test
    fun picksThreeShowsTheWideOneLandscape() {
        val shelf = Tvmaze.pick(Tvmaze.rank(week, emptySet(), player), ::backgroundOf)
        assertEquals(listOf("VisionQuest", "Tulsa King", "Dark Matter"), shelf.map { it.title })
        assertEquals("https://static.tvmaze.com/uploads/images/original_untouched/636/1592331.jpg", shelf[0].image)
        assertTrue(shelf[1].image.contains("medium_portrait"))
        assertEquals("Series · Action · Season 1 · Disney+", shelf[0].meta)
        assertEquals("Series · Drama · Paramount+", shelf[1].meta)
        assertEquals(listOf("Player", "Player", "Player"), shelf.map { it.app })
        assertEquals(List(3) { player.packageName }, shelf.map { it.packageName })
    }

    @Test
    fun withNoLandscapePictureTheBestPosterFillsTheWideSlot() {
        val ranked = Tvmaze.rank(week, emptySet(), player)
        val shelf = Tvmaze.pick(ranked, { null })
        assertEquals("VisionQuest", shelf[0].title)
        assertEquals(ranked[0].posterLarge, shelf[0].image)
    }

    @Test
    fun fewerThanThreeShowsIsNoShelf() {
        val day = Tvmaze.parseSchedule(fixture("tvmaze-web-2026-10-27-global.json"))
        val ranked = Tvmaze.rank(day, setOf(prime, netflix), null)
        assertEquals(1, ranked.size) // one show on this TV's apps that day, and no shelf app
        assertTrue(Tvmaze.pick(ranked, ::backgroundOf).isEmpty())
    }

    @Test
    fun findsALandscapePicture() {
        assertNull(Tvmaze.background("[]"))
        assertNull(Tvmaze.background("not json"))
        // Four backgrounds, none main: the first 1920 one, not the larger ones.
        assertEquals(
            "https://static.tvmaze.com/uploads/images/original_untouched/431/1078582.jpg",
            Tvmaze.background(fixture("tvmaze-images-59344.json")),
        )
        val main = """[{"type":"background","main":false,"resolutions":{"original":{"url":"https://a/small.jpg","width":1280}}},
            {"type":"background","main":true,"resolutions":{"original":{"url":"https://a/main.jpg","width":3840}}}]"""
        assertEquals("https://a/main.jpg", Tvmaze.background(main))
    }

    @Test(expected = IOException::class)
    fun refusesABrokenSchedule() {
        Tvmaze.parseSchedule("<html>")
    }

    @Test
    fun theShelfCacheRoundTrips() {
        val data = ShelfData(
            ShelfData.Source.TVMAZE,
            Tvmaze.pick(Tvmaze.rank(week, emptySet(), player), ::backgroundOf),
        )
        assertEquals(data, ShelfData.fromJson(data.toJson()))
    }

    @Test(expected = IOException::class)
    fun aBrokenCacheIsRefused() {
        ShelfData.fromJson("{\"source\":\"NOPE\",\"items\":[]}")
    }

    @Test
    fun tmdbPicksTheFeaturedBackdropThenTwoPosters() {
        val discover = JSONObject(
            """{"results":[
              {"id":1,"title":"Quiet","popularity":10.0,"genre_ids":[18],"backdrop_path":null,"poster_path":"/q.jpg"},
              {"id":2,"title":"Loud","popularity":99.0,"genre_ids":[28],"backdrop_path":null,"poster_path":"/l.jpg"},
              {"id":3,"title":"Wide","popularity":50.0,"genre_ids":[],"backdrop_path":"/w.jpg","poster_path":"/wp.jpg"},
              {"id":4,"title":"Bare","popularity":70.0,"genre_ids":[],"backdrop_path":null,"poster_path":null}
            ]}""",
        )
        val titles = Tmdb.titles(discover, "movie")
        assertEquals(28, titles[1].genreId)
        assertNull(titles[3].poster)
    }
}
