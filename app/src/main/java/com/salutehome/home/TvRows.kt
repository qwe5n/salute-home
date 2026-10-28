package com.salutehome.home

import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import com.salutehome.R

/**
 * What the apps themselves publish to Android's TV listings: Continue watching (Watch Next) and the
 * programmes of their preview channels (recommendations). Read with READ_TV_LISTINGS (granted over adb at
 * install). A reader without ACCESS_ALL_EPG_DATA sees only rows marked searchable and may pass no
 * selection, so the whole tables are read and sorted out here (docs/watch-next.md in cleanyourtv).
 * Blocking: run it off the main thread.
 */
object TvRows {
    private const val TAG = "SaluteHome"

    /** One row as the shelf shows it. [progress] is 0 to 1 for Continue watching, -1 for a recommendation. */
    data class Row(
        val packageName: String,
        val title: String,
        val meta: String,
        val image: String,
        val intentUri: String,
        val progress: Float,
        val engaged: Long,
    )

    /** Every app's rows by package: Continue watching first (most recent first), then recommendations. */
    fun read(context: Context): Map<String, List<Row>> {
        val next = query(context, TvContract.WatchNextPrograms.CONTENT_URI, watchNext = true)
        val preview = query(context, TvContract.PreviewPrograms.CONTENT_URI, watchNext = false)
        val out = HashMap<String, List<Row>>()
        for (pkg in (next.map { it.packageName } + preview.map { it.packageName }).toSet()) {
            out[pkg] = merge(next.filter { it.packageName == pkg }, preview.filter { it.packageName == pkg })
        }
        Log.i(TAG, "tv rows: " + out.entries.joinToString { "${it.key}=${it.value.count { r -> r.progress >= 0 }}+${it.value.count { r -> r.progress < 0 }}" }.ifEmpty { "none" })
        for (r in out.values.flatten().take(3)) Log.i(TAG, "tv row image: ${r.image.substringBefore("://")}://${r.image.substringAfter("://").substringBefore('/')}")
        return out
    }

    /**
     * One app's rows: Continue watching, most recent first, then its recommendations, each title once. An app
     * can put a title in several of its channels (Stremio does), or recommend what's already being watched.
     * A Watch Next title with no length goes with the recommendations.
     */
    fun merge(next: List<Row>, preview: List<Row>): List<Row> {
        val seen = HashSet<String>()
        val ordered = next.sortedWith(compareBy<Row> { it.progress < 0f }.thenByDescending { it.engaged })
        return (ordered + preview).filter { seen.add(it.title.trim().lowercase()) }
    }

    private fun query(context: Context, uri: Uri, watchNext: Boolean): List<Row> {
        val cursor: Cursor = try {
            context.contentResolver.query(uri, null, null, null, null)
        } catch (e: RuntimeException) {
            // No permission, or a TV provider that doesn't have the table.
            Log.w(TAG, "tv rows: $uri: $e")
            null
        } ?: return emptyList()
        val rows = ArrayList<Row>()
        cursor.use { c ->
            fun col(name: String) = c.getColumnIndex(name)
            val pkg = col(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME)
            val title = col(TvContract.PreviewPrograms.COLUMN_TITLE)
            val episode = col(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE)
            val season = col(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER)
            val ep = col(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER)
            val poster = col(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI)
            val thumb = col(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI)
            val intent = col(TvContract.PreviewPrograms.COLUMN_INTENT_URI)
            val position = col(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
            val duration = col(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS)
            val engaged = if (watchNext) col(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS) else -1
            val browsable = if (watchNext) -1 else col(TvContract.PreviewPrograms.COLUMN_BROWSABLE)
            while (c.moveToNext()) {
                if (browsable >= 0 && !c.isNull(browsable) && c.getInt(browsable) == 0) continue
                val p = c.str(pkg) ?: continue
                val t = c.str(title) ?: continue
                val image = c.str(thumb) ?: c.str(poster) ?: continue
                val dur = c.long(duration)
                val pos = c.long(position)
                val left = if (dur > 0 && pos > 0) ((dur - pos) / 60000L).coerceAtLeast(1) else 0
                val meta = when {
                    watchNext && left > 0 -> context.getString(R.string.home_row_left, left)
                    c.str(season) != null && c.str(ep) != null -> context.getString(R.string.home_row_episode, c.str(season), c.str(ep))
                    else -> c.str(episode) ?: ""
                }
                rows += Row(
                    packageName = p,
                    title = t,
                    meta = meta,
                    image = image,
                    intentUri = c.str(intent) ?: "",
                    // Without a length there's no bar to draw: such a row counts as a recommendation.
                    progress = if (watchNext && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else -1f,
                    engaged = if (engaged >= 0) c.long(engaged) else 0L,
                )
            }
        }
        return rows
    }

    private fun Cursor.str(i: Int): String? = if (i < 0 || isNull(i)) null else getString(i)?.takeIf { it.isNotBlank() }
    private fun Cursor.long(i: Int): Long = if (i < 0 || isNull(i)) 0L else getLong(i)
}
