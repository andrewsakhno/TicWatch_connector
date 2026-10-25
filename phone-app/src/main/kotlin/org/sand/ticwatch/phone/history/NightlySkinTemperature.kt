package org.sand.ticwatch.phone.history

import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SkinBaselineSource
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * One night's wrist temperature against the personal baseline: [meanSkin] over the sleep
 * session, [baseline] the median of up to [NightlySkinTemperature.baselineNights] earlier
 * nights (null until enough exist), [delta] their difference. Sleep is the one daily stretch
 * where ambient, posture and activity are about constant, so this delta - not the absolute
 * skin value - is what tracks core shifts (fever, cycle phase, illness onset).
 */
data class NightlySkinReading(
    val session: SleepSession,
    val meanSkin: Double,
    val baseline: Double?,
    val baselineNightCount: Int,
) {
    val delta: Double? get() = baseline?.let { meanSkin - it }
}

/**
 * Computes [NightlySkinReading]s from the sleep sessions and the SKIN_TEMPERATURE rollups,
 * which outlive the raw retention (the finest tier still covering a night is used; the day
 * tier is too coarse to cut a night out of and is never used).
 */
class NightlySkinTemperature(
    private val queries: SensorHistoryQueries,
    private val clock: Clock,
    private val baselineNights: Int = 14,
    private val minBaselineNights: Int = 3,
) : SkinBaselineSource {
    /** Nights starting in [from, to), oldest first; earlier nights are read only to seed the baseline. */
    suspend fun nights(from: Instant, to: Instant): List<NightlySkinReading> {
        val now = clock.instant()
        val seeded = sessions(from.minus(BASELINE_LOOKBACK), to).mapNotNull { session ->
            meanSkin(session, now)?.let { session to it }
        }
        return seeded.mapIndexedNotNull { index, (session, mean) ->
            if (session.start.isBefore(from)) return@mapIndexedNotNull null
            val earlier = seeded.subList(maxOf(0, index - baselineNights), index).map { it.second }
            NightlySkinReading(
                session = session,
                meanSkin = mean,
                baseline = if (earlier.size >= minBaselineNights) median(earlier) else null,
                baselineNightCount = earlier.size,
            )
        }
    }

    /** Median mean skin of the last [baselineNights] nights that ended before [before]. */
    override suspend fun nightlyBaseline(before: Instant): Double? {
        val now = clock.instant()
        val means = sessions(before.minus(BASELINE_LOOKBACK), before)
            .filter { !it.end.isAfter(before) }
            .mapNotNull { meanSkin(it, now) }
            .takeLast(baselineNights)
        return if (means.size >= minBaselineNights) median(means) else null
    }

    private suspend fun sessions(from: Instant, to: Instant): List<SleepSession> = buildList {
        var afterStart: Instant? = null
        while (true) {
            val page = queries.sleepSessions(from, to, afterStart, PAGE_SIZE)
            addAll(page)
            if (page.size < PAGE_SIZE) break
            afterStart = page.last().start
        }
    }

    private suspend fun meanSkin(session: SleepSession, now: Instant): Double? {
        val resolution = NIGHT_RESOLUTIONS.firstOrNull { it.covers(session.start, now) } ?: return null
        return queries.summary(SensorType.SKIN_TEMPERATURE, resolution, session.start, session.end)?.average
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }

    private companion object {
        const val PAGE_SIZE = 500
        val NIGHT_RESOLUTIONS = listOf(RollupResolution.FIVE_MINUTES, RollupResolution.HOUR)

        /** Enough calendar time to find the baseline nights even with gaps. */
        val BASELINE_LOOKBACK: Duration = Duration.ofDays(30)
    }
}
