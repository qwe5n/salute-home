package com.salutehome.media

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.salutehome.core.NowPlaying
import com.salutehome.core.NowPlayingHub

/**
 * Follows a set of media sessions and publishes what's playing to NowPlayingHub: the session that is
 * playing (else the most recent one that is paused), republished whenever its song, state or position
 * changes. Two things feed it the sessions: notification access (NowPlayingListener) where the TV allows
 * it, and on this low-RAM TV, which doesn't, the session tokens in music apps' notifications
 * (SessionTokens, through the Input key's accessibility service).
 *
 * Everything system-facing runs on one background thread of its own: the callbacks, scaling the art.
 * Only the finished NowPlaying crosses to the main thread.
 */
class SessionFollower(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null

    // Read on the worker to drop late callbacks once the listener has been let go.
    @Volatile
    private var worker: Handler? = null

    // ---- Touched on the worker thread only ----
    private var audio: AudioManager? = null
    private var watches: List<Watch> = emptyList()
    private var contentTypes: List<Int> = emptyList()
    private var art: Bitmap? = null
    private var artSignature = 0L
    private var artSong = ""
    private val labels = HashMap<String, String>()

    /**
     * One watcher on every active session, not just the followed one: another app starting to play is what
     * moves the island over to it. Each keeps the state and metadata its callbacks bring, so an update
     * doesn't ask every session again (the metadata carries the cover, a fresh copy each time it's read).
     */
    private inner class Watch(val controller: MediaController) : MediaController.Callback() {
        var state: PlaybackState? = controller.playbackState
        private var meta: MediaMetadata? = null
        private var metaRead = false

        val metadata: MediaMetadata?
            get() {
                if (!metaRead) {
                    meta = controller.metadata
                    metaRead = true
                }
                return meta
            }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            this.state = state
            update()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            meta = metadata
            metaRead = true
            update()
        }

        override fun onSessionDestroyed() {
            state = null
            update()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            contentTypes = typesOf(configs)
            update()
        }
    }

    fun start() {
        if (worker != null) return
        val t = HandlerThread("now-playing", Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        val h = Handler(t.looper)
        thread = t
        worker = h
        h.post {
            context.getSystemService(AudioManager::class.java)?.let { am ->
                am.registerAudioPlaybackCallback(playbackCallback, h)
                audio = am
                contentTypes = typesOf(am.activePlaybackConfigurations)
            }
        }
    }

    /** Follows [list] from now on (from any thread). */
    fun follow(list: List<MediaController>?) {
        worker?.post { followNow(list) }
    }

    /** The worker's own handler, for registering system listeners on it. */
    val handler: Handler? get() = worker

    /** Lets everything go, then says nothing is playing (after any update still queued on the worker). */
    fun stop() {
        val h = worker ?: return
        val t = thread
        worker = null
        thread = null
        NowPlayingControls.attach(null, null)
        h.post {
            audio?.unregisterAudioPlaybackCallback(playbackCallback)
            audio = null
            for (w in watches) w.controller.unregisterCallback(w)
            watches = emptyList()
            art = null
            main.post { NowPlayingHub.publish(null) }
            t?.quitSafely()
        }
    }

    private fun followNow(list: List<MediaController>?) {
        val h = worker ?: return
        for (w in watches) w.controller.unregisterCallback(w)
        watches = try {
            list.orEmpty().map { c -> Watch(c).also { c.registerCallback(it, h) } }
        } catch (e: RuntimeException) {
            // A session went while we looked: its removal brings another list.
            emptyList()
        }
        update()
    }

    private fun update() {
        val h = worker ?: return
        val now = try {
            val at = MediaPick.pick(watches.map { it.state?.state ?: MediaPick.STATE_NONE })
            val w = at?.let { watches[it] }
            NowPlayingControls.attach(w?.controller, if (w != null) h else null)
            w?.let { build(it) }
        } catch (e: RuntimeException) {
            // A session died mid-read: the destroyed callback brings the next update.
            null
        }
        main.post { if (worker != null) NowPlayingHub.publish(now) }
    }

    private fun build(w: Watch): NowPlaying? {
        val state = w.state ?: return null
        if (!MediaPick.shows(state.state)) return null
        val meta = w.metadata
        val pkg = w.controller.packageName
        val title = meta.text(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: labelOf(pkg)
        val artist = meta.text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val speed = when (state.state) {
            PlaybackState.STATE_PLAYING -> if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> state.playbackSpeed
            // Buffering or skipping: the position holds still until the song moves again.
            else -> 0f
        }
        return NowPlaying(
            packageName = pkg,
            title = title,
            artist = artist,
            art = artOf(meta),
            playing = MediaPick.playing(state.state),
            positionMs = state.position,
            positionAtMs = state.lastPositionUpdateTime,
            speed = speed,
            durationMs = maxOf(0L, meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L),
            isMusic = MediaPick.isMusic(pkg, contentTypes),
        )
    }

    private fun MediaMetadata?.text(key: String): String? = this?.getString(key)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The cover at most 256 px a side. Apps resend their metadata often, each time with a fresh copy of
     * the same picture, so a cheap signature (size and a few pixels) keeps the scaled one we already have.
     */
    private fun artOf(meta: MediaMetadata?): Bitmap? {
        // Not the display icon: Spotify TV sends every update twice, once with the cover and once without,
        // where the icon is its own logo, and the card flashed the logo on each song or volume change.
        val song = "${meta.text(MediaMetadata.METADATA_KEY_ARTIST)}\u0000${meta.text(MediaMetadata.METADATA_KEY_TITLE)}"
        val src = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: fromUri(meta)
        // The same song for a moment without its cover keeps the one it had.
        if (src == null && song == artSong && art != null) return art
        artSong = song
        if (src == null) {
            art = null
            artSignature = 0L
            return null
        }
        return try {
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val sig = signatureOf(soft)
            if (sig == artSignature && art != null) return art
            val (w, h) = MediaPick.fit(soft.width, soft.height)
            val scaled = if (w == soft.width && h == soft.height) soft else Bitmap.createScaledBitmap(soft, w, h, true)
            art = scaled
            artSignature = sig
            scaled
        } catch (e: RuntimeException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    // Covers by link, the last few kept; a link being fetched; links that failed (not asked again for a while).
    private val byUri = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 12
    }
    private var fetching: String? = null
    private val failedAt = HashMap<String, Long>()
    private val fetcher by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    /**
     * Apps that send the cover as a link (content:// or https) instead of a picture. Never waits: a link
     * not fetched yet is fetched on a thread of its own and the song is published without its cover; when
     * the cover comes, the song is published again with it. (Fetching here held up every update, so a
     * new song or a pause showed seconds late.)
     */
    private fun fromUri(meta: MediaMetadata?): Bitmap? {
        val uri = meta.text(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta.text(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta.text(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val title = meta.text(MediaMetadata.METADATA_KEY_TITLE)
        val artist = meta.text(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        // No link and no song to look up: nothing to fetch.
        val key = uri ?: if (title != null && artist != null) "song:$artist\u0000$title" else return null
        byUri[key]?.let { return it }
        val failed = failedAt[key]
        if (fetching == key || (failed != null && android.os.SystemClock.uptimeMillis() - failed < RETRY_MS)) return null
        fetching = key
        fetcher.execute {
            // The app's own link first (instant when it has the picture); then the song looked up on iTunes,
            // which answers from here; Spotify's CDN last (its host doesn't resolve on this network).
            val b = uri?.let { load(it) }
                ?: if (title != null && artist != null) itunes(title, artist) else null
                ?: uri?.let { spotifyCdn(it) }?.let { load(it, timeoutMs = 2500) }
            android.util.Log.i("SaluteHome", "art fetched: ${if (b != null) "${b.width}x${b.height}" else "nothing"} for $artist - $title")
            worker?.post {
                if (fetching == key) fetching = null
                if (b != null) byUri[key] = b else failedAt[key] = android.os.SystemClock.uptimeMillis()
                update()
            }
        }
        return null
    }

    /** "spotify:image:<id>" inside Spotify's link: the same picture on its CDN. */
    private fun spotifyCdn(uri: String): String? {
        val id = Regex("spotify(?:%3A|:)image(?:%3A|:)([0-9a-f]+)").find(uri)?.groupValues?.get(1) ?: return null
        return "https://i.scdn.co/image/$id"
    }

    /** The song's cover from the iTunes catalogue (no key), 600 px, matched by title and artist. */
    private fun itunes(title: String, artist: String): Bitmap? = try {
        val term = java.net.URLEncoder.encode("$artist $title", "UTF-8")
        val conn = java.net.URL("https://itunes.apple.com/search?term=$term&entity=song&limit=5").openConnection()
            .apply { connectTimeout = 4000; readTimeout = 5000 }
        val json = conn.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
        val results = org.json.JSONObject(json).optJSONArray("results")
        var art: String? = null
        if (results != null) {
            // The artist's own song first; any hit's cover otherwise.
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val a = r.optString("artworkUrl100")
                if (a.isEmpty()) continue
                if (art == null) art = a
                if (r.optString("artistName").contains(artist.substringBefore(',').trim(), ignoreCase = true)) {
                    art = a
                    break
                }
            }
        }
        art?.let { load(it.replace("100x100bb", "600x600bb")) }
    } catch (e: Exception) {
        android.util.Log.w("SaluteHome", "itunes cover for $artist - $title: $e")
        null
    }

    private fun load(uri: String, timeoutMs: Int = 5000): Bitmap? = try {
        val stream = if (uri.startsWith("http")) {
            java.net.URL(uri).openConnection().apply { connectTimeout = timeoutMs; readTimeout = timeoutMs }.getInputStream()
        } else {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))
        }
        stream?.use { android.graphics.BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        android.util.Log.w("SaluteHome", "art uri $uri: $e")
        null
    }

    private fun signatureOf(b: Bitmap): Long {
        var sig = (b.width.toLong() shl 32) xor b.height.toLong()
        val w = b.width
        val h = b.height
        if (w == 0 || h == 0) return sig
        for (i in 1..4) for (j in 1..4) {
            sig = sig * 31 + b.getPixel(w * i / 5, h * j / 5)
        }
        return sig
    }

    /** The app's own name, for a session that gives no title. */
    private fun labelOf(pkg: String): String = labels.getOrPut(pkg) {
        try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun typesOf(configs: List<AudioPlaybackConfiguration>?): List<Int> =
        configs.orEmpty().map { it.audioAttributes.contentType }

    private companion object {
        /** A cover link that failed is tried again after this (Spotify's own link works once it has cached). */
        const val RETRY_MS = 15_000L
    }
}
