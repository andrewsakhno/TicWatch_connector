package org.sand.ticwatch.watch.logging

import android.os.Process
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tees this process's own logcat (no READ_LOGS needed for its own pid) into [store], so every
 * `Log.*` call in the app is retrievable over the debug bridge without watch-side adb. Restarts
 * `logcat` if it exits and records uncaught exceptions directly, since the reader dies with the
 * process before the runtime's own crash line would reach it.
 */
class LogcatFileRecorder(private val store: RotatingLogStore) {

    private val started = AtomicBoolean(false)
    @Volatile private var lastLineAt: Instant? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        installCrashRecorder()
        Thread(::readLoop, THREAD_NAME).apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        while (true) {
            runCatching { readOnce() }.onFailure { Log.w(TAG, "logcat reader failed, restarting", it) }
            Thread.sleep(RESTART_DELAY_MS)
        }
    }

    private fun readOnce() {
        val process = ProcessBuilder(command()).redirectErrorStream(true).start()
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                val now = Instant.now()
                store.append(line, now)
                lastLineAt = now
            }
        } finally {
            process.destroy()
        }
    }

    // On a restart, -T resumes after the last line already stored instead of re-dumping the buffer.
    private fun command(): List<String> {
        val base = listOf(LOGCAT, "-v", "threadtime", "-v", "year", "--pid=${Process.myPid()}")
        val resumeAt = lastLineAt ?: return base
        return base + listOf("-T", "%d.%03d".format(resumeAt.epochSecond, resumeAt.nano / NANOS_PER_MILLI))
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { recordCrash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun recordCrash(thread: Thread, error: Throwable) {
        val now = Instant.now()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        store.append("${CRASH_TIME.format(now)} FATAL uncaught exception in thread ${thread.name}", now)
        trace.lineSequence().filter { it.isNotEmpty() }.forEach { store.append(it, now) }
    }

    private companion object {
        const val TAG = "LogcatFileRecorder"
        const val THREAD_NAME = "logcat-file-recorder"
        const val LOGCAT = "logcat"
        const val RESTART_DELAY_MS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000
        val CRASH_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
