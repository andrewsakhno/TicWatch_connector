package org.sand.ticwatch.phone.cloud

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.sand.ticwatch.phone.ecg.EcgRecordingEntity
import org.sand.ticwatch.phone.history.SensorHistoryRow
import org.sand.ticwatch.phone.history.SensorTypeCodes
import org.sand.ticwatch.phone.route.RoutePoint

/** One kind of local data drained to the server. */
interface CloudUploadChannel {
    /** Uploads the next batch; returns how many rows went up (0 = drained). */
    suspend fun uploadBatch(api: CloudApi): Int

    suspend fun pendingCount(): Long
}

/** Uploads [outbox] in batches of [batchSize]; rows are marked only after [send] returned (the server stored them). */
class OutboxUploadChannel<T>(
    private val outbox: CloudOutbox<T>,
    private val batchSize: Int,
    private val send: suspend (CloudApi, List<T>) -> Unit,
) : CloudUploadChannel {

    override suspend fun uploadBatch(api: CloudApi): Int {
        val batch = outbox.pendingUpload(batchSize)
        if (batch.isEmpty()) return 0
        send(api, batch)
        outbox.markUploaded(batch)
        return batch.size
    }

    override suspend fun pendingCount(): Long = outbox.pendingUploadCount()
}

/** Channels for everything the watch delivers: raw samples (all types), workout GPS fixes, rhythm strips. */
object CloudUploadChannels {
    private const val SAMPLE_BATCH = 2000
    private const val ROUTE_BATCH = 2000

    /** Strips carry ~30 s of 32 Hz pulse wave each. */
    private const val ECG_BATCH = 10

    fun samples(outbox: CloudOutbox<SensorHistoryRow>): CloudUploadChannel = OutboxUploadChannel(outbox, SAMPLE_BATCH) { api, rows ->
        api.uploadSamples(CloudSampleUpload(rows.map { it.toCloud() }))
    }

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
