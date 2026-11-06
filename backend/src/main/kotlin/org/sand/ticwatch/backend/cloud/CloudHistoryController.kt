package org.sand.ticwatch.backend.cloud

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/** Discovery probe: phones scanning the subnet accept a host only when [CloudApi.SERVICE_NAME] answers. */
@RestController
class PingController(private val clock: Clock) {
    @GetMapping(CloudApi.PING)
    fun ping() = PingResponse(CloudApi.SERVICE_NAME, CloudApi.API_VERSION, clock.millis())
}

/** Uploads from the phone's cloud outbox and the history reads its charts fall back to. */
@RestController
class CloudHistoryController(
    private val samples: SampleRepository,
    private val routePoints: RoutePointRepository,
    private val ecgRecordings: EcgRecordingRepository,
    private val deviationEpisodes: DeviationEpisodeRepository,
) {
    @PostMapping(CloudApi.SAMPLES)
    fun uploadSamples(
        @RequestHeader(CloudApi.DEVICE_HEADER) deviceId: String,
        @RequestBody upload: SampleUpload,
    ) = UploadResult(upload.samples.size, samples.insert(deviceId, upload.samples))

    @GetMapping(CloudApi.SAMPLES)
    fun samples(
        @RequestParam types: Set<Int>,
        @RequestParam from: Long,
        @RequestParam to: Long,
        @RequestParam(required = false) afterRecordedAt: Long?,
        @RequestParam(required = false) afterId: Long?,
        @RequestParam limit: Int,
    ): List<StoredSampleDto> = samples.find(types, from, to, afterRecordedAt, afterId, pageSize(limit))

    @GetMapping(CloudApi.ROLLUPS)
    fun rollups(
        @RequestParam type: Int,
        @RequestParam resolution: RollupResolution,
        @RequestParam from: Long,
        @RequestParam to: Long,
        @RequestParam zone: String,
    ): List<RollupPointDto> = samples.rollups(type, resolution, from, to, zone)

    @PostMapping(CloudApi.ROUTE_POINTS)
    fun uploadRoutePoints(
        @RequestHeader(CloudApi.DEVICE_HEADER) deviceId: String,
        @RequestBody upload: RoutePointUpload,
    ) = UploadResult(upload.points.size, routePoints.insert(deviceId, upload.points))

    @GetMapping(CloudApi.ROUTE_POINTS)
    fun routePoints(
        @RequestParam from: Long,
        @RequestParam to: Long,
        @RequestParam limit: Int,
    ): List<RoutePointDto> = routePoints.find(from, to, pageSize(limit))

    @PostMapping(CloudApi.ECG_RECORDINGS)
    fun uploadEcgRecordings(
        @RequestHeader(CloudApi.DEVICE_HEADER) deviceId: String,
        @RequestBody upload: EcgRecordingUpload,
    ) = UploadResult(upload.recordings.size, ecgRecordings.upsert(deviceId, upload.recordings))

    @PostMapping(CloudApi.DEVIATION_EPISODES)
    fun uploadDeviationEpisodes(
        @RequestHeader(CloudApi.DEVICE_HEADER) deviceId: String,
        @RequestBody upload: DeviationEpisodeUpload,
    ) = UploadResult(upload.episodes.size, deviationEpisodes.upsert(deviceId, upload.episodes))

    private fun pageSize(requested: Int): Int = requested.coerceIn(1, CloudApi.MAX_PAGE_SIZE)
}
