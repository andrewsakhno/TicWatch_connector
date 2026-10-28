package org.sand.ticwatch.phone.snore

import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.health.HealthRecordWriter
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.watchsettings.SnoreMicrophone
import org.sand.ticwatch.phone.watchsettings.WatchSettings
import java.time.Clock
import java.time.Duration

/** Switches the phone's snoring analysis on the watch's sleep-session messages. */
interface SnoreMonitorControl {
    fun onSleepStarted()
    fun onSleepEnded()
}

/**
 * Snoring analysis on the phone's microphone for one night: listens back to back in [epoch]s while [settings] ask
 * for it on the phone, and stores each epoch's snoring share as a history-only [SensorType.SNORING] interval
 * ([SampleSource.PHONE]) - the same shape as the watch's night monitor, so the per-night summary adds them up.
 * Stops on [stop], when the setting changes, or after [maxNight] (a night ended by watch-sensor silence sends no
 * stop). An epoch cut short by [stop] is dropped. A partial wake lock keeps the CPU on while listening.
 */
class PhoneSnoreMonitor(
    private val meter: SnoreMeter,
    private val writer: HealthRecordWriter,
    private val settings: () -> WatchSettings,
    private val powerManager: PowerManager,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val epoch: Duration = Duration.ofMinutes(5),
    private val maxNight: Duration = Duration.ofHours(12),
) {

    private var night: Job? = null

    @Synchronized
    fun start() {
        if (night?.isActive == true) return
        if (!isWanted()) return Unit.also { Log.i(TAG, "Snore analysis on the phone not enabled; ignoring sleep start") }
        night = scope.launch { listen() }
    }

    @Synchronized
    fun stop() {
        night?.cancel()
        night = null
    }

    private fun isWanted(): Boolean = settings().let { it.snoreAnalysis && it.snoreMicrophone == SnoreMicrophone.PHONE }

    private suspend fun listen() {
        val deadline = clock.instant().plus(maxNight)
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
        Log.i(TAG, "Phone snore analysis started")
        try {
            while (isWanted() && clock.instant().isBefore(deadline)) {
                wakeLock.acquire(epoch.plus(WAKE_LOCK_SLACK).toMillis())
                val start = clock.instant()
                val fraction = meter.snoringFraction(epoch)
                val end = clock.instant()
                if (fraction == null) {
                    // Microphone unavailable: wait out the epoch instead of spinning on it.
                    delay(Duration.between(end, start.plus(epoch)).toMillis().coerceAtLeast(0))
                    continue
                }
                writer.write(listOf(SensorSample(SensorType.SNORING, fraction, start, end, SampleSource.PHONE)))
                    .onFailure { Log.w(TAG, "Storing a snoring epoch failed", it) }
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            Log.i(TAG, "Phone snore analysis stopped")
        }
    }

    private companion object {
        const val TAG = "PhoneSnoreMonitor"
        const val WAKE_LOCK_TAG = "TicWatchConnector:PhoneSnore"
        val WAKE_LOCK_SLACK: Duration = Duration.ofSeconds(30)
    }
}

