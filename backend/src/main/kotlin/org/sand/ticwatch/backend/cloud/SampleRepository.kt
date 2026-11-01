package org.sand.ticwatch.backend.cloud

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/** Sample range reads and idempotent batch inserts over `sensor_sample`. */
interface SampleRepository {
    fun insert(deviceId: String, samples: List<SampleDto>): Int

    /** Up to [limit] samples of [types] in [from, to), ascending by (recorded_at, id), past the optional cursor. */
    fun find(types: Set<Int>, from: Long, to: Long, afterRecordedAt: Long?, afterId: Long?, limit: Int): List<StoredSampleDto>

    fun rollups(type: Int, resolution: RollupResolution, from: Long, to: Long, zone: String): List<RollupPointDto>
}

@Repository
class JdbcSampleRepository(private val jdbc: NamedParameterJdbcTemplate) : SampleRepository {

    @Transactional
    override fun insert(deviceId: String, samples: List<SampleDto>): Int {
        if (samples.isEmpty()) return 0
        val batch = samples.map { sample ->
            MapSqlParameterSource()
                .addValue("type", sample.type)
                .addValue("value", sample.value)
                .addValue("recordedAt", sample.recordedAt)
                .addValue("endedAt", sample.endedAt)
                .addValue("source", sample.source)
                .addValue("snapshot", sample.snapshot)
                .addValue("deviceId", deviceId)
        }.toTypedArray()
        return jdbc.batchUpdate(INSERT_SQL, batch).sumOf { maxOf(it, 0) }
    }

    override fun find(
        types: Set<Int>,
        from: Long,
        to: Long,
        afterRecordedAt: Long?,
        afterId: Long?,
        limit: Int,
    ): List<StoredSampleDto> {
        if (types.isEmpty()) return emptyList()
        val cursorClause = if (afterRecordedAt != null && afterId != null) CURSOR_CLAUSE else ""
        val params = MapSqlParameterSource()
            .addValue("types", types)
            .addValue("from", from)
            .addValue("to", to)
            .addValue("afterRecordedAt", afterRecordedAt)
            .addValue("afterId", afterId)
            .addValue("limit", limit)
        return jdbc.query(
            """
            SELECT id, type, value, recorded_at, ended_at, source, snapshot FROM sensor_sample
            WHERE type IN (:types) AND recorded_at >= :from AND recorded_at < :to $cursorClause
            ORDER BY recorded_at, id LIMIT :limit
            """.trimIndent(),
            params,
        ) { rs, _ ->
            StoredSampleDto(
                id = rs.getLong("id"),
                type = rs.getInt("type"),
                value = rs.getDouble("value"),
                recordedAt = rs.getLong("recorded_at"),
                endedAt = rs.getLong("ended_at").takeUnless { rs.wasNull() },
                source = rs.getInt("source"),
                snapshot = rs.getBoolean("snapshot"),
            )
        }
    }

    override fun rollups(type: Int, resolution: RollupResolution, from: Long, to: Long, zone: String): List<RollupPointDto> {
        val params = MapSqlParameterSource()
            .addValue("type", type)
            .addValue("from", from)
            .addValue("to", to)
            .addValue("zone", zone)
        return jdbc.query(
            """
            SELECT ${resolution.bucketSql} AS bucket, MIN(value) AS min_value, MAX(value) AS max_value,
                SUM(value) AS sum_value, COUNT(*) AS sample_count
            FROM sensor_sample WHERE type = :type AND recorded_at >= :from AND recorded_at < :to
            GROUP BY bucket ORDER BY bucket
            """.trimIndent(),
            params,
        ) { rs, _ ->
            RollupPointDto(
                bucketStart = rs.getLong("bucket"),
                min = rs.getDouble("min_value"),
                max = rs.getDouble("max_value"),
                sum = rs.getDouble("sum_value"),
                count = rs.getLong("sample_count"),
            )
        }
    }

    private companion object {
        const val INSERT_SQL = """
            INSERT INTO sensor_sample (type, value, recorded_at, ended_at, source, snapshot, device_id)
            VALUES (:type, :value, :recordedAt, :endedAt, :source, :snapshot, :deviceId)
            ON CONFLICT DO NOTHING
        """
        const val CURSOR_CLAUSE =
            "AND (recorded_at > :afterRecordedAt OR (recorded_at = :afterRecordedAt AND id > :afterId))"
    }
}
