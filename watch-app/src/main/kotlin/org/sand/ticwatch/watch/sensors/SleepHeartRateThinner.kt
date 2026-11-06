package org.sand.ticwatch.watch.sensors

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration

/**
 * Thins the heart rate on its way to the phone while [applies] says so (asleep, no workout, phone app not on screen):
 * one median per [period] of the readings' own time instead of the ~1 Hz the watch reports during the night monitor,
 * whose rows otherwise fill the sync buffer and trigger a flush every few minutes. Every other type passes untouched,
 * and readings with no contact (0 bpm) are dropped, as the phone ignores them anyway. Sits after the watch's own
 * consumers (live readings, adaptive triggers), which still see every reading.
 *
 * The open period lives in memory and goes out with the next heart-rate reading, so a killed process loses at most
 * that one [period].
 */
class SleepHeartRateThinner(private val period: Duration, private val applies: () -> Boolean) {

    private class OpenPeriod(val index: Long) {
        val readings = mutableListOf<SensorSample>()

        fun median() = SensorSample(SensorType.HEART_RATE, readings.map { it.value }.median(), readings.last().recordedAt)
    }

    fun thin(samples: Flow<SensorSample>): Flow<SensorSample> = flow {
        var open: OpenPeriod? = null
        samples.collect { sample ->
            if (sample.type != SensorType.HEART_RATE) {
                emit(sample)
                return@collect
            }
            val thinning = applies()
            val index = sample.recordedAt.toEpochMilli() / period.toMillis()
            open?.takeIf { !thinning || it.index != index }?.let { ending ->
                emit(ending.median())
                open = null
            }
            when {
                !thinning -> emit(sample)
                sample.value > 0 -> (open ?: OpenPeriod(index).also { open = it }).readings += sample
            }
        }
    }
}
