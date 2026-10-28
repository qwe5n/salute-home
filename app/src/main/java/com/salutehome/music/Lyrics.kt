package com.salutehome.music

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.salutehome.home.Http
import com.salutehome.home.HttpStatusException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/**
 * A song's words from LRCLIB (lrclib.net: free, open, no key), timed line by line when it has them.
 * One lookup per song, kept for the session; the answer comes back on the main thread.
 */
internal object Lyrics {
    /** A line and when it starts ([atMs] -1 for words with no timing). */
    class Line(val atMs: Long, val text: String)

    /** What we know about a song's words. [synced] false: plain lines, no timing. Empty: none found. */
    class Words(val lines: List<Line>, val synced: Boolean, val instrumental: Boolean = false)

    private val cache = LinkedHashMap<String, Words>()
    private val pending = HashMap<String, MutableList<(Words) -> Unit>>()
    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy { Handler(HandlerThread("lyrics").apply { start() }.looper) }
    private val http = Http(connectTimeoutMs = 6_000, readTimeoutMs = 8_000)

    fun key(title: String, artist: String) = "$artist\u0000$title"

    fun cached(title: String, artist: String): Words? = cache[key(title, artist)]

    /** Looks [title] by [artist] up (once), then calls [done] on the main thread. */
    fun find(title: String, artist: String, durationMs: Long, done: (Words) -> Unit) {
        val k = key(title, artist)
        cache[k]?.let { done(it); return }
        // Already being looked up: wait for that answer rather than asking again.
        pending[k]?.let { it += done; return }
        pending[k] = mutableListOf(done)
        worker.post {
            val words = try {
                lookUp(title, artist, durationMs)
            } catch (e: Exception) {
                Log.w("SaluteHome", "lyrics for $artist - $title: $e")
                null
            }
            Log.i("SaluteHome", "lyrics for $artist - $title: ${words?.lines?.size ?: "failed"} lines, synced=${words?.synced}")
            main.post {
                // A failed lookup isn't remembered: the next time the screen opens it tries again.
                if (words != null) {
                    cache[k] = words
                    if (cache.size > 40) cache.remove(cache.keys.first())
                }
                val answer = words ?: Words(emptyList(), synced = false)
                pending.remove(k)?.forEach { it(answer) }
            }
        }
    }

    private fun lookUp(title: String, artist: String, durationMs: Long): Words {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        // Search, not /get: /get waits on LRCLIB's own outside lookups and often answers 503 after 20 s.
        val searchUrl = "$BASE/search?track_name=${enc(title)}&artist_name=${enc(artist)}"
        // LRCLIB answers 503 now and then when busy: up to three tries, a little apart.
        var hits: JSONArray? = null
        for (attempt in 1..3) {
            try {
                hits = JSONArray(http.getString(searchUrl, HEADERS))
                break
            } catch (e: HttpStatusException) {
                if (e.code < 500 || attempt == 3) throw e
                Thread.sleep(1500L * attempt)
            }
        }
        hits!!
        // The timed hit closest in length to the song (a live or extended cut drifts off the words).
        val seconds = durationMs / 1000.0
        var best: Words? = null
        var bestOff = Double.MAX_VALUE
        var plain: Words? = null
        for (i in 0 until hits.length()) {
            val o = hits.getJSONObject(i)
            val w = words(o)
            if (w.synced) {
                val off = if (seconds > 0) abs(o.optDouble("duration", 0.0) - seconds) else i.toDouble()
                if (off < bestOff) {
                    best = w
                    bestOff = off
                }
            } else if (plain == null && (w.lines.isNotEmpty() || w.instrumental)) {
                plain = w
            }
        }
        return best ?: plain ?: Words(emptyList(), synced = false)
    }

    private fun words(o: JSONObject): Words {
        val synced = o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") }.orEmpty()
        if (synced.isNotBlank()) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Words(lines, synced = true)
        }
        val plain = o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") }.orEmpty()
        if (plain.isNotBlank()) return Words(plain.lines().map { Line(-1, it.trim()) }, synced = false)
        return Words(emptyList(), synced = false, instrumental = o.optBoolean("instrumental"))
    }

    private val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")

    /** "[01:02.34] words" lines, sorted by time; a line with several stamps is used at each. */
    internal fun parseLrc(text: String): List<Line> {
        val out = ArrayList<Line>()
        for (raw in text.lines()) {
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val words = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val ms = m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong()
                out += Line(ms, words)
            }
        }
        out.sortBy { it.atMs }
        return out
    }

    private const val BASE = "https://lrclib.net/api"
    private val HEADERS = mapOf("Lrclib-Client" to "SaluteHome (Android TV home screen)")
}
