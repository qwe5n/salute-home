package com.salutehome.saver

/** Ease-out (cubic): fast at first, settling at the end; [t] from 0 to 1. */
internal fun easeOut(t: Double): Double {
    val u = 1.0 - t.coerceIn(0.0, 1.0)
    return 1.0 - u * u * u
}
