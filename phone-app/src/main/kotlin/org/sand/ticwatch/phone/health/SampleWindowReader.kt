package org.sand.ticwatch.phone.health

import org.sand.ticwatch.phone.history.SampleCursor
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import java.time.Instant

/**
 * Samples of a time range for derivers that need more than one push: the stored history plus the
 * current push, which is only written to the history after the derivers ran. A sample re-sent by the
 * watch appears in both and is kept once.
 */
class SampleWindowReader(private val queries: SensorHistoryQueries) {

    /** [types] samples recorded in [from, to), ascending by time. */
    suspend fun samples(types: Set<SensorType>, from: Instant, to: Instant, push: List<SensorSample>): List<SensorSample> {
        val stored = buildList {
            var after: SampleCursor? = null
            while (true) {
                val page = queries.rawSamples(types, from, to, after, PAGE_SIZE)
                page.forEach { add(it.sample) }
                if (page.size < PAGE_SIZE) break
                after = page.last().cursor
            }
        }
        val pushed = push.filter { it.type in types && !it.recordedAt.isBefore(from) && it.recordedAt.isBefore(to) }
        return (stored + pushed).distinct().sortedBy { it.recordedAt }
    }

    private companion object {
        const val PAGE_SIZE = 2_000
    }
}

/** Age-predicted maximum heart rate, Tanaka (2001): `208 - 0.7 * age`. */
object TanakaMaxHeartRate {
    private const val INTERCEPT = 208.0
    private const val AGE_SLOPE = 0.7

    fun bpm(ageYears: Int): Double = INTERCEPT - AGE_SLOPE * ageYears
}
