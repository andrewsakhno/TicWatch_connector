package org.sand.ticwatch.phone.datalayer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.health.BloodPressureLogListener
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.RecentBloodPressureReadings
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** Tells the watch about the latest saved blood pressure readings. */
fun interface BloodPressureHistoryPublisher : WatchStatePublisher

/**
 * Sends the last [HISTORY_WINDOW] of saved readings (newest first, at most [MAX_READINGS]; just the latest one when
 * the day has none) for the watch's chart on [DataLayerPaths.MESSAGE_BLOOD_PRESSURE_HISTORY] whenever the log
 * changes (cuff upload, wrist estimate, deletion) and after sensor pushes, so a watch that missed one catches up; a
 * list identical to the last one sent isn't sent again.
 */
class WatchBloodPressurePublisher(
    private val readings: RecentBloodPressureReadings,
    private val transport: DeviceLinkTransport,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : BloodPressureHistoryPublisher, BloodPressureLogListener {

    private val lastSent = AtomicReference<String?>()

    override fun onLogChanged() = publish()

    override fun publish() {
        scope.launch {
            val payload = runCatching { BloodPressureWatchPayloads.encodeHistory(lastDay()) }
                .onFailure { Log.w(TAG, "Reading blood pressure history failed", it) }
                .getOrNull() ?: return@launch
            if (payload == lastSent.get()) return@launch
            transport.sendMessage(DataLayerPaths.MESSAGE_BLOOD_PRESSURE_HISTORY, payload.toByteArray(Charsets.UTF_8))
                .onSuccess { lastSent.set(payload) }
                .onFailure { Log.w(TAG, "Sending blood pressure history to the watch failed", it) }
        }
    }

    private suspend fun lastDay(): List<BloodPressureReading> =
        readings.since(clock.instant().minus(HISTORY_WINDOW)).asReversed().take(MAX_READINGS)
            .ifEmpty { readings.recent(LATEST_ONLY) }

    private companion object {
        const val TAG = "BloodPressurePublisher"

        /** The watch charts one day: 288 automatic readings at 5 min, plus cuff readings. */
        val HISTORY_WINDOW: Duration = Duration.ofHours(24)
        const val MAX_READINGS = 400
        const val LATEST_ONLY = 1
    }
}
