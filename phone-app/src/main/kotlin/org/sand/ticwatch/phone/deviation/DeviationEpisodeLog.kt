package org.sand.ticwatch.phone.deviation

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.sand.ticwatch.phone.cloud.CloudOutbox
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What the screen and the notification work from: episodes change push by push, across composition roots. */
interface DeviationEpisodeLog {
    /** Newest episodes, those waiting for an answer first; emits again on every change. */
    fun episodes(): Flow<List<DeviationEpisode>>

    /** Replaces the episode's answer; a late answer simply lands here, the corridors recalculate from its start. */
    suspend fun answer(id: Long, reasons: Set<DeviationReason>, intensity: DeviationIntensity?, at: Instant)
}

/** A candidate with what surrounded it, as the detector side hands it over. */
data class DetectedDeviation(val candidate: DeviationCandidate, val context: DeviationContext)

/** Written by the detector side. */
interface DeviationEpisodeSink {
    /**
     * Stores [detected]; one overlapping or just after a known episode extends it instead of asking again (the
     * episode keeps the context of its first detection).
     */
    suspend fun record(detected: List<DetectedDeviation>, detectedAt: Instant)

    /**
     * Hides unanswered episodes in a range that turned out to be a workout (its sample only arrives once it ended).
     * The rows stay, marked, because the server may already hold them.
     */
    suspend fun discardUnanswered(from: Instant, to: Instant, at: Instant)
}

/** What the advisor's post-stress window starts from. */
interface RecentDeviationEpisodes {
    /** The episode of [direction] in [state] that ended last at or after [since], discarded ones excluded. */
    suspend fun lastEnded(direction: DeviationDirection, state: RestState, since: Instant): DeviationEpisode?
}

/** What the notification still has to ask about. */
interface DeviationQuestionQueue {
    suspend fun unasked(since: Instant): List<DeviationEpisode>
    suspend fun askedSince(since: Instant): Int
    suspend fun markAsked(ids: List<Long>, at: Instant)
}

/**
 * [uid] is the server's key: local ids restart with a reinstall. Every change bumps [revision], and the row is pending
 * upload while [syncedRevision] differs, so a change made during an upload is not marked as delivered with it.
 * A [minor] episode is stored and uploaded but never listed or asked about. The columns from [thresholdBpm] on are
 * the detection's corridor and [DeviationContext], null on episodes found before v7.
 */
@Entity(tableName = "deviation_episode", indices = [Index("started_at"), Index("ended_at")])
data class DeviationEpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "uid") val uid: String,
    @ColumnInfo(name = "direction") val directionCode: Int,
    @ColumnInfo(name = "state") val stateCode: Int,
    @ColumnInfo(name = "started_at") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at") val endedAtMs: Long,
    @ColumnInfo(name = "peak_bpm") val peakBpm: Double,
    @ColumnInfo(name = "detected_at") val detectedAtMs: Long,
    @ColumnInfo(name = "asked_at") val askedAtMs: Long? = null,
    @ColumnInfo(name = "answered_at") val answeredAtMs: Long? = null,
    @ColumnInfo(name = "discarded_at") val discardedAtMs: Long? = null,
    @ColumnInfo(name = "revision") val revision: Int = 0,
    @ColumnInfo(name = "synced_revision") val syncedRevision: Int = NEVER_SYNCED,
    @ColumnInfo(name = "minor", defaultValue = "0") val minor: Boolean = false,
    @ColumnInfo(name = "intensity") val intensityCode: Int? = null,
    @ColumnInfo(name = "threshold_bpm") val thresholdBpm: Double? = null,
    @ColumnInfo(name = "baseline_bpm") val baselineBpm: Double? = null,
    @ColumnInfo(name = "excess_bpm_minutes") val excessBpmMinutes: Double? = null,
    @ColumnInfo(name = "worn_since") val wornSinceMs: Long? = null,
    @ColumnInfo(name = "steps_before") val stepsBefore: Double? = null,
    @ColumnInfo(name = "rmssd_before") val rmssdBefore: Double? = null,
    @ColumnInfo(name = "sleep_score") val sleepScore: Double? = null,
    @ColumnInfo(name = "fatigue_percent") val fatiguePercent: Double? = null,
    @ColumnInfo(name = "last_medication_at") val lastMedicationAtMs: Long? = null,
) {
    companion object {
        const val NEVER_SYNCED = -1
    }
}

