package org.sand.ticwatch.phone.deviation

import org.sand.ticwatch.phone.health.WorkoutActivitySource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.SleepSession
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** The heart rate range of one [RestState] at rest; [baselineBpm] is the personal norm it was built from, null for the fixed fallback. */
data class HeartRateCorridor(val lowBpm: Double, val highBpm: Double, val baselineBpm: Double? = null) {

    fun directionOf(bpm: Double): DeviationDirection? = when {
        bpm > highBpm -> DeviationDirection.HIGH
        bpm < lowBpm -> DeviationDirection.LOW
        else -> null
    }

    fun thresholdOf(direction: DeviationDirection): Double = if (direction == DeviationDirection.HIGH) highBpm else lowBpm
}

/** One corridor per [RestState]. */
data class HeartRateCorridors(val awakeRest: HeartRateCorridor, val sleep: HeartRateCorridor) {

    fun of(state: RestState): HeartRateCorridor = when (state) {
        RestState.SLEEP -> sleep
        RestState.AWAKE_REST -> awakeRest
    }

    companion object {
        /** Until there is enough personal data: the first version's thresholds. */
        val FIXED = HeartRateCorridors(
            awakeRest = HeartRateCorridor(lowBpm = 52.0, highBpm = 90.0),
            sleep = HeartRateCorridor(lowBpm = 48.0, highBpm = 85.0),
        )
    }
}

/**
 * Personal corridor of one state from the mean heart rate of its 5-minute rest buckets: median + max(12 bpm, 2 robust SD)
 * (SD = 1.4826 x median absolute deviation, so episodes in the window barely move it). No split by hour of day: a few
 * days can't tell a daily rhythm from chance. The low edge never gets stricter than the fixed one, since low readings
 * haven't been looked at yet. All values preliminary, checked against the first week of data (2026-11-04).
 */
class PersonalCorridorCalculator {

    fun corridor(state: RestState, restBucketMeans: List<Double>): HeartRateCorridor {
        val fixed = HeartRateCorridors.FIXED.of(state)
        if (restBucketMeans.size < minBucketsOf(state)) return fixed
        val median = restBucketMeans.median()
        val spread = ROBUST_SD_FACTOR * restBucketMeans.map { abs(it - median) }.median()
        val margin = maxOf(MIN_MARGIN_BPM, SPREAD_MARGINS * spread)
        return HeartRateCorridor(lowBpm = minOf(fixed.lowBpm, median - margin), highBpm = median + margin, baselineBpm = median)
    }

    private fun minBucketsOf(state: RestState): Int = when (state) {
        RestState.AWAKE_REST -> MIN_AWAKE_BUCKETS
        RestState.SLEEP -> MIN_SLEEP_BUCKETS
    }

    private fun List<Double>.median(): Double = sorted().let { sorted ->
        val mid = sorted.size / 2
        if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private companion object {
        const val ROBUST_SD_FACTOR = 1.4826
        const val SPREAD_MARGINS = 2.0
        const val MIN_MARGIN_BPM = 12.0

        /** A day's worth of rest buckets awake, a night's asleep. */
        const val MIN_AWAKE_BUCKETS = 288
        const val MIN_SLEEP_BUCKETS = 96
    }
}

interface HeartRateCorridorSource {
    /** The corridors in force at [at], built from the data before it. */
    suspend fun corridors(at: Instant): HeartRateCorridors
}

/**
 * Decorator keeping the last corridors for [maxAge]: they move over days, while pushes (every 5 s during live sync)
 * would otherwise re-read two weeks of rollups each time.
 */
class CachingHeartRateCorridorSource(
    private val delegate: HeartRateCorridorSource,
    private val maxAge: Duration,
) : HeartRateCorridorSource {

    private class Cached(val at: Instant, val corridors: HeartRateCorridors)

    private val mutex = Mutex()
    private var cached: Cached? = null

    override suspend fun corridors(at: Instant): HeartRateCorridors = mutex.withLock {
        cached?.takeIf { Duration.between(it.at, at).abs() < maxAge }?.corridors
            ?: delegate.corridors(at).also { cached = Cached(at, it) }
    }
}

/**
 * Builds the corridors from the 5-minute rollups (kept 90 days, unlike raw rows) of the last [WINDOW]: a bucket is rest
 * outside every stored workout and its recovery, asleep when its middle lies in a stored sleep session.
 */
class RollupHeartRateCorridorSource(
    private val queries: SensorHistoryQueries,
    private val activity: WorkoutActivitySource,
    private val calculator: PersonalCorridorCalculator,
) : HeartRateCorridorSource {

    override suspend fun corridors(at: Instant): HeartRateCorridors {
        val from = at.minus(WINDOW)
        val heartRate = queries.rollups(SensorType.HEART_RATE, RollupResolution.FIVE_MINUTES, from, at)
        val active = activity.activity(from, at)
        val sleep = sleepSessions(from, at)
        val (asleep, awake) = heartRate
            .filter { !active.overlaps(it.bucketStart, it.bucketStart.plus(RollupResolution.FIVE_MINUTES.bucket)) }
            .partition { bucket -> bucket.bucketStart.plus(HALF_BUCKET).let { mid -> sleep.any { !mid.isBefore(it.start) && mid.isBefore(it.end) } } }
        return HeartRateCorridors(
            awakeRest = calculator.corridor(RestState.AWAKE_REST, awake.map { it.average }),
            sleep = calculator.corridor(RestState.SLEEP, asleep.map { it.average }),
        )
    }

    private suspend fun sleepSessions(from: Instant, to: Instant): List<SleepSession> = buildList {
        var after: Instant? = null
        while (true) {
            val page = queries.sleepSessions(from, to, after, SLEEP_PAGE)
            addAll(page)
            if (page.size < SLEEP_PAGE) break
            after = page.last().start
        }
    }

    private companion object {
        val WINDOW: Duration = Duration.ofDays(14)
        val HALF_BUCKET: Duration = Duration.ofSeconds(150)
        const val SLEEP_PAGE = 100
    }
}
