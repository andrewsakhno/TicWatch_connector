package org.sand.ticwatch.phone.history

import android.util.Log
import org.sand.ticwatch.phone.health.HealthRecordWriter
import org.sand.ticwatch.phone.health.SampleDeriver
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Derives BODY_TEMPERATURE for retained SKIN_TEMPERATURE rows that have none yet (rows from
 * builds before the estimator existed, or pushes that failed mid-way). Each reading goes
 * through the same [estimator] as the live path, with the history rows of [contextSpans]
 * around it as context, so the activity filters apply retrospectively too. An estimate whose
 * timestamp already has a BODY_TEMPERATURE row is skipped, which makes a re-run a no-op.
 * Estimates are written through [writer] - the acknowledging one, so they land in the
 * history (and are retried) like live samples.
 */
class BodyTemperatureBackfill(
    private val queries: SensorHistoryQueries,
    private val estimator: SampleDeriver,
    private val writer: HealthRecordWriter,
    private val clock: Clock,
    private val contextSpans: List<ContextSpan> = DEFAULT_CONTEXT_SPANS,
) {
    /** History types read before a skin reading, [lookback] back from its window end. */
    data class ContextSpan(val types: Set<SensorType>, val lookback: Duration)

    /** Returns the number of estimates written. */
    suspend fun run(): Int {
        var cursor: SampleCursor? = null
        val pending = mutableListOf<SensorSample>()
        var written = 0
        while (true) {
            val page = queries.rawSamples(SKIN_TYPES, Instant.EPOCH, clock.instant(), cursor, PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { stored ->
                pending += estimator.derive(listOf(stored.sample) + context(stored.sample))
                    .filter { it.type == SensorType.BODY_TEMPERATURE && !hasEstimateAt(it.recordedAt) }
            }
            if (pending.size >= WRITE_BATCH) written += flush(pending)
            cursor = page.last().cursor
        }
        return written + flush(pending)
    }

    private suspend fun context(skin: SensorSample): List<SensorSample> {
        val end = (skin.endedAt ?: skin.recordedAt).plusMillis(1)
        return contextSpans.flatMap { span -> readAll(span.types, end.minus(span.lookback), end) }
    }

    private suspend fun hasEstimateAt(at: Instant): Boolean =
        queries.rawSamples(BODY_TYPES, at, at.plusMillis(1), null, 1).isNotEmpty()

    private suspend fun readAll(types: Set<SensorType>, from: Instant, to: Instant): List<SensorSample> = buildList {
        var cursor: SampleCursor? = null
        while (true) {
            val page = queries.rawSamples(types, from, to, cursor, PAGE_SIZE)
            page.mapTo(this) { it.sample }
            if (page.size < PAGE_SIZE) break
            cursor = page.last().cursor
        }
    }

    private suspend fun flush(pending: MutableList<SensorSample>): Int {
        if (pending.isEmpty()) return 0
        writer.write(pending.toList()).onFailure { Log.w(TAG, "Backfilled body temperature write failed", it) }
        return pending.size.also { pending.clear() }
    }

    private companion object {
        const val TAG = "BodyTemperatureBackfill"
        const val PAGE_SIZE = 1_000
        const val WRITE_BATCH = 200
        val SKIN_TYPES = setOf(SensorType.SKIN_TEMPERATURE)
        val BODY_TYPES = setOf(SensorType.BODY_TEMPERATURE)

        /** Covers the qualifiers' lookbacks; day-scale for sparse rows (sessions, daily resting HR). */
        val DEFAULT_CONTEXT_SPANS = listOf(
            ContextSpan(setOf(SensorType.HEART_RATE, SensorType.STEPS, SensorType.AMBIENT_TEMPERATURE), Duration.ofMinutes(45)),
            ContextSpan(setOf(SensorType.EXERCISE, SensorType.RESTING_HEART_RATE, SensorType.SLEEP), Duration.ofDays(2)),
        )
    }
}
