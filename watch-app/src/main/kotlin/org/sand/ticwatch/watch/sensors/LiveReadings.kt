package org.sand.ticwatch.watch.sensors

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant

/** Read side of the latest value per [SensorType], for displays that just render a snapshot. */
fun interface LatestReadings {
    fun current(type: SensorType): Double?
}

/** One value of a reading history. */
data class TimedReading(val at: Instant, val value: Double)

/** Recent values per [SensorType], oldest first, for trend graphs. */
fun interface ReadingHistory {
    fun history(type: SensorType): StateFlow<List<TimedReading>>
}

/**
 * The latest value of every [SensorType] collected this process lifetime, observable per
 * type, plus an in-memory trend of the last [historyWindow] thinned to one point per
 * [historySpacing] (the newest value of each slot wins, so per-second live heart rate stays
 * bounded). Owned by [SensorCollectionService] and handed to the app UI and the watch face over
 * its local binder; the trend starts empty after a service restart.
 */
class LiveReadings(
    private val historyWindow: Duration = DEFAULT_HISTORY_WINDOW,
    private val historySpacing: Duration = DEFAULT_HISTORY_SPACING,
) : LatestReadings, ReadingHistory {
    private val values: Map<SensorType, MutableStateFlow<Double?>> =
        SensorType.entries.associateWith { MutableStateFlow(null) }

    private val histories: Map<SensorType, MutableStateFlow<List<TimedReading>>> =
        SensorType.entries.associateWith { MutableStateFlow(emptyList()) }

    fun latest(type: SensorType): StateFlow<Double?> = values.getValue(type)

    override fun current(type: SensorType): Double? = values.getValue(type).value

    override fun history(type: SensorType): StateFlow<List<TimedReading>> = histories.getValue(type)

    fun record(sample: SensorSample) {
        values.getValue(sample.type).value = sample.value
        histories.getValue(sample.type).update { points -> appended(points, TimedReading(sample.recordedAt, sample.value)) }
    }

    private fun appended(points: List<TimedReading>, point: TimedReading): List<TimedReading> {
        val cutoff = point.at.minus(historyWindow)
        val last = points.lastOrNull()
        // Batches may arrive out of order; the trend only grows forward.
        if (last != null && point.at.isBefore(last.at)) return points
        val kept = points.dropWhile { it.at.isBefore(cutoff) }
        // Fixed slots: comparing against the last point's time would let a steady stream replace it forever.
        val replacesLast = last != null && kept.isNotEmpty() && slot(last.at) == slot(point.at)
        return if (replacesLast) kept.dropLast(1) + point else kept + point
    }

    private fun slot(at: Instant): Long = at.toEpochMilli() / historySpacing.toMillis()

    companion object {
        val DEFAULT_HISTORY_WINDOW: Duration = Duration.ofHours(6)
        val DEFAULT_HISTORY_SPACING: Duration = Duration.ofMinutes(1)
    }
}
