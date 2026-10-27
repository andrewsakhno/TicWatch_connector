package org.sand.ticwatch.phone.health

import androidx.health.connect.client.records.SleepSessionRecord
import org.sand.ticwatch.phone.history.SleepStage
import org.sand.ticwatch.phone.history.SleepStageQueries
import java.time.Duration
import java.time.Instant

/** Estimates a night's stages from other signals when the watch's sleep sensor left too few. */
fun interface SleepStageEstimator {
    suspend fun estimate(start: Instant, end: Instant): List<SleepStage>
}

/**
 * Builds a sleep session's Health Connect stages from the stages stored inside it - or, when those cover
 * less than [minStoredCoverage] of the session, from [fallback]. Health Connect rejects a session whose
 * stages fall outside it or overlap, so each stage is clipped to the session and to the end of the stage
 * before it; stages left empty by the clipping are dropped.
 */
class SleepStageFactory(
    private val stages: SleepStageQueries,
    private val fallback: SleepStageEstimator? = null,
    private val minStoredCoverage: Double = 0.5,
) {

    suspend fun stagesFor(start: Instant, end: Instant): List<SleepSessionRecord.Stage> {
        val stored = clip(stages.sleepStages(start, end), start, end)
        val estimator = fallback ?: return stored
        if (coverage(stored, start, end) >= minStoredCoverage) return stored
        return clip(estimator.estimate(start, end), start, end).ifEmpty { stored }
    }

    private fun clip(source: List<SleepStage>, start: Instant, end: Instant): List<SleepSessionRecord.Stage> {
        var previousEnd = start
        return source.mapNotNull { stage ->
            val stageStart = maxOf(stage.start, previousEnd)
            val stageEnd = minOf(stage.end, end)
            if (!stageEnd.isAfter(stageStart)) return@mapNotNull null
            previousEnd = stageEnd
            SleepSessionRecord.Stage(startTime = stageStart, endTime = stageEnd, stage = stage.type.healthConnectType)
        }
    }

    private fun coverage(stages: List<SleepSessionRecord.Stage>, start: Instant, end: Instant): Double {
        val session = Duration.between(start, end).toMillis()
        if (session <= 0) return 1.0
        return stages.sumOf { Duration.between(it.startTime, it.endTime).toMillis() }.toDouble() / session
    }
}
