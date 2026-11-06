package org.sand.ticwatch.backend.cloud

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Types

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

/** Heart rate deviation episodes and their answers, keyed by the phone's uid. */
fun interface DeviationEpisodeRepository {
    fun upsert(deviceId: String, episodes: List<DeviationEpisodeDto>): Int
}

@Repository
class JdbcDeviationEpisodeRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : DeviationEpisodeRepository {

    @Transactional
    override fun upsert(deviceId: String, episodes: List<DeviationEpisodeDto>): Int {
        if (episodes.isEmpty()) return 0
        val batch = episodes.map { episode ->
            MapSqlParameterSource()
                .addValue("uid", episode.uid)
                .addValue("direction", episode.direction)
                .addValue("state", episode.state)
                .addValue("startedAt", episode.startedAt)
                .addValue("endedAt", episode.endedAt)
                .addValue("peakBpm", episode.peakBpm)
                .addValue("reasons", objectMapper.writeValueAsString(episode.reasons))
                .addValue("detectedAt", episode.detectedAt)
                .addValue("askedAt", episode.askedAt, Types.BIGINT)
                .addValue("answeredAt", episode.answeredAt, Types.BIGINT)
                .addValue("discardedAt", episode.discardedAt, Types.BIGINT)
                .addValue("minor", episode.minor)
                .addValue("intensity", episode.intensity, Types.SMALLINT)
                .addValue("thresholdBpm", episode.thresholdBpm, Types.DOUBLE)
                .addValue("baselineBpm", episode.baselineBpm, Types.DOUBLE)
                .addValue("excessBpmMinutes", episode.excessBpmMinutes, Types.DOUBLE)
                .addValue("wornSince", episode.wornSince, Types.BIGINT)
                .addValue("stepsBefore", episode.stepsBefore, Types.DOUBLE)
                .addValue("rmssdBefore", episode.rmssdBefore, Types.DOUBLE)
                .addValue("sleepScore", episode.sleepScore, Types.DOUBLE)
                .addValue("fatiguePercent", episode.fatiguePercent, Types.DOUBLE)
                .addValue("lastMedicationAt", episode.lastMedicationAt, Types.BIGINT)
                .addValue("deviceId", deviceId)
        }.toTypedArray()
        return jdbc.batchUpdate(
            """
            INSERT INTO deviation_episode (uid, direction, state, started_at, ended_at, peak_bpm, reasons,
                detected_at, asked_at, answered_at, discarded_at, device_id,
                minor, intensity, threshold_bpm, baseline_bpm, excess_bpm_minutes, worn_since, steps_before, rmssd_before, sleep_score, fatigue_percent, last_medication_at)
            VALUES (:uid, :direction, :state, :startedAt, :endedAt, :peakBpm, CAST(:reasons AS JSONB),
                :detectedAt, :askedAt, :answeredAt, :discardedAt, :deviceId,
                :minor, :intensity, :thresholdBpm, :baselineBpm, :excessBpmMinutes, :wornSince, :stepsBefore,
                :rmssdBefore, :sleepScore, :fatiguePercent, :lastMedicationAt)
            ON CONFLICT (uid) DO UPDATE SET direction = EXCLUDED.direction, state = EXCLUDED.state,
                started_at = EXCLUDED.started_at, ended_at = EXCLUDED.ended_at, peak_bpm = EXCLUDED.peak_bpm,
                reasons = EXCLUDED.reasons, detected_at = EXCLUDED.detected_at, asked_at = EXCLUDED.asked_at,
                answered_at = EXCLUDED.answered_at, discarded_at = EXCLUDED.discarded_at,
                minor = EXCLUDED.minor, intensity = EXCLUDED.intensity, threshold_bpm = EXCLUDED.threshold_bpm, baseline_bpm = EXCLUDED.baseline_bpm, excess_bpm_minutes = EXCLUDED.excess_bpm_minutes, worn_since = EXCLUDED.worn_since, steps_before = EXCLUDED.steps_before, rmssd_before = EXCLUDED.rmssd_before, sleep_score = EXCLUDED.sleep_score, fatigue_percent = EXCLUDED.fatigue_percent, last_medication_at = EXCLUDED.last_medication_at, received_at = now()
            """.trimIndent(),
            batch,
        ).sumOf { maxOf(it, 0) }
    }
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
