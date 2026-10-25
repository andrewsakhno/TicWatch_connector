package org.sand.ticwatch.phone.history

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.RollupTable
import java.time.ZoneId

/**
 * Folds samples into every [RollupResolution] tier of `sensor_rollup` incrementally (min/max/
 * sum/count, so coarser re-aggregation stays exact). Incremental on insert rather than
 * recomputed from raw rows: late watch batches still land in their bucket, and purging raw
 * rows never shrinks an existing rollup. The caller owns the transaction.
 */
class SensorRollupWriter(private val zone: ZoneId) : HistoryProjection {

    override fun accumulate(db: SQLiteDatabase, samples: List<SensorSample>) {
        if (samples.isEmpty()) return
        val buckets = samples
            .flatMap { sample ->
                RollupResolution.entries.map { resolution ->
                    BucketKey(
                        typeCode = SensorTypeCodes.code(sample.type),
                        resolutionCode = resolution.code,
                        bucketStartMillis = resolution.bucketStart(sample.recordedAt, zone).toEpochMilli(),
                    ) to sample.value
                }
            }
            .groupBy({ it.first }, { it.second })

        // SQLite on minSdk 26 predates UPSERT (3.24), so update first and insert on a miss.
        val update = db.compileStatement(
            """
            UPDATE ${RollupTable.NAME} SET
                ${RollupTable.MIN} = MIN(${RollupTable.MIN}, ?),
                ${RollupTable.MAX} = MAX(${RollupTable.MAX}, ?),
                ${RollupTable.SUM} = ${RollupTable.SUM} + ?,
                ${RollupTable.COUNT} = ${RollupTable.COUNT} + ?
            WHERE ${RollupTable.TYPE} = ? AND ${RollupTable.RESOLUTION} = ? AND ${RollupTable.BUCKET_START} = ?
            """.trimIndent(),
        )
        val insert = db.compileStatement(
            """
            INSERT INTO ${RollupTable.NAME} (
                ${RollupTable.MIN}, ${RollupTable.MAX}, ${RollupTable.SUM}, ${RollupTable.COUNT},
                ${RollupTable.TYPE}, ${RollupTable.RESOLUTION}, ${RollupTable.BUCKET_START}
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
        update.use {
            insert.use {
                buckets.forEach { (key, values) ->
                    update.bindBucket(key, values)
                    if (update.executeUpdateDelete() == 0) {
                        insert.bindBucket(key, values)
                        insert.executeInsert()
                    }
                }
            }
        }
    }

    /** Binds the shared parameter order of both statements: min, max, sum, count, then the key. */
    private fun SQLiteStatement.bindBucket(key: BucketKey, values: List<Double>) {
        clearBindings()
        bindDouble(1, values.min())
        bindDouble(2, values.max())
        bindDouble(3, values.sum())
        bindLong(4, values.size.toLong())
        bindLong(5, key.typeCode.toLong())
        bindLong(6, key.resolutionCode.toLong())
        bindLong(7, key.bucketStartMillis)
    }

    private data class BucketKey(val typeCode: Int, val resolutionCode: Int, val bucketStartMillis: Long)
}
