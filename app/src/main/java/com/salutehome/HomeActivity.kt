package com.salutehome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import com.salutehome.core.HomeHost
import com.salutehome.home.DockApps
import com.salutehome.home.DockOrder
import com.salutehome.home.HomeScreen
import com.salutehome.home.Images
import com.salutehome.music.MusicMode
import com.salutehome.saver.SaverOverlay
import com.salutehome.side.DropdownView
import com.salutehome.side.TopBarView

/**
 * The home screen: the prototype's Sidebar layout. The slots in activity_home.xml are filled by the parts:
 * the glow, bento, shelf and app dock (this file and home/), the side dock (side/), the island (island/),
 * music mode (music/), the screensaver (saver/).
 */
class HomeActivity : Activity(), HomeHost {

    private lateinit var home: HomeScreen
    private lateinit var topBar: TopBarView
    private lateinit var musicMode: MusicMode
    private lateinit var saver: SaverOverlay
    private var resumed = false
    private var musicCovered = false
    private var saverOnResume = false

    // The prototype is drawn on a 960 x 540 dp screen: the density that makes the panel exactly that wide
    // (320 dpi on the Salute TV's 1920 x 1080, 213 dpi on a 1280 x 720 UI).
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val m = base.resources.displayMetrics
        val dpi = maxOf(m.widthPixels, m.heightPixels) * 160 / UI_WIDTH_DP
        if (base.resources.configuration.densityDpi != dpi) {
            applyOverrideConfiguration(Configuration().apply { densityDpi = dpi })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        // The Input key service (bridge, volume bar, music mode over apps) must stay on; see ServiceGuard.
        com.salutehome.input.ServiceGuard.start(this)
        // The layout's root paints the screen black, so the theme's black window behind it would only be
        // a second full-screen fill on every frame (the launch preview still uses the theme's).
        window.setBackgroundDrawable(null)

        // The glow, bento, shelf, app dock and toast (home/). First, so the toast is there for the others.
        home = HomeScreen(this, this, findViewById(R.id.root))

        val menu = DropdownView(this)
        findViewById<FrameLayout>(R.id.menuSlot).addView(menu)
        // Holding OK on an app, in the dock or the library: change the dock, close the app, or its system
        // page to force-stop it.
        val appMenu: (com.salutehome.home.AppTileView) -> Unit = { tile ->
            val pkg = tile.app.packageName
            if (!pkg.startsWith("*")) {
                val dock = home.dock.tiles.map { it.app.packageName }
                val inDockRow = tile.parent === home.dock
                val dockRows = buildList {
                    if (inDockRow && dock.size > 1) {
                        add(DropdownView.Row(getString(R.string.app_menu_move)) { home.dock.startMove(tile) })
                    }
                    if (pkg in dock) {
                        if (dock.size > 1) add(DropdownView.Row(getString(R.string.app_menu_unpin)) {
                            DockOrder.save(this@HomeActivity, DockApps.remove(dock, pkg))
                        })
                    } else {
                        add(DropdownView.Row(getString(R.string.app_menu_pin)) {
                            if (DockApps.isFull(dock)) {
                                home.toast(getString(R.string.app_menu_dock_full, DockApps.DOCK_SIZE))
                            } else {
                                DockOrder.save(this@HomeActivity, DockApps.add(dock, pkg))
                                home.toast(getString(R.string.app_menu_pinned, tile.app.label))
                            }
                        })
                    }
                }
                menu.open(tile, dockRows + listOf(
                    DropdownView.Row(getString(R.string.app_menu_close)) {
                        getSystemService(android.app.ActivityManager::class.java)?.killBackgroundProcesses(pkg)
                        home.toast(getString(R.string.app_menu_closed, tile.app.label))
                    },
                    DropdownView.Row(getString(R.string.app_menu_force)) {
                        launch(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg")))
                    },
                ))
            }
        }
        home.dock.onMenu = appMenu
        home.dock.onMoved = { order -> DockOrder.save(this, order) }
        home.apps.onMenu = appMenu
        topBar = TopBarView(this, this, menu)
        findViewById<FrameLayout>(R.id.topBarSlot).addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Now playing is a card on the shelf (home/PlayerCard.kt); the island is no longer shown.
        musicMode = MusicMode(this, findViewById(R.id.musicSlot))
        home.shelf.player.onLyrics = { musicMode.open() }
        com.salutehome.core.HomeChrome.volumeSpan = span@{
            val wifi = topBar.wifiPill
            val vol = topBar.volumePill
            if (wifi.width == 0 || vol.width == 0) return@span null
            val a = IntArray(2)
            val b = IntArray(2)
            wifi.getLocationOnScreen(a)
            vol.getLocationOnScreen(b)
            intArrayOf(a[0], b[1], b[0] + vol.width, b[1] + vol.height, vol.width)
        }
        com.salutehome.core.HomeChrome.topBarShown = {
            resumed && !saver.isOn && !musicMode.isOn && !home.idle.isOn && !home.apps.isOpen && !home.weatherPanel.isOpen
        }
        saver = SaverOverlay(this, findViewById(R.id.saverSlot))
        // Under music mode's black or the screensaver the clock stops ticking: nothing there can be seen.
        // While the screensaver shows, music mode stands aside, so the two never show together.
        musicMode.onCovered = { covered ->
            musicCovered = covered
            home.setCovered(covered || saver.isOn)
        }
        saver.onShown = { shown ->
            home.setCovered(shown || musicCovered)
            musicMode.setForeground(resumed && !shown)
        }

        // Last, once every part is in: the apps, focus on YouTube, then the cached and fresh data.
        home.start()
    }

    // For checking the screensaver without waiting 10 minutes:
    // am start -n com.salutehome/.HomeActivity --ez show_saver true
    // (A new intent arrives between onPause and onResume, so it's shown once the screen is back in front.)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_SAVER, false)) saverOnResume = true
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        home.resume()
        topBar.start()
        musicMode.setForeground(true)
        saver.setForeground(true)
        if (saverOnResume) {
            saverOnResume = false
            saver.showNow()
        }
    }

