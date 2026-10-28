package com.salutehome.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The YouTube shelf: the last video left half-watched (with how far in) and the first four of the
 * owner's home feed, from YouTube's own TV API (the one the YouTube TV app and SmartTube use; unofficial,
 * so it may change under us). Sign-in is the TV way: a code shown on the shelf, typed on a phone at
 * google.com/device; no password ever reaches the TV. The tokens stay in this app's private preferences.
 * Blocking calls: run them off the main thread.
 */
class YouTube(private val context: Context, private val http: Http) {
    private val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)

    val signedIn: Boolean get() = prefs.getString(REFRESH, null) != null

    /** A code to type at [url], good until [until] (ms); poll [deviceCode] every [everyMs]. */
    data class Code(val user: String, val url: String, val deviceCode: String, val everyMs: Long, val until: Long)

    fun requestCode(now: Long): Code {
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("scope", SCOPE)
            .put("device_id", deviceId())
            .put("device_model", "ytlr::")
        val o = JSONObject(http.postJson("$BASE/o/oauth2/device/code", body.toString()))
        return Code(
            user = o.getString("user_code"),
            url = o.optString("verification_url", "https://www.google.com/device"),
            deviceCode = o.getString("device_code"),
            everyMs = o.optLong("interval", 5) * 1000,
            until = now + o.optLong("expires_in", 1800) * 1000,
        )
    }

    /** One poll: true once the owner has typed the code and the tokens are saved, false while still waiting. */
    fun poll(code: Code, now: Long): Boolean {
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("client_secret", CLIENT_SECRET)
            .put("code", code.deviceCode)
            .put("grant_type", "http://oauth.net/grant_type/device/1.0")
        val o = try {
            JSONObject(http.postJson("$BASE/o/oauth2/token", body.toString()))
        } catch (e: HttpStatusException) {
            // Some answers come as 400 with the same "authorization_pending" error.
            if (e.message?.contains("pending") == true || e.message?.contains("slow_down") == true) return false
            throw e
        }
        if (o.has("error")) {
            val error = o.getString("error")
            if (error == "authorization_pending" || error == "slow_down") return false
            throw IOException("youtube sign-in: $error")
        }
        save(o, now)
        return true
    }

    fun signOut() {
        prefs.edit().clear().apply()
    }

    /** The shelf's rows: the half-watched video first (if any), then four from the home feed. */
    fun rows(now: Long): List<TvRows.Row> {
        val token = accessToken(now)
        val history = tiles(browse("FEhistory", token), "history")
        val feed = tiles(browse("FEwhat_to_watch", token), "feed")
        val resume = history.firstOrNull { it.percent in 1..94 }
        val picks = feed.filter { it.id != resume?.id }.take(if (resume != null) 4 else 5)
        Log.i(TAG, "youtube: history ${history.size}, feed ${feed.size}, resume ${resume != null}")
        return listOfNotNull(resume?.toRow(watching = true)) + picks.map { it.toRow(watching = false) }
    }

    // ---- Tokens -------------------------------------------------------------------------------------------

    private fun save(o: JSONObject, now: Long) {
        prefs.edit()
            .putString(ACCESS, o.getString("access_token"))
            .putLong(EXPIRES, now + o.optLong("expires_in", 3600) * 1000)
            .apply {
                o.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { putString(REFRESH, it) }
            }
            .apply()
    }

    private fun accessToken(now: Long): String {
        val access = prefs.getString(ACCESS, null)
        if (access != null && prefs.getLong(EXPIRES, 0) - now > 60_000) return access
        val refresh = prefs.getString(REFRESH, null) ?: throw IOException("youtube: not signed in")
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("client_secret", CLIENT_SECRET)
            .put("refresh_token", refresh)
            .put("grant_type", "refresh_token")
        val o = try {
            JSONObject(http.postJson("$BASE/o/oauth2/token", body.toString()))
        } catch (e: HttpStatusException) {
            // The owner signed this TV out in their Google account: back to the sign-in card.
            if (e.code == 400 || e.code == 401) signOut()
            throw e
        }
        if (o.has("error")) {
            if (o.getString("error") == "invalid_grant") signOut()
            throw IOException("youtube token: ${o.getString("error")}")
        }
        save(o, now)
        return o.getString("access_token")
    }

    private fun deviceId(): String =
        prefs.getString(DEVICE, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(DEVICE, it).apply() }

    // ---- The TV API ---------------------------------------------------------------------------------------

    private fun browse(id: String, token: String): JSONObject {
        val client = JSONObject()
            .put("clientName", "TVHTML5")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "ru")
            .put("gl", "RU")
        val body = JSONObject().put("context", JSONObject().put("client", client)).put("browseId", id)
        val text = http.postJson(
            "$BASE/youtubei/v1/browse",
            body.toString(),
            mapOf("Authorization" to "Bearer $token", "User-Agent" to USER_AGENT),
        )
        return JSONObject(text)
    }

    private class Tile(val id: String, val title: String, val channel: String, val length: String, val image: String, val percent: Int) {
        fun toRow(watching: Boolean): TvRows.Row {
            val seconds = seconds(length)
            val left = if (seconds > 0) ((seconds * (100 - percent) / 100) / 60).coerceAtLeast(1) else 0
            val meta = when {
                watching && left > 0 -> listOf(channel, "осталось $left мин").filter { it.isNotEmpty() }.joinToString(" · ")
                else -> listOf(channel, length).filter { it.isNotEmpty() }.joinToString(" · ")
            }
            val at = if (watching && seconds > 0) "&t=${seconds * percent / 100}s" else ""
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id$at"))
                .setPackage(PACKAGE)
            return TvRows.Row(
                packageName = PACKAGE,
                title = title,
                meta = meta,
                image = image,
                intentUri = intent.toUri(Intent.URI_INTENT_SCHEME),
                progress = if (watching) percent / 100f else -1f,
                engaged = 0L,
            )
        }
    }

    /** Every video tile in [root], in order, found wherever the TV layout of the day keeps them. */
    private fun tiles(root: JSONObject, name: String): List<Tile> {
        val out = ArrayList<Tile>()
        val seen = HashSet<String>()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    val tile = o.optJSONObject("tileRenderer")
                    if (tile != null) tile(tile)?.let { if (seen.add(it.id)) out += it }
                    else for (k in o.keys()) walk(o.opt(k))
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        walk(root)
        // Nothing found: keep the answer to look at (adb pull), the shape may have changed.
        if (out.isEmpty()) try {
            context.getExternalFilesDir(null)?.let { File(it, "youtube-$name.json").writeText(root.toString()) }
        } catch (e: Exception) {
        }
        return out
    }

    private fun tile(t: JSONObject): Tile? {
        val type = t.optString("contentType")
        if (type.isNotEmpty() && type != "TILE_CONTENT_TYPE_VIDEO") return null
        val id = t.optString("contentId").ifEmpty {
            t.optJSONObject("onSelectCommand")?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
        }
        if (id.isEmpty()) return null
        val meta = t.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
        val title = text(meta?.optJSONObject("title")).ifEmpty { return null }
        val lines = meta?.optJSONArray("lines")
        val channel = lines?.optJSONObject(0)?.optJSONObject("lineRenderer")?.optJSONArray("items")
            ?.optJSONObject(0)?.optJSONObject("lineItemRenderer")?.optJSONObject("text")?.let { text(it) } ?: ""
        val header = t.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
        var length = ""
        var percent = 0
        header?.optJSONArray("thumbnailOverlays")?.let { overlays ->
            for (i in 0 until overlays.length()) {
                val o = overlays.optJSONObject(i) ?: continue
                o.optJSONObject("thumbnailOverlayTimeStatusRenderer")?.let { length = text(it.optJSONObject("text")) }
                o.optJSONObject("thumbnailOverlayResumePlaybackRenderer")?.let { percent = it.optInt("percentDurationWatched") }
            }
        }
        // YouTube's own 16:9 still at 1280×720; the shelf cuts it to the poster.
        return Tile(id, title, channel, length, "https://i.ytimg.com/vi/$id/hq720.jpg", percent)
    }

    companion object {
        const val PACKAGE = "com.google.android.youtube.tv"

        /** A poster that, pressed, asks for a sign-in code (HomeScreen handles it). */
        const val SIGN_IN = "salutehome:youtube-sign-in"

        private const val TAG = "SaluteHome"
        private const val BASE = "https://www.youtube.com"
        private const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
        private const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
        private const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
        private const val CLIENT_VERSION = "7.20250219.14.00"
        private const val USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
        private const val ACCESS = "access"
        private const val REFRESH = "refresh"
        private const val EXPIRES = "expires"
        private const val DEVICE = "device"

        private fun text(o: JSONObject?): String {
            if (o == null) return ""
            o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
            val runs = o.optJSONArray("runs") ?: return ""
            return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        }

        /** "41:10" or "1:02:03" in seconds; 0 for anything else ("LIVE"). */
        private fun seconds(length: String): Int {
            val parts = length.split(':').map { it.trim().toIntOrNull() ?: return 0 }
            return parts.fold(0) { acc, p -> acc * 60 + p }
        }
    }
}
