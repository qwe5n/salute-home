package com.salutehome.home

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** One poster on the shelf: what it is, its picture, and the app OK opens. */
data class ShelfItem(
    val title: String,
    val meta: String,
    val image: String,
    val app: String,
    val packageName: String,
    /** 0 to 1 for something being watched (a progress bar), -1 for none. Not kept on disk. */
    val progress: Float = -1f,
    /** The app's own intent for this title (TV listings' intent_uri); empty opens the app. */
    val intent: String = "",
)

/** The shelf's three posters, wide first, and where they came from. */
data class ShelfData(val source: ShelfData.Source, val items: List<ShelfItem>) {
    enum class Source { TVMAZE, TMDB }

    fun toJson(): String = JSONObject()
        .put("source", source.name)
        .put("items", JSONArray(items.map {
            JSONObject().put("title", it.title).put("meta", it.meta).put("image", it.image)
                .put("app", it.app).put("package", it.packageName)
        }))
        .toString()

    companion object {
        fun fromJson(json: String): ShelfData = try {
            val root = JSONObject(json)
            val items = root.getJSONArray("items")
            ShelfData(
                Source.valueOf(root.getString("source")),
                List(items.length()) {
                    val o = items.getJSONObject(it)
                    ShelfItem(o.getString("title"), o.getString("meta"), o.getString("image"), o.getString("app"), o.getString("package"))
                },
            )
        } catch (e: JSONException) {
            throw IOException("Unreadable shelf cache", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unreadable shelf cache", e)
        }
    }
}

/** The streaming apps the shelf can point at, by the names TVmaze and TMDB give them. */
object Streaming {
    class Service(val name: String, val packageName: String, private val match: Regex? = null) {
        fun matches(channel: String) = match?.containsMatchIn(channel) == true
    }

    val SERVICES = listOf(
        Service("Prime Video", "com.amazon.amazonvideo.livingroom", Regex("prime video", RegexOption.IGNORE_CASE)),
        Service("Netflix", "com.netflix.ninja", Regex("^netflix$", RegexOption.IGNORE_CASE)),
        Service("Okko", "ru.more.play", Regex("^okko$", RegexOption.IGNORE_CASE)),
    )

    fun forChannel(channel: String?): Service? = channel?.let { c -> SERVICES.firstOrNull { it.matches(c) } }

    fun byName(name: String): Service? = SERVICES.firstOrNull { it.name == name }
}

/**
 * New on your apps, from TVmaze's streaming schedule (no key needed; its licence asks for credit, which
 * the shelf's head gives). The web schedule comes in two halves: country RU holds Russia's own services,
 * and the global half holds the worldwide ones. A show on a service whose app is installed (Netflix, Prime
 * Video, Okko) opens that app, any other the shelf's app (shelf.app, or the first app for films and series
 * in the dock). The shelf looks back over the last week and shows the most-watched three (TVmaze's
 * weight), newest first among equals.
 */
object Tvmaze {
    /** A show with a new episode on one of the apps. */
    data class Candidate(
        val showId: Int,
        val name: String,
        val service: String,
        val type: String,
        val genres: List<String>,
        val language: String?,
        val weight: Int,
        val airstamp: String,
        val season: Int,
        val poster: String?,
        val posterLarge: String?,
        /** The app it opens, by name and package, set by [rank]; not kept on disk. */
        val app: String = "",
        val packageName: String = "",
    )

    /** The days the shelf looks back over, today included. */
    const val DAYS = 7

    fun scheduleUrl(date: String, russia: Boolean) =
        "https://api.tvmaze.com/schedule/web?date=$date&country=${if (russia) "RU" else ""}"

    fun imagesUrl(showId: Int) = "https://api.tvmaze.com/shows/$showId/images"

    // Live sport and news are regional (Thursday Night Football is a US stream): not for this shelf.
    private val SKIP_TYPES = setOf("Sports", "News")

    // The shows this home's household watches: English and Russian.
    private val LANGUAGES = setOf("English", "Russian")

