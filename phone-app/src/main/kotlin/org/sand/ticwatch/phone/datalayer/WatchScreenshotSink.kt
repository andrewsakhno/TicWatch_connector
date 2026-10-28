package org.sand.ticwatch.phone.datalayer

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Receives the watch's screenshot replies (`/debug/screenshot/response`). */
fun interface WatchScreenshotSink {
    fun save(payload: ByteArray)
}

/**
 * Writes each screenshot as `page-<n>-<epochMs>.png` into [directory] (pull with
 * `adb exec-out run-as org.sand.ticwatch.phone cat files/watch_screenshots/<name>`).
 */
class FileWatchScreenshotSink(private val directory: File) : WatchScreenshotSink {

    override fun save(payload: ByteArray) {
        val json = JSONObject(String(payload, Charsets.UTF_8))
        if (json.has(KEY_ERROR)) {
            Log.w(TAG, "Watch screenshot failed: ${json.getString(KEY_ERROR)}")
            return
        }
        val page = if (json.isNull(KEY_PAGE)) CURRENT_PAGE_LABEL else json.getInt(KEY_PAGE).toString()
        directory.mkdirs()
        val file = File(directory, "page-$page-${System.currentTimeMillis()}.png")
        file.writeBytes(Base64.decode(json.getString(KEY_PNG), Base64.DEFAULT))
        Log.i(TAG, "Watch screenshot saved: ${file.absolutePath} (page ${json.opt(KEY_PAGE)} of ${json.optInt(KEY_PAGE_COUNT)})")
    }

    companion object {
        const val DIRECTORY_NAME = "watch_screenshots"
        private const val TAG = "WatchScreenshot"
        private const val CURRENT_PAGE_LABEL = "current"
        private const val KEY_ERROR = "error"
        private const val KEY_PAGE = "page"
        private const val KEY_PAGE_COUNT = "page_count"
        private const val KEY_PNG = "png_base64"
    }
}
