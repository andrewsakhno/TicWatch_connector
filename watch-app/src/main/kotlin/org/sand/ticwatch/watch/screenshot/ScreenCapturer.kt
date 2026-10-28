package org.sand.ticwatch.watch.screenshot

import android.graphics.Bitmap

/** Renders what this app currently shows on the watch display. */
interface ScreenCapturer {
    /** Pages the dashboard can be switched to, for the phone to iterate over. */
    val pageCount: Int

    /** Captures [page] (switching to it first), or the current page when null. */
    suspend fun capture(page: Int?): Bitmap
}

/**
 * Process-wide holder of the screen that is visible right now: the debug query runs in a listener service while the
 * activity that can be captured registers itself here between onStart and onStop.
 */
object VisibleScreen {
    @Volatile
    var capturer: ScreenCapturer? = null
        private set

    fun show(capturer: ScreenCapturer) {
        this.capturer = capturer
    }

    fun hide(capturer: ScreenCapturer) {
        if (this.capturer === capturer) this.capturer = null
    }
}
