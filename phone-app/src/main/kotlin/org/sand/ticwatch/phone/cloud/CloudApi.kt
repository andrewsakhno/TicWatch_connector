package org.sand.ticwatch.phone.cloud

import com.google.gson.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * REST contract of the cloud history server (`backend/`, `cloud/CloudApi.kt` there - keep both in sync).
 * Type/source fields are the stable [org.sand.ticwatch.phone.history.SensorTypeCodes] /
 * [org.sand.ticwatch.phone.health.SampleSource] codes, times epoch ms.
 */
interface CloudApi {
    @GET(PING)
    suspend fun ping(): CloudPing

    @POST(SAMPLES)
    suspend fun uploadSamples(@Body upload: CloudSampleUpload): CloudUploadResult

    @GET(SAMPLES)
    suspend fun samples(
        @Query("types") types: String,
        @Query("from") from: Long,
        @Query("to") to: Long,
        @Query("afterRecordedAt") afterRecordedAt: Long?,
        @Query("afterId") afterId: Long?,
        @Query("limit") limit: Int,
    ): List<CloudStoredSample>

    @GET(ROLLUPS)
    suspend fun rollups(
        @Query("type") type: Int,
        @Query("resolution") resolution: String,
        @Query("from") from: Long,
        @Query("to") to: Long,
        @Query("zone") zone: String,
    ): List<CloudRollupPoint>

    @POST(ROUTE_POINTS)
    suspend fun uploadRoutePoints(@Body upload: CloudRoutePointUpload): CloudUploadResult

    @POST(ECG_RECORDINGS)
    suspend fun uploadEcgRecordings(@Body upload: CloudEcgRecordingUpload): CloudUploadResult

    companion object {
        const val PORT = 8766
        const val SERVICE_NAME = "ticwatch-cloud"
        const val DEVICE_HEADER = "X-Device-Id"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        private const val BASE = "api/v1"
        const val PING = "$BASE/ping"
        const val SAMPLES = "$BASE/samples"
        const val ROLLUPS = "$BASE/rollups"
        const val ROUTE_POINTS = "$BASE/route-points"
        const val ECG_RECORDINGS = "$BASE/ecg-recordings"
    }
}

data class CloudPing(val service: String?, val apiVersion: Int, val serverTime: Long)

data class CloudSample(
    val type: Int,
    val value: Double,
    val recordedAt: Long,
    val endedAt: Long?,
    val source: Int,
    val snapshot: Boolean,
)

data class CloudSampleUpload(val samples: List<CloudSample>)

data class CloudUploadResult(val received: Int, val stored: Int)

data class CloudStoredSample(
    val id: Long,
    val type: Int,
    val value: Double,
    val recordedAt: Long,
    val endedAt: Long?,
    val source: Int,
    val snapshot: Boolean,
)

data class CloudRollupPoint(val bucketStart: Long, val min: Double, val max: Double, val sum: Double, val count: Long)

data class CloudRoutePoint(
    val recordedAt: Long,
    val source: Int,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val horizontalAccuracyMeters: Double?,
)

data class CloudRoutePointUpload(val points: List<CloudRoutePoint>)

data class CloudEcgRecording(val startedAt: Long, val payload: JsonElement)

data class CloudEcgRecordingUpload(val recordings: List<CloudEcgRecording>)
