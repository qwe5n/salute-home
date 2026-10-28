package com.salutehome.music

import android.animation.ValueAnimator
import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.salutehome.R
import com.salutehome.core.NowPlaying
import com.salutehome.core.NowPlayingHub

/**
 * The lyrics screen over the home screen, opened from the player card's lyrics button ([open]), never by
 * itself. It fades in over the home screen, which is then hidden so only the lyrics screen draws; keys go
 * to it while it shows ([onKey]) and Back leaves, giving focus back to where it was. The screen stays on
 * while it shows. Nothing playing at all closes it; a pause doesn't (its own play button resumes).
 */
class MusicMode(private val activity: Activity, private val layer: FrameLayout) {
    private var view: LyricsView? = null
    private var foreground = false
    private var on = false
    private var behindHidden = false
    private var focusBefore: View? = null
    private val behind = arrayOfNulls<View>(BEHIND.size)
    private val behindWas = IntArray(BEHIND.size)
    private val nowPlaying: (NowPlaying?) -> Unit = { onNowPlaying(it) }

    /** Told when the home screen goes under the lyrics screen (true) and comes back (false), so it can rest. */
    var onCovered: ((Boolean) -> Unit)? = null

    init {
        NowPlayingHub.addListener(nowPlaying)
    }

    val isOn: Boolean get() = on

    /** A touch while it shows closes it; true if it did (swallow that touch). */
    fun onUserActivity(): Boolean {
        if (!on) return false
        close()
        return true
    }

    /** The home screen is in front (onResume) or not (onPause): the lyrics screen only shows while it is. */
    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        if (!foreground) closeNow()
    }

    fun release() {
        NowPlayingHub.removeListener(nowPlaying)
        closeNow()
        view?.let { layer.removeView(it) }
        view = null
    }

    /** The lyrics button: the lyrics screen opens while anything plays. */
    fun open() {
        val np = NowPlayingHub.current ?: return
        if (on || !foreground) return
        on = true
        val v = view ?: LyricsView(activity).also {
            view = it
            layer.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        v.show(np, 0)
        v.visibility = View.VISIBLE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        v.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            v.alpha = 1f
            hideBehind()
            return
        }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(FADE_IN_MS).withEndAction { if (on) hideBehind() }.start()
    }

    /** A key while the lyrics screen shows: Back leaves, Home passes, the rest drive its control centre. */
    fun onKey(event: KeyEvent): Boolean {
        if (!on) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) close()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_HOME) return false
        return view?.onKey(event) ?: true
    }

    private fun onNowPlaying(np: NowPlaying?) {
        view?.show(np, 0)
        // Fetch the words as the song starts, so they're there when the lyrics screen opens.
        if (np != null && !on) Lyrics.find(np.title, np.artist, np.durationMs) {}
        if (np == null) close()
    }

    private fun close() {
        if (!on) return
        on = false
        showBehind()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val v = view ?: return
        v.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            v.visibility = View.GONE
            return
        }
        v.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction { if (!on) v.visibility = View.GONE }.start()
    }

    // The home screen left the front: off at once.
    private fun closeNow() {
        on = false
        showBehind()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view?.animate()?.cancel()
        view?.visibility = View.GONE
    }

    // Under the opaque lyrics screen nothing behind can be seen, so it isn't drawn at all.
    private fun hideBehind() {
        if (behindHidden) return
        behindHidden = true
        // Hiding the home screen drops its focus: remember where it was, to give it back on the way out.
        focusBefore = activity.currentFocus
        onCovered?.invoke(true)
        for (i in BEHIND.indices) {
            val v = activity.findViewById<View>(BEHIND[i]) ?: continue
            behind[i] = v
            behindWas[i] = v.visibility
            v.visibility = View.INVISIBLE
        }
    }

    private fun showBehind() {
        if (!behindHidden) return
        behindHidden = false
        onCovered?.invoke(false)
        for (i in BEHIND.indices) {
            val v = behind[i] ?: continue
            if (v.visibility == View.INVISIBLE) v.visibility = behindWas[i]
            behind[i] = null
        }
        focusBefore?.let { f -> f.post { if (f.isShown) f.requestFocus() } }
        focusBefore = null
    }

    private companion object {
        const val FADE_IN_MS = 500L
        const val FADE_OUT_MS = 350L
        val BEHIND = intArrayOf(R.id.content, R.id.glowSlot)
    }
}
