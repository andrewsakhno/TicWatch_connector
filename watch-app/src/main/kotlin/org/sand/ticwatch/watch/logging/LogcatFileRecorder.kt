package org.sand.ticwatch.watch.logging

import android.os.Process
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tees this process's own logcat (no READ_LOGS needed for its own pid) into [store], so every
 * `Log.*` call in the app is retrievable over the debug bridge without watch-side adb. Restarts
 * `logcat` if it exits. The reader costs battery (a second process plus a flash write per line), so it
 * runs only between [start] and [stop]; uncaught exceptions are recorded regardless ([recordCrashes]),
 * since the reader dies with the process before the runtime's own crash line would reach it.
 */
class LogcatFileRecorder(private val store: RotatingLogStore) : LogCapture {

    private val crashRecorderInstalled = AtomicBoolean(false)
    private val lock = Any()
    private var reader: Thread? = null
    private val logcat = AtomicReference<java.lang.Process?>()
    @Volatile private var lastLineAt: Instant? = null

    fun recordCrashes() {
        if (crashRecorderInstalled.compareAndSet(false, true)) installCrashRecorder()
    }

    override fun start() {
        synchronized(lock) {
            if (reader != null) return
            reader = Thread(::readLoop, THREAD_NAME).apply { isDaemon = true }.also(Thread::start)
        }
    }

    override fun stop() {
        synchronized(lock) {
            val running = reader ?: return
            reader = null
            running.interrupt()
            logcat.get()?.destroy()
        }
    }

    private fun readLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        while (!Thread.currentThread().isInterrupted) {
            runCatching { readOnce() }.onFailure { Log.w(TAG, "logcat reader failed, restarting", it) }
            try {
                Thread.sleep(RESTART_DELAY_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun readOnce() {
        val process = ProcessBuilder(command()).redirectErrorStream(true).start()
        logcat.set(process)
        try {
            // stop() may have run before the process was published; it destroys only what it can see.
            if (Thread.currentThread().isInterrupted) return
            process.inputStream.bufferedReader().forEachLine { line ->
                val now = Instant.now()
                store.append(line, now)
                lastLineAt = now
            }
        } finally {
            process.destroy()
            logcat.compareAndSet(process, null)
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
