package org.sand.ticwatch.phone.health

import org.sand.ticwatch.phone.history.SleepSession
import org.sand.ticwatch.phone.history.SleepStage
import org.sand.ticwatch.phone.history.SleepStageQueries
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Sleep sessions stored in a time range; only what [OrphanSleepSessionDeriver] needs of the history. */
fun interface StoredSleepSessions {
    suspend fun overlapping(from: Instant, to: Instant): List<SleepSession>
}

/**
 * Restores a [SensorType.SLEEP] session the watch never delivered (its one SLEEP sample got lost) from stages
 * stored without a session. Derived rather than written straight into `sleep_session`, so the session takes the
 * normal path: `SleepSessionWriter` stores it and links its stages, Health Connect gets its record, the night
 * derivers after this one run on it. Idempotent: once linked, those stages are no longer orphans; a real SLEEP
 * arriving later with the same start replaces it (record id by start). Checked at most every [checkInterval],
 * since pushes come every few seconds during live sync.
 */
class OrphanSleepSessionDeriver(
    private val stages: SleepStageQueries,
    private val sessions: StoredSleepSessions,
    private val clock: Clock,
    private val lookBack: Duration = Duration.ofDays(7),
    private val settle: Duration = Duration.ofHours(3),
    private val maxStageGap: Duration = Duration.ofMinutes(30),
    private val minSleep: Duration = Duration.ofMinutes(15),
    private val checkInterval: Duration = Duration.ofMinutes(15),
) : SampleDeriver {

    @Volatile
    private var lastCheckAt: Instant? = null

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> {
        val now = clock.instant()
        if (lastCheckAt?.let { Duration.between(it, now) < checkInterval } == true) return emptyList()
        lastCheckAt = now
        val from = now.minus(lookBack)
        val known = sessions.overlapping(from, now) + samples.filter { it.type == SensorType.SLEEP }
            .mapNotNull { sample -> sample.endedAt?.let { SleepSession(sample.recordedAt, it) } }
        return orphanRuns(stages.sleepStages(from, now))
            // A night still open on the watch keeps sending stages; it closes by itself well within [settle].
            .filter { run -> Duration.between(run.last().end, now) >= settle }
            .mapNotNull(::sessionOf)
            .filterNot { candidate -> known.any { it.start < candidate.end && candidate.start < it.end } }
            .map { SensorSample(SensorType.SLEEP, NO_SESSION_VALUE, it.start, it.end) }
    }

    /** Runs of consecutive session-less stages; a stage linked to a session or a long gap ends a run. */
    private fun orphanRuns(stored: List<SleepStage>): List<List<SleepStage>> {
        val runs = mutableListOf<MutableList<SleepStage>>()
        var open: MutableList<SleepStage>? = null
        stored.forEach { stage ->
            if (stage.sessionStart != null) {
                open = null
                return@forEach
            }
            val current = open?.takeIf { Duration.between(it.last().end, stage.start) <= maxStageGap }
                ?: mutableListOf<SleepStage>().also(runs::add)
            current += stage
            open = current
        }
        return runs
    }

    /** The run from its first to its last actual sleep stage, when these add up to [minSleep]. */
    private fun sessionOf(run: List<SleepStage>): SleepSession? {
        val asleep = run.filter { it.type != SleepStageType.AWAKE }
        val slept = asleep.fold(Duration.ZERO) { total, stage -> total + Duration.between(stage.start, stage.end) }
        if (slept < minSleep) return null
        return SleepSession(asleep.first().start, asleep.last().end)
    }

    companion object {
        /** A SLEEP sample's value carries nothing (watch `StateSessionTracker.NO_SESSION_VALUE`). */
        const val NO_SESSION_VALUE = 0.0
    }
}
