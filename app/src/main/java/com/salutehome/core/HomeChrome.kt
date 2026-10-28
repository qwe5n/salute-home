package com.salutehome.core

/**
 * Whether the home screen's top bar is in sight right now (main thread): the home in front with nothing
 * over it (idle mode, the screensaver, the lyrics screen, the app library). The volume pill opens over the
 * top bar's own pill only then; otherwise it comes up at the bottom, as over an app.
 */
object HomeChrome {
    var topBarShown: () -> Boolean = { true }

    /**
     * Where the volume opens on the home screen, in screen pixels: from the Wi-Fi pill's left edge to the
     * volume pill's right edge, as left, top, right, bottom, then the volume pill's own width; null if unknown.
     */
    var volumeSpan: () -> IntArray? = { null }
}
