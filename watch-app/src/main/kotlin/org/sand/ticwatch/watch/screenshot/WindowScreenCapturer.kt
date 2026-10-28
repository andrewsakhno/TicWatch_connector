package org.sand.ticwatch.watch.screenshot

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Lets something outside the composition switch the dashboard pager; the screen collects [requests]. */
class DashboardPageNavigator {
    private val pageRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val requests: SharedFlow<Int> = pageRequests

    /** Set by the dashboard once composed. */
    @Volatile
    var pageCount: Int = 0

    fun show(page: Int) {
        pageRequests.tryEmit(page)
    }
}

/** Captures the activity [window] with [PixelCopy] (hardware-rendered Compose content included). */
class WindowScreenCapturer(
    private val window: Window,
    private val navigator: DashboardPageNavigator,
) : ScreenCapturer {

    override val pageCount: Int get() = navigator.pageCount

    override suspend fun capture(page: Int?): Bitmap {
        if (page != null) {
            require(page in 0 until pageCount) { "Page $page out of 0 until $pageCount" }
            navigator.show(page)
            // Page scroll plus the first frame of the page's data.
            delay(PAGE_SETTLE_MS)
        }
        return withContext(Dispatchers.Main) { copyWindow() }
    }

    private suspend fun copyWindow(): Bitmap {
        val view = window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { continuation ->
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    continuation.resume(bitmap)
                } else {
                    continuation.resumeWithException(IllegalStateException("PixelCopy failed with code $result"))
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    private companion object {
        const val PAGE_SETTLE_MS = 1_200L
    }
}
