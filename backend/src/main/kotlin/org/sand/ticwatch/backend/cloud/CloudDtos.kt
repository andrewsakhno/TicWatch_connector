package org.sand.ticwatch.backend.cloud

import tools.jackson.databind.JsonNode

data class PingResponse(val service: String, val apiVersion: Int, val serverTime: Long)

/** One stored sample; times are epoch ms, [type]/[source] the phone's stable codes. */
data class SampleDto(
    val type: Int,
    val value: Double,
    val recordedAt: Long,
    val endedAt: Long?,
    val source: Int,
    val snapshot: Boolean,
)

data class SampleUpload(val samples: List<SampleDto>)

/** [stored] = newly inserted; the rest were already on the server. All [received] are safe to mark uploaded. */
data class UploadResult(val received: Int, val stored: Int)

/** A sample read back, with its server [id] as the paging tie-breaker. */
data class StoredSampleDto(
    val id: Long,
    val type: Int,
    val value: Double,
    val recordedAt: Long,
    val endedAt: Long?,
    val source: Int,
    val snapshot: Boolean,
)

data class RollupPointDto(val bucketStart: Long, val min: Double, val max: Double, val sum: Double, val count: Long)

data class RoutePointDto(
    val recordedAt: Long,
    val source: Int,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val horizontalAccuracyMeters: Double?,
)

data class RoutePointUpload(val points: List<RoutePointDto>)

/** One rhythm strip; [payload] is opaque to the server. */
data class EcgRecordingDto(val startedAt: Long, val payload: JsonNode)

data class EcgRecordingUpload(val recordings: List<EcgRecordingDto>)