    /** The episodes in one day's web schedule, as candidates; [Candidate.service] is where it streams. */
    fun parseSchedule(json: String): List<Candidate> {
        val episodes = try {
            JSONArray(json)
        } catch (e: JSONException) {
            throw IOException("TVmaze sent something unexpected", e)
        }
        val out = ArrayList<Candidate>()
        for (i in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(i) ?: continue
            val show = episode.optJSONObject("_embedded")?.optJSONObject("show") ?: continue
            val channel = show.optJSONObject("webChannel")?.optString("name")
                ?: show.optJSONObject("network")?.optString("name")
            val service = Streaming.forChannel(channel)?.name ?: channel?.takeIf { it.isNotEmpty() } ?: continue
            val image = show.optJSONObject("image")
            out += Candidate(
                showId = show.optInt("id"),
                name = show.optString("name").trim(),
                service = service,
                type = show.optString("type"),
                genres = show.optJSONArray("genres")?.let { g -> List(g.length()) { g.optString(it) } } ?: emptyList(),
                language = show.optString("language").takeIf { it.isNotEmpty() && it != "null" },
                weight = show.optInt("weight"),
                airstamp = episode.optString("airstamp"),
                season = episode.optInt("season"),
                poster = image?.optString("medium")?.takeIf { it.startsWith("http") },
                posterLarge = image?.optString("original")?.takeIf { it.startsWith("http") },
            )
        }
        return out
    }

    /**
     * The shows worth a poster, best first: with a picture, not live sport or news, in a language this home
     * watches, each with the app it opens: its service's own if installed, else [shelfApp] (none: dropped);
     * one entry per show; the most-watched first, then the newest.
     */
    fun rank(candidates: List<Candidate>, installed: Set<String>, shelfApp: Streaming.Service?): List<Candidate> = candidates
        .filter { c ->
            c.poster != null && c.name.isNotEmpty() && c.showId > 0 &&
                c.type !in SKIP_TYPES &&
                (c.language == null || c.language in LANGUAGES)
        }
        .mapNotNull { c ->
            val app = Streaming.byName(c.service)?.takeIf { it.packageName in installed } ?: shelfApp
            app?.let { c.copy(app = it.name, packageName = it.packageName) }
        }
        .sortedWith(compareByDescending<Candidate> { it.weight }.thenByDescending { it.airstamp })
        .distinctBy { it.showId }

    /**
     * A landscape picture TVmaze has for a show ("background"), or null: the show's main one if it has one,
     * otherwise the smallest (they come at 1920 or 3840 wide, and the wide slot is 416 px: a 4K picture
     * would only cost download time).
     */
    fun background(imagesJson: String): String? {
        val images = try {
            JSONArray(imagesJson)
        } catch (e: JSONException) {
            return null
        }
        var best: String? = null
        var bestMain = false
        var bestWidth = Int.MAX_VALUE
        for (i in 0 until images.length()) {
            val image = images.optJSONObject(i) ?: continue
            if (image.optString("type") != "background") continue
            val original = image.optJSONObject("resolutions")?.optJSONObject("original") ?: continue
            val url = original.optString("url")
            if (!url.startsWith("http")) continue
            val main = image.optBoolean("main")
            val width = original.optInt("width", Int.MAX_VALUE).takeIf { it > 0 } ?: Int.MAX_VALUE
            if (best == null || (main && !bestMain) || (main == bestMain && width < bestWidth)) {
                best = url
                bestMain = main
                bestWidth = width
            }
        }
        return best
    }

    /**
     * Three different shows: the wide slot takes the best one with a landscape picture (asking
     * [backgroundOf] for the first few, in order), the two tall ones the best two left. With no landscape
     * picture at all, the best show's poster fills the wide slot.
     */
    fun pick(ranked: List<Candidate>, backgroundOf: (Candidate) -> String?, tries: Int = 5): List<ShelfItem> {
        if (ranked.size < 3) return emptyList()
        var wide: Candidate? = null
        var wideImage: String? = null
        for (c in ranked.take(tries)) {
            val bg = backgroundOf(c) ?: continue
            wide = c
            wideImage = bg
            break
        }
        if (wide == null) {
            wide = ranked.first()
            wideImage = wide.posterLarge ?: wide.poster
        }
        val tall = ranked.filter { it !== wide }.take(2)
        return listOf(item(wide, wideImage!!, wide = true)) + tall.map { item(it, it.poster!!, wide = false) }
    }

