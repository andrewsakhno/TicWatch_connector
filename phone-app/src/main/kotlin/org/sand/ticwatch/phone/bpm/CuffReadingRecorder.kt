package org.sand.ticwatch.phone.bpm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sand.ticwatch.phone.health.BloodPressureLimits
import org.sand.ticwatch.phone.health.BloodPressureMonitor
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.CalibrationResult
import org.sand.ticwatch.phone.health.SampleSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Stores each cuff result as a blood pressure reading plus its pulse (history + Health Connect, tagged with the cuff)
 * and recalibrates the wrist estimate with it when the watch has a heart rate within 5 min of it
 * ([BloodPressureMonitor.calibrateFromCuff]). Watch data reaches the phone in batches (<= 15 min apart), so a missing
 * heart rate is retried after each of [CALIBRATION_RETRY_DELAYS].
 *
 * The cuff clock is the reference, since [BiolandCuffClient] sets it on every connection: a reading is stamped with it
 * when it falls in [MAX_RECORD_AGE] .. now + [CLOCK_TOLERANCE]. Outside that the clock was lost (battery change before
 * the next connection): a live reading falls back to the phone's time, a stored one can't be placed and is dropped.
 * A reading within [DUPLICATE_WINDOW] of a stored cuff reading is the same measurement uploaded again.
 */
class CuffReadingRecorder(
    private val monitor: BloodPressureMonitor,
    private val statusSink: CuffStatusSink,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val zone: ZoneId,
) : CuffEventListener {

    private val storeLock = Mutex()

    override fun onReading(reading: CuffReading, live: Boolean) {
        val systolic = reading.systolic.toDouble()
        val diastolic = reading.diastolic.toDouble()
        if (!BloodPressureLimits.isPlausible(systolic, diastolic)) {
            Log.w(TAG, "Implausible cuff reading $reading, not stored")
            return
        }
        val now = clock.instant()
        val measuredAt = cuffTime(reading, now) ?: if (live) now else null
        if (measuredAt == null) {
            Log.w(TAG, "Stored cuff reading $reading has no usable time, dropped")
            return
        }
        scope.launch {
            val stored = storeLock.withLock {
                if (isDuplicate(measuredAt)) {
                    Log.i(TAG, "Cuff reading at $measuredAt already stored")
                    return@withLock false
                }
                monitor.log.save(
                    BloodPressureReading(systolic, diastolic, measuredAt, SampleSource.BLOOD_PRESSURE_CUFF, reading.pulse.toDouble()),
                )
                    .onSuccess { Log.i(TAG, "Stored cuff reading $systolic/$diastolic pulse ${reading.pulse} at $measuredAt") }
                    .onFailure { Log.w(TAG, "Failed to store cuff reading", it) }
                true
            }
            if (stored) calibrate(systolic, diastolic, measuredAt)
        }
    }

    private suspend fun calibrate(systolic: Double, diastolic: Double, measuredAt: Instant) {
        for (retryDelay in listOf(Duration.ZERO) + CALIBRATION_RETRY_DELAYS) {
            delay(retryDelay.toMillis())
            val result = monitor.calibrateFromCuff(systolic, diastolic, measuredAt)
            Log.i(TAG, "Calibration from cuff reading at $measuredAt: $result")
            if (result != CalibrationResult.NoHeartRate) return
        }
    }

    override fun onDeviceInfo(info: BiolandFrame.DeviceInfo) =
        statusSink.setCuffStatus(CuffStatus(info.batteryPercent, info.serial, clock.instant()))

    private fun cuffTime(reading: CuffReading, now: Instant): Instant? =
        reading.deviceClock?.atZone(zone)?.toInstant()
            ?.takeIf { it.isAfter(now.minus(MAX_RECORD_AGE)) && it.isBefore(now.plus(CLOCK_TOLERANCE)) }

    private suspend fun isDuplicate(at: Instant): Boolean =
        monitor.log.readings(at.minus(DUPLICATE_WINDOW), at.plus(DUPLICATE_WINDOW))
            .any { it.source == SampleSource.BLOOD_PRESSURE_CUFF }

    private companion object {
        const val TAG = "CuffReadingRecorder"
        val MAX_RECORD_AGE: Duration = Duration.ofDays(365)
        val CLOCK_TOLERANCE: Duration = Duration.ofMinutes(10)
        val DUPLICATE_WINDOW: Duration = Duration.ofMinutes(3)
        val CALIBRATION_RETRY_DELAYS: List<Duration> = List(3) { Duration.ofMinutes(10) }
    }
}
