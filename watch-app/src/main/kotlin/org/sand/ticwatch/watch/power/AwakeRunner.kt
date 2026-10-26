package org.sand.ticwatch.watch.power

import android.os.PowerManager
import java.time.Duration

/** Keeps the application processor awake while a block runs, so the device can't suspend mid-work. */
interface AwakeRunner {
    suspend fun <T> runAwake(timeout: Duration, block: suspend () -> T): T
}

/** [AwakeRunner] on a partial wake lock, released on completion or after [timeout] at the latest. */
class WakeLockAwakeRunner(
    private val powerManager: PowerManager,
    private val tag: String,
) : AwakeRunner {

    override suspend fun <T> runAwake(timeout: Duration, block: suspend () -> T): T {
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        wakeLock.acquire(timeout.toMillis())
        try {
            return block()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}
