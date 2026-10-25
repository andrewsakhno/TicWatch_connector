package org.sand.ticwatch.phone.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class SensorSampleDto(val type: String, val value: Double, val recordedAtEpochMs: Long)
data class SyncRequest(val deviceId: String, val samples: List<SensorSampleDto>)
data class SyncResponse(val accepted: Int)

/** REST contract exposed by the backend module for pushing synced sensor data upstream. */
interface BackendApi {
    @POST("api/v1/sync")
    suspend fun sync(@Body request: SyncRequest): Response<SyncResponse>
}
