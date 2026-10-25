package org.sand.ticwatch.watch.buffer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sand.ticwatch.watch.sensors.SensorSample
import org.sand.ticwatch.watch.sensors.SensorType
import java.time.Instant

/** [SensorSampleBuffer] backed by a plain SQLite table (no extra persistence dependency
 *  needed for a single append/scan/delete queue). */
class SqliteSensorSampleBuffer(context: Context) : SensorSampleBuffer {

    private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_TYPE TEXT NOT NULL,
                    $COL_VALUE REAL NOT NULL,
                    $COL_RECORDED_AT INTEGER NOT NULL,
                    $COL_ENDED_AT INTEGER
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Additive migration keeps any not-yet-delivered samples queued across app updates.
            if (oldVersion < VERSION_WITH_ENDED_AT) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_ENDED_AT INTEGER")
            }
        }
    }

    override suspend fun enqueue(sample: SensorSample): Unit = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(COL_TYPE, sample.type.name)
            put(COL_VALUE, sample.value)
            put(COL_RECORDED_AT, sample.recordedAt.toEpochMilli())
            put(COL_ENDED_AT, sample.endedAt?.toEpochMilli())
        }
        helper.writableDatabase.insert(TABLE, null, values)
        Unit
    }

    override suspend fun peek(limit: Int): List<BufferedSample> = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(
            TABLE,
            arrayOf(COL_ID, COL_TYPE, COL_VALUE, COL_RECORDED_AT, COL_ENDED_AT),
            null,
            null,
            null,
            null,
            "$COL_ID ASC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        BufferedSample(
                            id = cursor.getLong(0),
                            sample = SensorSample(
                                type = SensorType.valueOf(cursor.getString(1)),
                                value = cursor.getDouble(2),
                                recordedAt = Instant.ofEpochMilli(cursor.getLong(3)),
                                endedAt = if (cursor.isNull(4)) null else Instant.ofEpochMilli(cursor.getLong(4)),
                            ),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun remove(ids: List<Long>): Unit = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val placeholders = ids.joinToString(",") { "?" }
        helper.writableDatabase.delete(TABLE, "$COL_ID IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
        Unit
    }

    private companion object {
        const val DB_NAME = "pending_sensor_samples.db"
        const val VERSION_WITH_ENDED_AT = 2
        const val DB_VERSION = VERSION_WITH_ENDED_AT
        const val TABLE = "pending_samples"
        const val COL_ID = "id"
        const val COL_TYPE = "type"
        const val COL_VALUE = "value"
        const val COL_RECORDED_AT = "recorded_at_epoch_ms"
        const val COL_ENDED_AT = "ended_at_epoch_ms"
    }
}
