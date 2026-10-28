package com.salutehome.side

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Where Picture goes: Sber's own Picture panel, else Android's display settings, else Android's settings;
 * Sound the same way, from Sber's Sound panel. The first that resolves to an activity open to other apps
 * wins.
 */
internal object PictureSettings {

    /** Sber's own Picture panel (com.sberbank.settings), as the remote's settings show it. */
    const val SHOW_PICTURE = "com.cvte.intent.action.SHOW_PICTURE"

    /** Sber's own Sound panel. */
    const val SHOW_SOUND = "com.cvte.intent.action.SHOW_SOUND"

    /**
     * The candidates in order. Sber's Picture panel first: CVTE's TV settings opened without a TV source
     * start the channel set-up wizard instead (and the TV hung after it).
     */
    fun candidates(): List<Intent> = listOf(
        Intent(SHOW_PICTURE),
        Intent(Settings.ACTION_DISPLAY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    /** Sound's candidates: Sber's panel, then Android's sound settings, then Android's settings. */
    fun soundCandidates(): List<Intent> = listOf(
        Intent(SHOW_SOUND),
        Intent(Settings.ACTION_SOUND_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    /** The first candidate this app may open, or null. Call it off the main thread: it asks PackageManager. */
    fun find(context: Context, candidates: List<Intent> = candidates()): Intent? {
        return candidates.firstOrNull { opens(context, it) }
    }

    private fun opens(context: Context, intent: Intent): Boolean {
        val info = try {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        } catch (e: RuntimeException) {
            null
        } ?: return false
        if (!info.exported) return false
        val permission = info.permission ?: return true
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
