package org.sand.ticwatch.phone.datalayer

import android.util.Log
import java.io.File

/** Keeps a watch debug probe's reply that is too large for logcat. */
fun interface WatchProbeDumpSink {
    fun save(name: String, payload: ByteArray)
}

/**
 * Writes each reply as `<name>-<epochMs>.json` into [directory] (pull with
 * `adb exec-out run-as org.sand.ticwatch.phone cat files/watch_probes/<file>`).
 */
class FileWatchProbeDumpSink(private val directory: File) : WatchProbeDumpSink {

    override fun save(name: String, payload: ByteArray) {
        directory.mkdirs()
        val file = File(directory, "$name-${System.currentTimeMillis()}.json")
        file.writeBytes(payload)
        Log.i(TAG, "Watch probe saved: ${file.absolutePath} (${payload.size} bytes)")
    }

    companion object {
        const val DIRECTORY_NAME = "watch_probes"
        const val RAW_PPG_DUMP_NAME = "raw-ppg"
        private const val TAG = "WatchProbeDump"
    }
}
