package org.sand.ticwatch.phone.history

import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SleepStageType
import java.time.Instant

/** Delivery state of one history row towards Google Health (Health Connect); [code] is the stored value. */
enum class DeliveryStatus(val code: Int) {
    PENDING(0),
    DELIVERED(1),
    FAILED(2);

    companion object {
        fun fromCode(code: Int): DeliveryStatus = entries.first { it.code == code }
    }
}

/**
 * Write side of the transfer history, used by the acknowledgement flow: rows are persisted
 * as [DeliveryStatus.PENDING] before the Health Connect write and updated once it answers.
 */
interface SensorHistoryLog {
    /** Persists [samples] as pending and returns their row ids in the same order. */
    suspend fun recordPending(samples: List<SensorSample>, receivedAt: Instant): List<Long>
    suspend fun markDelivery(ids: List<Long>, status: DeliveryStatus, at: Instant)

    /** Up to [limit] [DeliveryStatus.FAILED] rows with id greater than [afterId], ascending by id. */
    suspend fun findFailed(afterId: Long, limit: Int): List<SensorHistoryRow>
}

/** A stored history row: its id plus the sample it holds. */
data class SensorHistoryRow(val id: Long, val sample: SensorSample)

/** On-disk footprint of the transfer history (main file plus WAL/SHM side files). */
interface SensorHistoryStorageSize {
    suspend fun storageSizeBytes(): Long
}

/** Maintenance side of the transfer history: retention purge, wipe and on-disk size. */
interface SensorHistoryMaintenance : SensorHistoryStorageSize {
    /** Deletes raw rows received before [cutoff]; returns the number of deleted rows. */
    suspend fun purgeReceivedBefore(cutoff: Instant): Int

    /** Deletes [resolution] rollup buckets starting before [cutoff]; returns the number of deleted buckets. */
    suspend fun purgeRollupsBefore(resolution: RollupResolution, cutoff: Instant): Int

    /** Deletes sleep sessions and sleep stages starting before [cutoff]; returns the number of deleted sessions. */
    suspend fun purgeSleepSessionsBefore(cutoff: Instant): Int

    /** Shrinks the file after deletes (DELETE alone never returns space to the filesystem). */
    suspend fun compact()
    suspend fun clear()
}

/** Drops a stored night so it can be re-imported. */
fun interface SleepHistoryEraser {
    /** Deletes sleep sessions, sleep stages and their raw rows starting in [from, to); returns the deleted sessions. */
    suspend fun eraseSleep(from: Instant, to: Instant): Int
}

/** Aggregate of one rollup bucket (or of a whole range when summarised). */
data class RollupPoint(
    val bucketStart: Instant,
    val min: Double,
    val max: Double,
    val sum: Double,
    val count: Long,
) {
    val average: Double get() = sum / count
}

/** Time span a sensor type has rollup data for. */
data class MetricCoverage(val type: SensorType, val firstDay: Instant, val lastDay: Instant, val sampleCount: Long)

/** One sleep session, [start] to [end]. */
data class SleepSession(val start: Instant, val end: Instant)

/** One sleep stage interval; [sessionStart] identifies its enclosing [SleepSession], null until that arrives. */
data class SleepStage(val start: Instant, val end: Instant, val type: SleepStageType, val sessionStart: Instant?)

/**
 * Sleep stages from the long-lived `sleep_stage` table. Separate from [SensorHistoryQueries] (ISP):
 * the Health Connect sleep mapper needs only this.
 */
fun interface SleepStageQueries {
    /** Stages overlapping [from, to), ascending by start. */
    suspend fun sleepStages(from: Instant, to: Instant): List<SleepStage>
}

/** Position just after one raw row in (recorded_at, id) order - the resume point of a paged read. */
data class SampleCursor(val recordedAt: Instant, val id: Long)

/** A raw row as returned to readers: the sample plus its delivery bookkeeping. */
data class StoredSample(
    val id: Long,
    val sample: SensorSample,
    val receivedAt: Instant,
    val status: DeliveryStatus,
    val statusUpdatedAt: Instant?,
) {
    val cursor: SampleCursor get() = SampleCursor(sample.recordedAt, id)
}

/** Read side of the transfer history, used by the MCP tools. Ranges are half-open: [from, to). */
interface SensorHistoryQueries {
    /** Every sensor type with data, from the never-expiring [RollupResolution.DAY] tier. */
    suspend fun coverage(): List<MetricCoverage>

    /** [resolution] buckets of [type] overlapping [from, to), ascending. */
    suspend fun rollups(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant): List<RollupPoint>

    /** One aggregate over the [resolution] buckets overlapping [from, to), or null without data. */
    suspend fun summary(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant): RollupPoint?

    /**
     * Up to [limit] raw rows of [types] recorded in [from, to), ascending by (recorded_at, id);
     * with [after] set, only rows past that cursor - so pages never skip or repeat equal timestamps.
     */
    suspend fun rawSamples(
        types: Set<SensorType>,
        from: Instant,
        to: Instant,
        after: SampleCursor?,
        limit: Int,
    ): List<StoredSample>

    /**
     * Up to [limit] sleep sessions overlapping [from, to), ascending by start; with [afterStart]
     * set, only sessions starting after it (starts are unique, so this pages without gaps).
     */
    suspend fun sleepSessions(from: Instant, to: Instant, afterStart: Instant?, limit: Int): List<SleepSession>

    /** Raw-row count per delivery status for rows received in [from, to). */
    suspend fun deliveryCounts(from: Instant, to: Instant): Map<DeliveryStatus, Long>
}