    private fun item(c: Candidate, image: String, wide: Boolean) =
        ShelfItem(c.name, meta(c, wide), image, c.app, c.packageName)

    /** The line under a poster, as the prototype's "Series · Drama · Prime Video". */
    fun meta(c: Candidate, wide: Boolean): String {
        val kind = when (c.type) {
            "Scripted", "" -> "Series"
            "Talk Show" -> "Talk show"
            "Game Show" -> "Game show"
            "Award Show" -> "Award show"
            "Panel Show" -> "Panel show"
            else -> c.type
        }
        val genre = c.genres.firstOrNull { it.isNotEmpty() && !it.equals(kind, ignoreCase = true) }
        val season = if (wide && c.season > 0) "Season ${c.season}" else null
        return listOfNotNull(kind, genre, season, c.service).joinToString(" · ")
    }
}

/**
 * Popular films and series, from TMDB, when the build has a key (local.properties tmdb.key).
 */
object Tmdb {
    fun get(http: Http, key: String, path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val query = StringBuilder()
        for ((k, v) in params) query.append(if (query.isEmpty()) '?' else '&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        // A v4 read token (a JWT) goes in a header; a v3 key goes in the query.
        val headers = mutableMapOf("Accept" to "application/json")
        if (key.startsWith("eyJ")) headers["Authorization"] = "Bearer $key"
        else query.append(if (query.isEmpty()) '?' else '&').append("api_key=").append(URLEncoder.encode(key, "UTF-8"))
        val body = http.getString("https://api.themoviedb.org/3$path$query", headers)
        return try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("TMDB sent something unexpected for $path", e)
        }
    }

    /** A title from a TMDB list, ready to be picked. */
    data class Title(
        val id: Int,
        val type: String,
        val title: String,
        val year: String = "",
        val popularity: Double,
        val genreId: Int?,
        val backdrop: String?,
        val poster: String?,
    )

    fun titles(discover: JSONObject, type: String): List<Title> {
        val results = discover.optJSONArray("results") ?: return emptyList()
        return List(results.length()) { results.optJSONObject(it) }.filterNotNull().filter { r ->
            type.isNotEmpty() || r.optString("media_type") == "movie" || r.optString("media_type") == "tv"
        }.map { r ->
            val t = type.ifEmpty { r.optString("media_type") }
            Title(
                id = r.optInt("id"),
                type = t,
                title = r.optString(if (t == "movie") "title" else "name").ifEmpty { r.optString("title", r.optString("name")) },
                year = r.optString(if (t == "movie") "release_date" else "first_air_date").take(4),
                popularity = r.optDouble("popularity", 0.0),
                genreId = r.optJSONArray("genre_ids")?.takeIf { it.length() > 0 }?.optInt(0),
                backdrop = r.optString("backdrop_path").takeIf { it.startsWith("/") },
                poster = r.optString("poster_path").takeIf { it.startsWith("/") },
            )
        }
    }

    /**
     * What's popular this week, for a Russian home: TMDB's weekly trending films and series in Russian,
     * the first five with a backdrop (the shelf's posters are all landscape). Each opens [app], the shelf's
     * app; the line under it says what it is, its genre and year.
     */
    fun fetch(http: Http, key: String, app: Streaming.Service?): List<ShelfItem> {
        val ru = mapOf("language" to "ru-RU")
        val trending = titles(get(http, key, "/trending/all/week", ru), "")
        val genres = HashMap<Int, String>()
        for (type in listOf("movie", "tv")) {
            val list = get(http, key, "/genre/$type/list", ru).optJSONArray("genres") ?: continue
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { genres[it.optInt("id")] = it.optString("name") }
        }
        return trending.filter { it.backdrop != null && it.title.isNotEmpty() }.take(ShelfView.COUNT).map { r ->
            ShelfItem(
                title = r.title,
                meta = listOf(if (r.type == "tv") "Сериал" else "Фильм", genres[r.genreId]?.replaceFirstChar { it.uppercase() } ?: "", r.year)
                    .filter { it.isNotEmpty() }.joinToString(" · "),
                image = "https://image.tmdb.org/t/p/w780${r.backdrop}",
                app = app?.name ?: "",
                packageName = app?.packageName ?: "",
            )
        }
    }
}