/** One chosen reason of an episode; [reasonCode] is [DeviationReason.code]. */
@Entity(
    tableName = "deviation_episode_reason",
    primaryKeys = ["episode_id", "reason"],
    foreignKeys = [
        ForeignKey(
            entity = DeviationEpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DeviationEpisodeReasonEntity(
    @ColumnInfo(name = "episode_id") val episodeId: Long,
    @ColumnInfo(name = "reason") val reasonCode: Int,
)

data class DeviationEpisodeWithReasons(
    @Embedded val episode: DeviationEpisodeEntity,
    @Relation(parentColumn = "id", entityColumn = "episode_id") val reasons: List<DeviationEpisodeReasonEntity>,
)

@Dao
abstract class DeviationEpisodeDao {
    @Transaction
    @Query("SELECT * FROM deviation_episode WHERE discarded_at IS NULL AND minor = 0 ORDER BY answered_at IS NOT NULL, started_at DESC LIMIT :limit")
    abstract fun observe(limit: Int): Flow<List<DeviationEpisodeWithReasons>>

    @Query(
        "SELECT * FROM deviation_episode WHERE discarded_at IS NULL AND direction = :direction " +
            "AND ended_at >= :fromMs AND started_at <= :toMs ORDER BY started_at",
    )
    abstract suspend fun near(direction: Int, fromMs: Long, toMs: Long): List<DeviationEpisodeEntity>

    @Query(
        "SELECT * FROM deviation_episode WHERE discarded_at IS NULL AND minor = 0 AND direction = :direction AND state = :state " +
            "AND ended_at >= :sinceMs ORDER BY ended_at DESC LIMIT 1",
    )
    abstract suspend fun lastEnded(direction: Int, state: Int, sinceMs: Long): DeviationEpisodeEntity?

    @Insert
    abstract suspend fun insert(episode: DeviationEpisodeEntity): Long

    @Update
    abstract suspend fun update(episode: DeviationEpisodeEntity)

    @Query(
        "UPDATE deviation_episode SET discarded_at = :atMs, revision = revision + 1 " +
            "WHERE discarded_at IS NULL AND answered_at IS NULL AND ended_at >= :fromMs AND started_at <= :toMs",
    )
    abstract suspend fun discardUnanswered(fromMs: Long, toMs: Long, atMs: Long)

    @Query(
        "SELECT * FROM deviation_episode WHERE discarded_at IS NULL AND minor = 0 AND asked_at IS NULL AND answered_at IS NULL " +
            "AND ended_at >= :sinceMs ORDER BY started_at",
    )
    abstract suspend fun unasked(sinceMs: Long): List<DeviationEpisodeEntity>

    /** Episodes asked in one notification share its time, so this counts notifications. */
    @Query("SELECT COUNT(DISTINCT asked_at) FROM deviation_episode WHERE asked_at >= :sinceMs")
    abstract suspend fun askedSince(sinceMs: Long): Int

    @Query("UPDATE deviation_episode SET asked_at = :atMs, revision = revision + 1 WHERE id IN (:ids)")
    abstract suspend fun markAsked(ids: List<Long>, atMs: Long)

    @Query("UPDATE deviation_episode SET answered_at = :atMs, intensity = :intensity, revision = revision + 1 WHERE id = :id")
    abstract suspend fun markAnswered(id: Long, intensity: Int?, atMs: Long)

    @Transaction
    @Query("SELECT * FROM deviation_episode WHERE revision != synced_revision ORDER BY id LIMIT :limit")
    abstract suspend fun pendingUpload(limit: Int): List<DeviationEpisodeWithReasons>

    @Query("SELECT COUNT(*) FROM deviation_episode WHERE revision != synced_revision")
    abstract suspend fun pendingUploadCount(): Long

    @Query("UPDATE deviation_episode SET synced_revision = :revision WHERE id = :id")
    abstract suspend fun markUploaded(id: Long, revision: Int)

    @Query("DELETE FROM deviation_episode_reason WHERE episode_id = :id")
    abstract suspend fun clearReasons(id: Long)

    @Insert
    abstract suspend fun insertReasons(reasons: List<DeviationEpisodeReasonEntity>)

    /** In one transaction, since pushes arrive on several services at once. */
    @Transaction
    open suspend fun record(candidate: DeviationEpisodeEntity, mergeGapMs: Long) {
        val nearby = near(candidate.directionCode, candidate.startedAtMs - mergeGapMs, candidate.endedAtMs + mergeGapMs)
        val target = nearby.firstOrNull { it.endedAtMs >= candidate.startedAtMs && it.startedAtMs <= candidate.endedAtMs }
            ?: nearby.firstOrNull { it.answeredAtMs == null }
        if (target == null) {
            insert(candidate)
            return
        }
        val direction = DeviationDirection.of(target.directionCode)
        val startedAtMs = minOf(target.startedAtMs, candidate.startedAtMs)
        val endedAtMs = maxOf(target.endedAtMs, candidate.endedAtMs)
        val peakBpm = direction.peakOf(target.peakBpm, candidate.peakBpm)
        val threshold = target.thresholdBpm ?: candidate.thresholdBpm
        val merged = target.copy(
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            peakBpm = peakBpm,
            // A re-scan finds the same stretch again, so the area is the larger one, not a sum.
            excessBpmMinutes = listOfNotNull(target.excessBpmMinutes, candidate.excessBpmMinutes).maxOrNull(),
            minor = target.minor && candidate.minor && threshold != null &&
                DeviationSeverity.isMinor(Duration.ofMillis(endedAtMs - startedAtMs), direction.excessOf(peakBpm, threshold)),
        )
        // A re-scan usually finds nothing new; an update would wake every observer for nothing.
        if (merged != target) update(merged.copy(revision = target.revision + 1))
    }

    @Transaction
    open suspend fun answer(id: Long, reasons: List<DeviationEpisodeReasonEntity>, intensity: Int?, atMs: Long) {
        clearReasons(id)
        insertReasons(reasons)
        markAnswered(id, intensity, atMs)
    }
}

class RoomDeviationEpisodeLog(private val dao: DeviationEpisodeDao) :
    DeviationEpisodeLog, DeviationEpisodeSink, DeviationQuestionQueue, RecentDeviationEpisodes, CloudOutbox<DeviationEpisodeWithReasons> {

    override suspend fun pendingUpload(limit: Int): List<DeviationEpisodeWithReasons> = dao.pendingUpload(limit)

    override suspend fun markUploaded(items: List<DeviationEpisodeWithReasons>) =
        items.forEach { dao.markUploaded(it.episode.id, it.episode.revision) }

    override suspend fun pendingUploadCount(): Long = dao.pendingUploadCount()

    override fun episodes(): Flow<List<DeviationEpisode>> =
        dao.observe(MAX_LISTED).map { rows -> rows.map { it.episode.toDomain(it.reasons) } }

    override suspend fun answer(id: Long, reasons: Set<DeviationReason>, intensity: DeviationIntensity?, at: Instant) =
        dao.answer(id, reasons.map { DeviationEpisodeReasonEntity(id, it.code) }, intensity?.code, at.toEpochMilli())

    override suspend fun record(detected: List<DetectedDeviation>, detectedAt: Instant) = detected.forEach { (candidate, context) ->
        dao.record(
            DeviationEpisodeEntity(
                uid = UUID.randomUUID().toString(),
                directionCode = candidate.direction.code,
                stateCode = candidate.state.code,
                startedAtMs = candidate.start.toEpochMilli(),
                endedAtMs = candidate.end.toEpochMilli(),
                peakBpm = candidate.peakBpm,
                detectedAtMs = detectedAt.toEpochMilli(),
                minor = candidate.minor,
                thresholdBpm = candidate.thresholdBpm,
                baselineBpm = candidate.baselineBpm,
                excessBpmMinutes = candidate.excessBpmMinutes,
                wornSinceMs = candidate.wornSince?.toEpochMilli(),
                stepsBefore = context.stepsBefore,
                rmssdBefore = context.rmssdBefore,
                sleepScore = context.sleepScore,
                fatiguePercent = context.fatiguePercent,
                lastMedicationAtMs = context.lastMedicationAt?.toEpochMilli(),
            ),
            MERGE_GAP.toMillis(),
        )
    }

    override suspend fun discardUnanswered(from: Instant, to: Instant, at: Instant) =
        dao.discardUnanswered(from.toEpochMilli(), to.toEpochMilli(), at.toEpochMilli())

    override suspend fun unasked(since: Instant): List<DeviationEpisode> =
        dao.unasked(since.toEpochMilli()).map { it.toDomain(emptyList()) }

    override suspend fun lastEnded(direction: DeviationDirection, state: RestState, since: Instant): DeviationEpisode? =
        dao.lastEnded(direction.code, state.code, since.toEpochMilli())?.toDomain(emptyList())

    override suspend fun askedSince(since: Instant): Int = dao.askedSince(since.toEpochMilli())

    override suspend fun markAsked(ids: List<Long>, at: Instant) = dao.markAsked(ids, at.toEpochMilli())

    private fun DeviationEpisodeEntity.toDomain(rows: List<DeviationEpisodeReasonEntity>) = DeviationEpisode(
        id = id,
        direction = DeviationDirection.of(directionCode),
        state = RestState.of(stateCode),
        start = Instant.ofEpochMilli(startedAtMs),
        end = Instant.ofEpochMilli(endedAtMs),
        peakBpm = peakBpm,
        reasons = rows.mapNotNullTo(mutableSetOf()) { DeviationReason.of(it.reasonCode) },
        intensity = DeviationIntensity.of(intensityCode),
        answeredAt = answeredAtMs?.let(Instant::ofEpochMilli),
    )

    private companion object {
        const val MAX_LISTED = 200

        /** Close episodes make one question (preliminary). */
        val MERGE_GAP: Duration = Duration.ofMinutes(30)
    }
}
