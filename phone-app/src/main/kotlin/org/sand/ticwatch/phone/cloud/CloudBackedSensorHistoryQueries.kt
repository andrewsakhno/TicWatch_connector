package org.sand.ticwatch.phone.cloud

import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.RawHistoryHorizon
import org.sand.ticwatch.phone.history.RollupPoint
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SampleCursor
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.StoredSample
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [SensorHistoryQueries] that reads like the phone still had everything: the part of a range older than
 * what is kept locally comes from the cloud server, the rest from [local]. Raw rows split at the oldest
 * local raw row of the requested types ([RawHistoryHorizon]); rollup tiers split at their own retention
 * (DAY is kept forever, so it never leaves the phone). Without a reachable server it is [local] alone.
 */
class CloudBackedSensorHistoryQueries(
    private val local: SensorHistoryQueries,
    private val horizon: RawHistoryHorizon,
    private val remote: CloudHistoryReader,
    private val clock: Clock,
    private val zone: ZoneId,
) : SensorHistoryQueries by local {

    override suspend fun rawSamples(
        types: Set<SensorType>,
        from: Instant,
        to: Instant,
        after: SampleCursor?,
        limit: Int,
    ): List<StoredSample> {
        if (types.isEmpty()) return emptyList()
        val split = (horizon.oldestRecordedAt(types) ?: to).coerceIn(from, to)
        val cursorIsRemote = after == null || after.recordedAt.isBefore(split)
        if (!from.isBefore(split) || !cursorIsRemote) return local.rawSamples(types, from, to, after, limit)
        // Remote cursors only ever point before the split, local ones at or after it, so ids never mix.
        val older = remote.samples(types, from, split, after, limit).orEmpty()
        if (older.size >= limit) return older
        return older + local.rawSamples(types, split, to, null, limit - older.size)
    }

    override suspend fun rollups(
        type: SensorType,
        resolution: RollupResolution,
        from: Instant,
        to: Instant,
    ): List<RollupPoint> {
        val split = localRollupStart(resolution, from, to) ?: return local.rollups(type, resolution, from, to)
        val older = remote.rollups(type, resolution, resolution.bucketStart(from, zone), split)
            ?: return local.rollups(type, resolution, from, to)
        return older + local.rollups(type, resolution, split, to)
    }

    override suspend fun summary(
        type: SensorType,
        resolution: RollupResolution,
        from: Instant,
        to: Instant,
    ): RollupPoint? {
        if (localRollupStart(resolution, from, to) == null) return local.summary(type, resolution, from, to)
        val points = rollups(type, resolution, from, to)
        if (points.isEmpty()) return null
        return RollupPoint(
            bucketStart = points.first().bucketStart,
            min = points.minOf { it.min },
            max = points.maxOf { it.max },
            sum = points.sumOf { it.sum },
            count = points.sumOf { it.count },
        )
    }

    /** First bucket the phone still holds for [resolution], when [from, to) reaches before it; else null. */
    private fun localRollupStart(resolution: RollupResolution, from: Instant, to: Instant): Instant? {
        val retention = resolution.retention ?: return null
        val start = resolution.bucketStart(clock.instant().minus(retention), zone)
        return start.takeIf { from.isBefore(it) }?.coerceAtMost(to)
    }
}
