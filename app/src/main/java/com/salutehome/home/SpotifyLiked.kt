package com.salutehome.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.net.Uri
import com.salutehome.R
import com.salutehome.media.NowPlayingListener

/**
 * The first card on Spotify's shelf: the owner's Liked Songs, played shuffled. Spotify's TV app follows the
 * open.spotify.com link only when it starts cold; once it runs it ignores links, so then the card asks its
 * player instead, through its media session (what the voice assistant uses), and brings Spotify forward.
 */
object SpotifyLiked {
    const val PACKAGE = "com.spotify.tv.android"

    /** The card's intent, handled by HomeScreen.open. */
    const val OPEN = "salutehome:spotify-liked"

    private const val URI = "spotify:collection:tracks"
    private const val LINK = "https://open.spotify.com/collection/tracks"

    fun row(context: Context): TvRows.Row = TvRows.Row(
        packageName = PACKAGE,
        title = context.getString(R.string.spotify_liked),
        meta = context.getString(R.string.spotify_liked_meta),
        // Kept from the resource shrinker by res/raw/keep.xml.
        image = "android.resource://${context.packageName}/${R.drawable.cover_liked}",
        intentUri = OPEN,
        progress = -1f,
        engaged = 0L,
    )

    /** Plays Liked Songs and shows Spotify; false if Spotify couldn't be reached at all. */
    fun play(context: Context, launch: (Intent) -> Boolean): Boolean {
        val player = try {
            context.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(context, NowPlayingListener::class.java))
                ?.firstOrNull { it.packageName == PACKAGE }
        } catch (e: SecurityException) {
            // No notification access: only the link is left.
            null
        }
        if (player != null) {
            player.transportControls.playFromUri(Uri.parse(URI), null)
            val app = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            if (app != null && launch(app)) return true
        }
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(LINK)).setPackage(PACKAGE))
    }
}
