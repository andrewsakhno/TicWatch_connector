package org.sand.ticwatch.watch.logging

/** Continuous capture of the app's own log; idempotent start/stop. */
interface LogCapture {
    fun start()
    fun stop()
}
