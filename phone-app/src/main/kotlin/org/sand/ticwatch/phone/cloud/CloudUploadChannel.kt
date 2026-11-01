package org.sand.ticwatch.phone.cloud

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.sand.ticwatch.phone.ecg.EcgRecordingEntity
import org.sand.ticwatch.phone.health.OrphanSleepSessionDeriver
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.SensorHistoryRow
import org.sand.ticwatch.phone.history.SensorTypeCodes
import org.sand.ticwatch.phone.history.SleepSession
import org.sand.ticwatch.phone.history.SleepStage
import org.sand.ticwatch.phone.route.RoutePoint
import java.io.IOException
import java.time.Instant

/** One kind of local data drained to the server. */
interface CloudUploadChannel {
    /** Uploads the next batch; returns how many rows went up (0 = drained). */
    suspend fun uploadBatch(api: CloudApi): Int

    suspend fun pendingCount(): Long
}

/** The server's answer doesn't cover the whole batch, so none of it may be marked uploaded. */
class CloudUploadNotAcknowledgedException(sent: Int, acknowledged: Int) :
    IOException("Server acknowledged $acknowledged of $sent uploaded rows")

/**
 * Uploads [outbox] in batches of [batchSize]; rows are marked only once the server's [CloudUploadResult]
 * acknowledges the whole batch (it answers after committing), so any other answer leaves them pending.
 */
class OutboxUploadChannel<T>(
    private val outbox: CloudOutbox<T>,
    private val batchSize: Int,
    private val send: suspend (CloudApi, List<T>) -> CloudUploadResult,
) : CloudUploadChannel {

    override suspend fun uploadBatch(api: CloudApi): Int {
        val batch = outbox.pendingUpload(batchSize)
        if (batch.isEmpty()) return 0
        val acknowledged = send(api, batch).received
        if (acknowledged != batch.size) throw CloudUploadNotAcknowledgedException(batch.size, acknowledged)
        outbox.markUploaded(batch)
        return batch.size
    }

    override suspend fun pendingCount(): Long = outbox.pendingUploadCount()
}

/** Channels for everything the watch delivers: raw samples (all types), sleep tables, workout GPS fixes, rhythm strips. */
object CloudUploadChannels {
    private const val SAMPLE_BATCH = 2000
    private const val SLEEP_BATCH = 500
    private const val ROUTE_BATCH = 2000

    /** Strips carry ~30 s of 32 Hz pulse wave each. */
    private const val ECG_BATCH = 10

    fun samples(outbox: CloudOutbox<SensorHistoryRow>): CloudUploadChannel = OutboxUploadChannel(outbox, SAMPLE_BATCH) { api, rows ->
        api.uploadSamples(CloudSampleUpload(rows.map { it.toCloud() }))
    }

    /**
     * Sleep rows travel as the samples they were projected from: the server's unique index absorbs the ones the
     * raw channel already delivered, and the rows whose raw source is gone get there too.
     */
    fun sleepSessions(outbox: CloudOutbox<SleepSession>): CloudUploadChannel = OutboxUploadChannel(outbox, SLEEP_BATCH) { api, sessions ->
        api.uploadSamples(CloudSampleUpload(sessions.map { sleepSample(SensorType.SLEEP, OrphanSleepSessionDeriver.NO_SESSION_VALUE, it.start, it.end) }))
    }

    fun sleepStages(outbox: CloudOutbox<SleepStage>): CloudUploadChannel = OutboxUploadChannel(outbox, SLEEP_BATCH) { api, stages ->
        api.uploadSamples(CloudSampleUpload(stages.map { sleepSample(SensorType.SLEEP_STAGE, it.type.code.toDouble(), it.start, it.end) }))
    }

    private fun sleepSample(type: SensorType, value: Double, start: Instant, end: Instant) = CloudSample(
        type = SensorTypeCodes.code(type),
        value = value,
        recordedAt = start.toEpochMilli(),
        endedAt = end.toEpochMilli(),
        source = SampleSource.WATCH.code,
        snapshot = false,
    )

    fun routePoints(outbox: CloudOutbox<RoutePoint>): CloudUploadChannel = OutboxUploadChannel(outbox, ROUTE_BATCH) { api, points ->
        api.uploadRoutePoints(CloudRoutePointUpload(points.map { it.toCloud() }))
    }

    fun ecgRecordings(outbox: CloudOutbox<EcgRecordingEntity>): CloudUploadChannel = OutboxUploadChannel(outbox, ECG_BATCH) { api, rows ->
        api.uploadEcgRecordings(CloudEcgRecordingUpload(rows.map { it.toCloud() }))
    }

    private fun SensorHistoryRow.toCloud() = CloudSample(
        type = SensorTypeCodes.code(sample.type),
        value = sample.value,
        recordedAt = sample.recordedAt.toEpochMilli(),
        endedAt = sample.endedAt?.toEpochMilli(),
        source = sample.source.code,
        snapshot = sample.snapshot,
    )

    private fun RoutePoint.toCloud() = CloudRoutePoint(
        recordedAt = recordedAt.toEpochMilli(),
        source = source.code,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters,
        horizontalAccuracyMeters = horizontalAccuracyMeters,
    )

    /** The stored beats/wave JSON go up as-is, nested in one object per strip. */
    private fun EcgRecordingEntity.toCloud() = CloudEcgRecording(
        startedAt = startedAtMs,
        payload = JsonObject().apply {
            addProperty("startedAt", startedAtMs)
            addProperty("finishedAt", finishedAtMs)
            add("beats", JsonParser.parseString(beatsJson))
            add("wave", if (waveJson.isEmpty()) JsonNull.INSTANCE else JsonParser.parseString(waveJson))
        },
    )
}
