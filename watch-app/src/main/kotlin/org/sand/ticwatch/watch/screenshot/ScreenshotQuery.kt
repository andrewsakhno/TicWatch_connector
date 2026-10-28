package org.sand.ticwatch.watch.screenshot

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import org.sand.ticwatch.watch.datalayer.DebugQuery
import java.io.ByteArrayOutputStream

/** Debug bridge: answers `{"page": n}` (optional) with a PNG of the visible app screen as base64. */
class ScreenshotQuery(private val screen: () -> ScreenCapturer?) : DebugQuery {

    override suspend fun answer(request: ByteArray): JSONObject {
        val capturer = screen() ?: error("Watch app is not on screen - open it on the watch first")
        val page = requestedPage(request)
        val bitmap = capturer.capture(page)
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            out.toByteArray()
        }
        return JSONObject()
            .put(KEY_PAGE, page ?: JSONObject.NULL)
            .put(KEY_PAGE_COUNT, capturer.pageCount)
            .put(KEY_WIDTH, bitmap.width)
            .put(KEY_HEIGHT, bitmap.height)
            .put(KEY_PNG, Base64.encodeToString(png, Base64.NO_WRAP))
    }

    private fun requestedPage(request: ByteArray): Int? {
        if (request.isEmpty()) return null
        val json = JSONObject(String(request, Charsets.UTF_8))
        return if (json.has(KEY_PAGE)) json.getInt(KEY_PAGE) else null
    }

    private companion object {
        /** Ignored by PNG, which is lossless. */
        const val PNG_QUALITY = 100
        const val KEY_PAGE = "page"
        const val KEY_PAGE_COUNT = "page_count"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_PNG = "png_base64"
    }
}
