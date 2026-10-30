package org.sand.ticwatch.phone.cloud

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.DeliveryStatus
import org.sand.ticwatch.phone.history.RollupPoint
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SampleCursor
import org.sand.ticwatch.phone.history.SensorTypeCodes
import org.sand.ticwatch.phone.history.StoredSample
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** History reads from the cloud server; null = server not reachable (callers fall back to local data). */
interface CloudHistoryReader {
    suspend fun samples(types: Set<SensorType>, from: Instant, to: Instant, after: SampleCursor?, limit: Int): List<StoredSample>?
    suspend fun rollups(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant): List<RollupPoint>?
}

/**
 * Reads from the host the last sync found (never scans - that is the worker's job). After a failure it
 * stays silent for [BACKOFF], so screens re-reading on every push don't each wait out a connect timeout.
 */
class HttpCloudHistoryReader(
    private val settings: CloudSettingsStore,
    private val memory: CloudConnectionMemory,
    private val apis: CloudApiProvider,
    private val zone: ZoneId,
    private val clock: Clock,
) : CloudHistoryReader {

    @Volatile private var silentUntil: Instant = Instant.EPOCH

    override suspend fun samples(
        types: Set<SensorType>,
        from: Instant,
        to: Instant,
        after: SampleCursor?,
        limit: Int,
    ): List<StoredSample>? = read { api ->
        api.samples(
            types = types.joinToString(TYPE_SEPARATOR) { SensorTypeCodes.code(it).toString() },
            from = from.toEpochMilli(),
            to = to.toEpochMilli(),
            afterRecordedAt = after?.recordedAt?.toEpochMilli(),
            afterId = after?.id,
            limit = limit,
        ).mapNotNull { it.toStored() }
    }

    override suspend fun rollups(
        type: SensorType,
        resolution: RollupResolution,
        from: Instant,
        to: Instant,
    ): List<RollupPoint>? = read { api ->
        api.rollups(SensorTypeCodes.code(type), resolution.name, from.toEpochMilli(), to.toEpochMilli(), zone.id).map {
            RollupPoint(Instant.ofEpochMilli(it.bucketStart), it.min, it.max, it.sum, it.count)
        }
    }

    private suspend fun <T> read(call: suspend (CloudApi) -> T): T? {
        if (clock.instant().isBefore(silentUntil) || !settings.get().enabled) return null
        val host = memory.lastHost ?: return null
        return try {
            call(apis.api(host))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cloud history read from $host failed; local data only for ${BACKOFF.toMinutes()} min", e)
            silentUntil = clock.instant().plus(BACKOFF)
            null
        }
    }

    /** Server rows were stored on the phone and delivered before they were uploaded. Unknown codes are skipped. */
    private fun CloudStoredSample.toStored(): StoredSample? {
        val sensorType = runCatching { SensorTypeCodes.type(type) }.getOrNull() ?: return null
        val recorded = Instant.ofEpochMilli(recordedAt)
        return StoredSample(
            id = id,
            sample = SensorSample(
                type = sensorType,
                value = value,
                recordedAt = recorded,
                endedAt = endedAt?.let(Instant::ofEpochMilli),
                source = SampleSource.of(source),
                snapshot = snapshot,
            ),
            receivedAt = recorded,
            status = DeliveryStatus.DELIVERED,
            statusUpdatedAt = null,
        )
    }

    private companion object {
        const val TAG = "CloudHistoryReader"
        const val TYPE_SEPARATOR = ","
        val BACKOFF: Duration = Duration.ofMinutes(1)
    }
}
