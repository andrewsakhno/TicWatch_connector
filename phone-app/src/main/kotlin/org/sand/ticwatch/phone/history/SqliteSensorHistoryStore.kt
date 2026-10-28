package org.sand.ticwatch.phone.history

import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.RollupTable
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.SleepSessionTable
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.SleepStageTable
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.Table
import java.time.Instant
import java.time.ZoneId

/**
 * [SensorHistoryLog] + [SensorHistoryMaintenance] + [SensorHistoryQueries] + [SleepStageQueries] over
 * [SensorHistoryDatabase]; all I/O runs on [Dispatchers.IO].
 */
class SqliteSensorHistoryStore(
    private val database: SensorHistoryDatabase,
    private val projections: List<HistoryProjection>,
    private val zone: ZoneId,
) : SensorHistoryLog, SensorHistoryMaintenance, SensorHistoryQueries, SleepStageQueries, SleepHistoryEraser,
    SampleEraser {

    override suspend fun recordPending(samples: List<SensorSample>, receivedAt: Instant): List<Long> =
        withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                val ids = samples.map { sample ->
                    val values = ContentValues().apply {
                        put(Table.TYPE, SensorTypeCodes.code(sample.type))
                        put(Table.VALUE, sample.value)
                        put(Table.RECORDED_AT, sample.recordedAt.toEpochMilli())
                        sample.endedAt?.let { put(Table.ENDED_AT, it.toEpochMilli()) }
                        put(Table.RECEIVED_AT, receivedAt.toEpochMilli())
                        put(Table.DELIVERY_STATUS, DeliveryStatus.PENDING.code)
                        put(Table.SOURCE, sample.source.code)
                        put(Table.IS_SNAPSHOT, if (sample.snapshot) SensorHistoryDatabase.SNAPSHOT else SensorHistoryDatabase.NOT_SNAPSHOT)
                    }
                    db.insertOrThrow(Table.NAME, null, values)
                }
                projections.forEach { it.accumulate(db, samples) }
                db.setTransactionSuccessful()
                ids
            } finally {
                db.endTransaction()
            }
        }

    override suspend fun markDelivery(ids: List<Long>, status: DeliveryStatus, at: Instant) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put(Table.DELIVERY_STATUS, status.code)
                put(Table.DELIVERY_UPDATED_AT, at.toEpochMilli())
            }
            db.beginTransaction()
            try {
                // Chunked to stay under SQLite's bound-parameter limit (999 on older devices).
                ids.chunked(MAX_BOUND_IDS).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    db.update(Table.NAME, values, "${Table.ID} IN ($placeholders)", chunk.map(Long::toString).toTypedArray())
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override suspend fun findFailed(afterId: Long, limit: Int): List<SensorHistoryRow> = withContext(Dispatchers.IO) {
        // The literal status matches the partial index's WHERE clause, so SQLite can use it.
        database.readableDatabase.query(
            Table.NAME,
            SensorHistoryDatabase.SAMPLE_COLUMNS + Table.ID,
            "${Table.DELIVERY_STATUS} = ${DeliveryStatus.FAILED.code} AND ${Table.ID} > ?",
            arrayOf(afterId.toString()),
            null,
            null,
            "${Table.ID} ASC",
            limit.toString(),
        ).use { cursor ->
            cursor.readAll { SensorHistoryRow(getLong(getColumnIndexOrThrow(Table.ID)), readSample()) }
        }
    }

    override suspend fun purgeReceivedBefore(cutoff: Instant): Int = withContext(Dispatchers.IO) {
        database.writableDatabase.delete(Table.NAME, "${Table.RECEIVED_AT} < ?", arrayOf(cutoff.toEpochMilli().toString()))
    }

    override suspend fun purgeRollupsBefore(resolution: RollupResolution, cutoff: Instant): Int =
        withContext(Dispatchers.IO) {
            database.writableDatabase.delete(
                RollupTable.NAME,
                "${RollupTable.RESOLUTION} = ? AND ${RollupTable.BUCKET_START} < ?",
                arrayOf(resolution.code.toString(), cutoff.toEpochMilli().toString()),
            )
        }

    override suspend fun purgeSleepSessionsBefore(cutoff: Instant): Int = withContext(Dispatchers.IO) {
        val db = database.writableDatabase
        val cutoffArgs = arrayOf(cutoff.toEpochMilli().toString())
        db.beginTransaction()
        try {
            db.delete(SleepStageTable.NAME, "${SleepStageTable.START} < ?", cutoffArgs)
            db.delete(SleepSessionTable.NAME, "${SleepSessionTable.START} < ?", cutoffArgs)
                .also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun eraseSleep(from: Instant, to: Instant): Int = withContext(Dispatchers.IO) {
        val db = database.writableDatabase
        val range = arrayOf(from.toEpochMilli().toString(), to.toEpochMilli().toString())
        val sleepTypes = listOf(SensorType.SLEEP, SensorType.SLEEP_STAGE).joinToString { SensorTypeCodes.code(it).toString() }
        db.beginTransaction()
        try {
            db.delete(Table.NAME, "${Table.TYPE} IN ($sleepTypes) AND ${Table.RECORDED_AT} >= ? AND ${Table.RECORDED_AT} < ?", range)
            db.delete(SleepStageTable.NAME, "${SleepStageTable.START} >= ? AND ${SleepStageTable.START} < ?", range)
            db.delete(SleepSessionTable.NAME, "${SleepSessionTable.START} >= ? AND ${SleepSessionTable.START} < ?", range)
                .also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun eraseSamples(types: Set<SensorType>, at: Instant): Int = withContext(Dispatchers.IO) {
        val codes = types.joinToString { SensorTypeCodes.code(it).toString() }
        database.writableDatabase.delete(
            Table.NAME, "${Table.TYPE} IN ($codes) AND ${Table.RECORDED_AT} = ?", arrayOf(at.toEpochMilli().toString()),
        )
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        val db = database.writableDatabase
        db.delete(Table.NAME, null, null)
        db.delete(RollupTable.NAME, null, null)
        db.delete(SleepSessionTable.NAME, null, null)
        db.delete(SleepStageTable.NAME, null, null)
        db.delete(SensorHistoryDatabase.RoutePointTable.NAME, null, null)
        compactNow()
    }

    override suspend fun storageSizeBytes(): Long = withContext(Dispatchers.IO) {
        database.files.sumOf { if (it.exists()) it.length() else 0L }
    }

    override suspend fun compact() = withContext(Dispatchers.IO) { compactNow() }

    /** VACUUM rebuilds the file, the checkpoint then truncates the WAL. */
    private fun compactNow() {
        val db = database.writableDatabase
        db.execSQL("VACUUM")
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    override suspend fun coverage(): List<MetricCoverage> = withContext(Dispatchers.IO) {
        database.readableDatabase.rawQuery(
            """
            SELECT ${RollupTable.TYPE}, MIN(${RollupTable.BUCKET_START}), MAX(${RollupTable.BUCKET_START}),
                SUM(${RollupTable.COUNT})
            FROM ${RollupTable.NAME} WHERE ${RollupTable.RESOLUTION} = ?
            GROUP BY ${RollupTable.TYPE} ORDER BY ${RollupTable.TYPE}
            """.trimIndent(),
            arrayOf(RollupResolution.DAY.code.toString()),
        ).use { cursor ->
            cursor.readAll {
                MetricCoverage(
                    type = SensorTypeCodes.type(getInt(0)),
                    firstDay = Instant.ofEpochMilli(getLong(1)),
                    lastDay = Instant.ofEpochMilli(getLong(2)),
                    sampleCount = getLong(3),
                )
            }
        }
    }

    override suspend fun rollups(
        type: SensorType,
        resolution: RollupResolution,
        from: Instant,
        to: Instant,
    ): List<RollupPoint> = withContext(Dispatchers.IO) {
        database.readableDatabase.rawQuery(
            """
            SELECT ${RollupTable.BUCKET_START}, ${RollupTable.MIN}, ${RollupTable.MAX}, ${RollupTable.SUM},
                ${RollupTable.COUNT}
            FROM ${RollupTable.NAME} WHERE $ROLLUP_RANGE_CLAUSE
            ORDER BY ${RollupTable.BUCKET_START}
            """.trimIndent(),
            rollupRangeArgs(type, resolution, from, to),
        ).use { cursor ->
            cursor.readAll {
                RollupPoint(Instant.ofEpochMilli(getLong(0)), getDouble(1), getDouble(2), getDouble(3), getLong(4))
            }
        }
    }

    override suspend fun summary(
        type: SensorType,
        resolution: RollupResolution,
        from: Instant,
        to: Instant,
    ): RollupPoint? = withContext(Dispatchers.IO) {
        database.readableDatabase.rawQuery(
            """
            SELECT MIN(${RollupTable.BUCKET_START}), MIN(${RollupTable.MIN}), MAX(${RollupTable.MAX}),
                SUM(${RollupTable.SUM}), SUM(${RollupTable.COUNT})
            FROM ${RollupTable.NAME} WHERE $ROLLUP_RANGE_CLAUSE
            """.trimIndent(),
            rollupRangeArgs(type, resolution, from, to),
        ).use { cursor ->
            // An aggregate without matching rows still yields one all-NULL row.
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                null
            } else {
                RollupPoint(
                    Instant.ofEpochMilli(cursor.getLong(0)),
                    cursor.getDouble(1),
                    cursor.getDouble(2),
                    cursor.getDouble(3),
                    cursor.getLong(4),
                )
            }
        }
    }

    override suspend fun rawSamples(
        types: Set<SensorType>,
        from: Instant,
        to: Instant,
        after: SampleCursor?,
        limit: Int,
    ): List<StoredSample> = withContext(Dispatchers.IO) {
        if (types.isEmpty()) return@withContext emptyList()
        val typePlaceholders = types.joinToString(",") { "?" }
        val cursorClause = after?.let {
            " AND (${Table.RECORDED_AT} > ? OR (${Table.RECORDED_AT} = ? AND ${Table.ID} > ?))"
        }.orEmpty()
        val cursorArgs = after?.let {
            val recordedAt = it.recordedAt.toEpochMilli().toString()
            listOf(recordedAt, recordedAt, it.id.toString())
        }.orEmpty()
        database.readableDatabase.query(
            Table.NAME,
            SensorHistoryDatabase.SAMPLE_COLUMNS +
                arrayOf(Table.ID, Table.RECEIVED_AT, Table.DELIVERY_STATUS, Table.DELIVERY_UPDATED_AT),
            "${Table.TYPE} IN ($typePlaceholders) AND ${Table.RECORDED_AT} >= ? AND ${Table.RECORDED_AT} < ?$cursorClause",
            (types.map { SensorTypeCodes.code(it).toString() } +
                listOf(from.toEpochMilli().toString(), to.toEpochMilli().toString()) + cursorArgs).toTypedArray(),
            null,
            null,
            "${Table.RECORDED_AT} ASC, ${Table.ID} ASC",
            limit.toString(),
        ).use { cursor ->
            cursor.readAll {
                val updatedAtColumn = getColumnIndexOrThrow(Table.DELIVERY_UPDATED_AT)
                StoredSample(
                    id = getLong(getColumnIndexOrThrow(Table.ID)),
                    sample = readSample(),
                    receivedAt = Instant.ofEpochMilli(getLong(getColumnIndexOrThrow(Table.RECEIVED_AT))),
                    status = DeliveryStatus.fromCode(getInt(getColumnIndexOrThrow(Table.DELIVERY_STATUS))),
                    statusUpdatedAt = if (isNull(updatedAtColumn)) null else Instant.ofEpochMilli(getLong(updatedAtColumn)),
                )
            }
        }
    }

    override suspend fun sleepSessions(
        from: Instant,
        to: Instant,
        afterStart: Instant?,
        limit: Int,
    ): List<SleepSession> = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            SleepSessionTable.NAME,
            arrayOf(SleepSessionTable.START, SleepSessionTable.END),
            "${SleepSessionTable.START} < ? AND ${SleepSessionTable.END} > ? AND ${SleepSessionTable.START} > ?",
            arrayOf(
                to.toEpochMilli().toString(),
                from.toEpochMilli().toString(),
                (afterStart?.toEpochMilli() ?: Long.MIN_VALUE).toString(),
            ),
            null,
            null,
            "${SleepSessionTable.START} ASC",
            limit.toString(),
        ).use { cursor ->
            cursor.readAll { SleepSession(Instant.ofEpochMilli(getLong(0)), Instant.ofEpochMilli(getLong(1))) }
        }
    }

    override suspend fun sleepStages(from: Instant, to: Instant): List<SleepStage> = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            SleepStageTable.NAME,
            arrayOf(SleepStageTable.START, SleepStageTable.END, SleepStageTable.STAGE_TYPE, SleepStageTable.SESSION_START),
            "${SleepStageTable.START} < ? AND ${SleepStageTable.END} > ?",
            arrayOf(to.toEpochMilli().toString(), from.toEpochMilli().toString()),
            null,
            null,
            "${SleepStageTable.START} ASC",
        ).use { cursor ->
            // SleepStageWriter stores only known codes, so fromCode never misses here.
            cursor.readAll {
                SleepStage(
                    start = Instant.ofEpochMilli(getLong(0)),
                    end = Instant.ofEpochMilli(getLong(1)),
                    type = checkNotNull(SleepStageType.fromCode(getInt(2))),
                    sessionStart = if (isNull(3)) null else Instant.ofEpochMilli(getLong(3)),
                )
            }
        }
    }

    override suspend fun deliveryCounts(from: Instant, to: Instant): Map<DeliveryStatus, Long> =
        withContext(Dispatchers.IO) {
            database.readableDatabase.rawQuery(
                """
                SELECT ${Table.DELIVERY_STATUS}, COUNT(*) FROM ${Table.NAME}
                WHERE ${Table.RECEIVED_AT} >= ? AND ${Table.RECEIVED_AT} < ?
                GROUP BY ${Table.DELIVERY_STATUS}
                """.trimIndent(),
                arrayOf(from.toEpochMilli().toString(), to.toEpochMilli().toString()),
            ).use { cursor ->
                cursor.readAll { DeliveryStatus.fromCode(getInt(0)) to getLong(1) }.toMap()
            }
        }

    /** Starts at the bucket containing [from], so a partially covered first bucket is included. */
    private fun rollupRangeArgs(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant) = arrayOf(
        SensorTypeCodes.code(type).toString(),
        resolution.code.toString(),
        resolution.bucketStart(from, zone).toEpochMilli().toString(),
        to.toEpochMilli().toString(),
    )

    private inline fun <T> Cursor.readAll(readRow: Cursor.() -> T): List<T> = buildList {
        while (moveToNext()) add(readRow())
    }

    private companion object {
        const val MAX_BOUND_IDS = 500
        val ROLLUP_RANGE_CLAUSE =
            "${RollupTable.TYPE} = ? AND ${RollupTable.RESOLUTION} = ? " +
                "AND ${RollupTable.BUCKET_START} >= ? AND ${RollupTable.BUCKET_START} < ?"
    }
}
