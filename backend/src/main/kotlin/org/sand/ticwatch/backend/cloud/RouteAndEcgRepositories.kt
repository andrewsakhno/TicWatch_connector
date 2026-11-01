package org.sand.ticwatch.backend.cloud

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/** Workout GPS fixes; a re-sent fix (same time + source) replaces itself. */
interface RoutePointRepository {
    fun insert(deviceId: String, points: List<RoutePointDto>): Int

    /** Up to [limit] fixes recorded in [from, to), ascending by (recorded_at, source). */
    fun find(from: Long, to: Long, limit: Int): List<RoutePointDto>
}

/** Watch rhythm strips keyed by start time. */
fun interface EcgRecordingRepository {
    fun upsert(deviceId: String, recordings: List<EcgRecordingDto>): Int
}

@Repository
class JdbcRoutePointRepository(private val jdbc: NamedParameterJdbcTemplate) : RoutePointRepository {

    @Transactional
    override fun insert(deviceId: String, points: List<RoutePointDto>): Int {
        if (points.isEmpty()) return 0
        val batch = points.map { point ->
            MapSqlParameterSource()
                .addValue("recordedAt", point.recordedAt)
                .addValue("source", point.source)
                .addValue("latitude", point.latitude)
                .addValue("longitude", point.longitude)
                .addValue("altitude", point.altitudeMeters)
                .addValue("accuracy", point.horizontalAccuracyMeters)
                .addValue("deviceId", deviceId)
        }.toTypedArray()
        return jdbc.batchUpdate(
            """
            INSERT INTO route_point (recorded_at, source, latitude, longitude, altitude_m, accuracy_m, device_id)
            VALUES (:recordedAt, :source, :latitude, :longitude, :altitude, :accuracy, :deviceId)
            ON CONFLICT (recorded_at, source) DO UPDATE SET latitude = EXCLUDED.latitude,
                longitude = EXCLUDED.longitude, altitude_m = EXCLUDED.altitude_m, accuracy_m = EXCLUDED.accuracy_m
            """.trimIndent(),
            batch,
        ).sumOf { maxOf(it, 0) }
    }

    override fun find(from: Long, to: Long, limit: Int): List<RoutePointDto> = jdbc.query(
        """
        SELECT recorded_at, source, latitude, longitude, altitude_m, accuracy_m FROM route_point
        WHERE recorded_at >= :from AND recorded_at < :to ORDER BY recorded_at, source LIMIT :limit
        """.trimIndent(),
        MapSqlParameterSource().addValue("from", from).addValue("to", to).addValue("limit", limit),
    ) { rs, _ ->
        RoutePointDto(
            recordedAt = rs.getLong("recorded_at"),
            source = rs.getInt("source"),
            latitude = rs.getDouble("latitude"),
            longitude = rs.getDouble("longitude"),
            altitudeMeters = rs.getDouble("altitude_m").takeUnless { rs.wasNull() },
            horizontalAccuracyMeters = rs.getDouble("accuracy_m").takeUnless { rs.wasNull() },
        )
    }
}

@Repository
class JdbcEcgRecordingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : EcgRecordingRepository {

    @Transactional
    override fun upsert(deviceId: String, recordings: List<EcgRecordingDto>): Int {
        if (recordings.isEmpty()) return 0
        val batch = recordings.map { recording ->
            MapSqlParameterSource()
                .addValue("startedAt", recording.startedAt)
                .addValue("payload", objectMapper.writeValueAsString(recording.payload))
                .addValue("deviceId", deviceId)
        }.toTypedArray()
        return jdbc.batchUpdate(
            """
            INSERT INTO ecg_recording (started_at, payload, device_id) VALUES (:startedAt, CAST(:payload AS JSONB), :deviceId)
            ON CONFLICT (started_at) DO UPDATE SET payload = EXCLUDED.payload, received_at = now()
            """.trimIndent(),
            batch,
        ).sumOf { maxOf(it, 0) }
    }
}
