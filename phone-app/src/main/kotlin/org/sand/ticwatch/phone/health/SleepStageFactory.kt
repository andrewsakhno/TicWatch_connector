package org.sand.ticwatch.phone.health

import androidx.health.connect.client.records.SleepSessionRecord
import org.sand.ticwatch.phone.history.SleepStageQueries
import java.time.Instant

/**
 * Builds a sleep session's Health Connect stages from the stages stored inside it. Health Connect
 * rejects a session whose stages fall outside it or overlap, so each stage is clipped to the session
 * and to the end of the stage before it; stages left empty by the clipping are dropped.
 */
class SleepStageFactory(private val stages: SleepStageQueries) {

    suspend fun stagesFor(start: Instant, end: Instant): List<SleepSessionRecord.Stage> {
        var previousEnd = start
        return stages.sleepStages(start, end).mapNotNull { stage ->
            val stageStart = maxOf(stage.start, previousEnd)
            val stageEnd = minOf(stage.end, end)
            if (!stageEnd.isAfter(stageStart)) return@mapNotNull null
            previousEnd = stageEnd
            SleepSessionRecord.Stage(startTime = stageStart, endTime = stageEnd, stage = stage.type.healthConnectType)
        }
    }
}
