package org.sand.ticwatch.phone.sleep

import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.health.clippedTo
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.SleepSession
import org.sand.ticwatch.phone.history.SleepStage
import org.sand.ticwatch.phone.history.SleepStageQueries
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** One recorded sleep: its stages clipped to the session, the day it ended on, its stored score and analytics, if any. */
data class SleepJournalEntry(
    val session: SleepSession,
    val wakeDay: LocalDate,
    val stages: List<SleepStage>,
    val score: Int?,
    val insights: SleepAdvancedInsights? = null,
) {
    val duration: Duration get() = Duration.between(session.start, session.end)

    val stageTotals: Map<SleepStageType, Duration>
        get() = stages.groupBy { it.type }.mapValues { (_, list) ->
            list.fold(Duration.ZERO) { sum, stage -> sum + Duration.between(stage.start, stage.end) }
        }
}

/** Past sleeps for the sleep journal screen. */
interface SleepJournal {
    /** Sleeps that started in [from, to), newest first. */
    suspend fun entries(from: Instant, to: Instant): List<SleepJournalEntry>

    /** Start of the earliest sleep overlapping the time before [before], or null when there is none. */
    suspend fun oldestStart(before: Instant): Instant?
}

/**
 * Reads the phone's own tables only, so [queries] and [nightInsights] must be local, never cloud-backed. Scores come
 * from the [SCORE_TIER] rollups: a score sample is stamped with its session's start, and raw rows don't last that long.
 */
class LocalSleepJournal(
    private val queries: SensorHistoryQueries,
    private val stages: SleepStageQueries,
    private val nightInsights: SleepNightInsightsSource,
    private val zone: ZoneId,
) : SleepJournal {

    override suspend fun entries(from: Instant, to: Instant): List<SleepJournalEntry> {
        val sessions = sessionsStarting(from, to)
        if (sessions.isEmpty()) return emptyList()
        val rangeEnd = sessions.maxOf { it.end }
        val allStages = stages.sleepStages(sessions.first().start, rangeEnd)
        val scores = queries.rollups(SensorType.SLEEP_SCORE, SCORE_TIER, sessions.first().start, rangeEnd)
            .associateBy { it.bucketStart }
        return sessions.asReversed().map { session ->
            SleepJournalEntry(
                session = session,
                wakeDay = session.end.atZone(zone).toLocalDate(),
                stages = allStages.filter { it.start < session.end && it.end > session.start }.clippedTo(session.start, session.end),
                score = scores[SCORE_TIER.bucketStart(session.start, zone)]?.average?.roundToInt(),
                insights = nightInsights.night(session),
            )
        }
    }

    override suspend fun oldestStart(before: Instant): Instant? =
        queries.sleepSessions(Instant.EPOCH, before, null, 1).firstOrNull()?.start

    private suspend fun sessionsStarting(from: Instant, to: Instant): List<SleepSession> = buildList {
        var afterStart: Instant? = null
        do {
            val page = queries.sleepSessions(from, to, afterStart, PAGE_SIZE)
            addAll(page.filter { !it.start.isBefore(from) })
            afterStart = page.lastOrNull()?.start
        } while (page.size == PAGE_SIZE)
    }

    private companion object {
        const val PAGE_SIZE = 100
        val SCORE_TIER = RollupResolution.HOUR
    }
}