    override fun onRestart() {
        super.onRestart()
        val content = findViewById<View>(R.id.content) ?: return
        content.animate().cancel()
        content.alpha = 0f
        content.scaleX = 1.04f
        content.scaleY = 1.04f
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    }

    override fun onPause() {
        resumed = false
        musicMode.setForeground(false)
        saver.setForeground(false)
        topBar.stop()
        home.pause()
        super.onPause()
    }

    override fun onDestroy() {
        musicMode.release()
        saver.release()
        home.destroy()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Out of sight or short of memory: let the decoded-picture cache go (what's on screen stays).
        if (level >= TRIM_MEMORY_UI_HIDDEN) Images.trim()
    }

    // Every key first goes to the screensaver and music mode: each restarts its idle clock, and while
    // either is showing the first key only wakes the screen, as a remote does.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (musicMode.onKey(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN && wake()) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && wake()) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun wake(): Boolean {
        val wokeSaver = saver.onUserActivity()
        val wokeMusic = musicMode.onUserActivity()
        val wokeIdle = home.onUserActivity()
        return wokeSaver || wokeMusic || wokeIdle
    }

    @Deprecated("Back on API 28 still comes here")
    override fun onBackPressed() {
        // Home is the bottom of the stack: Back only closes what's open on it.
        if (!home.dock.endMove() && !topBar.onBack() && !home.weatherPanel.onBack()) home.apps.onBack()
    }

    // ---- HomeHost ----

    override fun toast(text: CharSequence) {
        home.toast(text)
    }

    override fun launch(intent: Intent): Boolean = try {
        // The app grows out of the tile or poster that opened it, as on Apple TV.
        val from = currentFocus
        val options = if (from != null && from.width > 0) {
            android.app.ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        } else null
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
        true
    } catch (e: ActivityNotFoundException) {
        toast(getString(R.string.side_not_available))
        false
    } catch (e: SecurityException) {
        toast(getString(R.string.side_not_available))
        false
    }

    override fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            toast(getString(R.string.app_not_installed))
            return false
        }
        return launch(intent)
    }

    companion object {
        const val UI_WIDTH_DP = 960
        private const val EXTRA_SHOW_SAVER = "show_saver"
    }
}
