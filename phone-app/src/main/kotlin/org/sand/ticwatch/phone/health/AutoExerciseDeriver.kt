package org.sand.ticwatch.phone.health

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** Remembers the end of the last automatically detected exercise, so a walk is never emitted twice. */
interface InstantLog {
    suspend fun last(): Instant?
    suspend fun record(at: Instant)
}

/** [InstantLog] over SharedPreferences. */
class SharedPreferencesInstantLog(context: Context, name: String) : InstantLog {

    private val prefs by lazy { context.getSharedPreferences(name, Context.MODE_PRIVATE) }

    override suspend fun last(): Instant? = withContext(Dispatchers.IO) {
        if (prefs.contains(KEY)) Instant.ofEpochMilli(prefs.getLong(KEY, 0L)) else null
    }

    override suspend fun record(at: Instant) = withContext(Dispatchers.IO) {
        prefs.edit().putLong(KEY, at.toEpochMilli()).apply()
    }

    private companion object {
        const val KEY = "last_epoch_ms"
    }
}

/**
 * Walks and runs nobody started a workout for, from Health Services' passive step intervals (as Google
 * Fit's auto-detected activities): intervals at [minCadence] steps/min or more, merged across gaps of up
 * to [maxGap], lasting [minDuration], become one [SensorType.AUTO_EXERCISE] - a run when the mean cadence
 * reaches [runCadence]. A stretch is emitted once it has ended (no brisk steps for [settle] up to the
 * newest data, which may take a later push) and only if no watch workout overlaps it; [log] keeps it
 * from being emitted again. The history lookback ([lookback]) lets a walk span several pushes.
 */
class AutoExerciseDeriver(
    private val reader: SampleWindowReader,
    private val log: InstantLog,
    private val lookback: Duration = Duration.ofHours(3),
    private val minCadence: Double = 90.0,
    private val runCadence: Double = 140.0,
    private val minInterval: Duration = Duration.ofSeconds(30),
    private val maxInterval: Duration = Duration.ofMinutes(10),
    private val maxGap: Duration = Duration.ofMinutes(1),
    private val minDuration: Duration = Duration.ofMinutes(10),
    private val settle: Duration = Duration.ofMinutes(5),
    private val maxWorkout: Duration = Duration.ofHours(12),
) : SampleDeriver {

    private class Stretch(val start: Instant, var end: Instant, var steps: Double)

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> {
        val newest = samples.filter { it.type == SensorType.STEPS }.mapNotNull { it.endedAt }.maxOrNull() ?: return emptyList()
        val from = newest.minus(lookback)
        val emittedUntil = log.last()
        val workouts = reader.samples(WORKOUTS, from.minus(maxWorkout), newest, samples).mapNotNull { w -> w.endedAt?.let { w.recordedAt to it } }
        val detected = stretches(reader.samples(STEPS, from, newest.plusMillis(1), samples))
            .filter { Duration.between(it.start, it.end) >= minDuration }
            .filter { !it.end.plus(settle).isAfter(newest) }
            .filter { emittedUntil == null || it.start.isAfter(emittedUntil) }
            .filter { stretch -> workouts.none { (start, end) -> start.isBefore(stretch.end) && end.isAfter(stretch.start) } }
        detected.maxOfOrNull { it.end }?.let { log.record(it) }
        return detected.map { stretch ->
            val minutes = Duration.between(stretch.start, stretch.end).toMillis() / MILLIS_PER_MINUTE
            val type = if (stretch.steps / minutes >= runCadence) HealthServicesExerciseTypes.RUNNING else HealthServicesExerciseTypes.WALKING
            SensorSample(SensorType.AUTO_EXERCISE, type.toDouble(), stretch.start, stretch.end)
        }
    }

    private fun stretches(steps: List<SensorSample>): List<Stretch> {
        val stretches = mutableListOf<Stretch>()
        steps.forEach { interval ->
            val end = interval.endedAt ?: return@forEach
            val duration = Duration.between(interval.recordedAt, end)
            if (duration < minInterval || duration > maxInterval) return@forEach
            if (interval.value / duration.toMillis() * MILLIS_PER_MINUTE < minCadence) return@forEach
            val current = stretches.lastOrNull()
            if (current != null && !interval.recordedAt.isAfter(current.end.plus(maxGap))) {
                current.end = maxOf(current.end, end)
                current.steps += interval.value
            } else {
                stretches += Stretch(interval.recordedAt, end, interval.value)
            }
        }
        return stretches
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000.0
        val STEPS = setOf(SensorType.STEPS)
        val WORKOUTS = setOf(SensorType.EXERCISE, SensorType.AUTO_EXERCISE)
    }
}
