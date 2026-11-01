package org.sand.ticwatch.phone.history

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.cloud.CloudOutbox
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.SleepSessionTable
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.SleepStageTable
import java.time.Instant

internal val SLEEP_SESSION_COLUMNS = arrayOf(SleepSessionTable.START, SleepSessionTable.END)

internal val SLEEP_STAGE_COLUMNS =
    arrayOf(SleepStageTable.START, SleepStageTable.END, SleepStageTable.STAGE_TYPE, SleepStageTable.SESSION_START)

/** Reads the [SLEEP_SESSION_COLUMNS] of the current row. */
internal fun Cursor.readSleepSession() = SleepSession(Instant.ofEpochMilli(getLong(0)), Instant.ofEpochMilli(getLong(1)))

/** Reads the [SLEEP_STAGE_COLUMNS] of the current row; [SleepStageWriter] stores only known codes, so fromCode never misses here. */
internal fun Cursor.readSleepStage() = SleepStage(
    start = Instant.ofEpochMilli(getLong(0)),
    end = Instant.ofEpochMilli(getLong(1)),
    type = checkNotNull(SleepStageType.fromCode(getInt(2))),
    sessionStart = if (isNull(3)) null else Instant.ofEpochMilli(getLong(3)),
)

/**
 * [CloudOutbox] over one of the sleep tables, keyed by start time; all I/O runs on [Dispatchers.IO]. These tables
 * outlive the raw rows they were projected from, so they need their own acknowledgement before their purge.
 * A session or stage re-sent by the watch replaces its row and so starts unsynced again.
 */
class SqliteSleepOutbox<T> private constructor(
    private val database: SensorHistoryDatabase,
    private val table: String,
    private val startColumn: String,
    private val columns: Array<String>,
    private val startOf: (T) -> Instant,
    private val readRow: Cursor.() -> T,
) : CloudOutbox<T> {

    override suspend fun pendingUpload(limit: Int): List<T> = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            table, columns, PENDING_CLAUSE, null, null, null, "$startColumn ASC", limit.toString(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readRow()) } }
    }

    override suspend fun markUploaded(items: List<T>) {
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            val values = ContentValues().apply { put(SensorHistoryDatabase.SLEEP_CLOUD_SYNCED, SensorHistoryDatabase.CLOUD_SYNCED) }
            db.beginTransaction()
            try {
                items.forEach { item ->
                    db.update(table, values, "$startColumn = ?", arrayOf(startOf(item).toEpochMilli().toString()))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override suspend fun pendingUploadCount(): Long = withContext(Dispatchers.IO) {
        DatabaseUtils.longForQuery(database.readableDatabase, "SELECT COUNT(*) FROM $table WHERE $PENDING_CLAUSE", null)
    }

    companion object {
        private val PENDING_CLAUSE = "${SensorHistoryDatabase.SLEEP_CLOUD_SYNCED} = ${SensorHistoryDatabase.NOT_CLOUD_SYNCED}"

        fun sessions(database: SensorHistoryDatabase): CloudOutbox<SleepSession> = SqliteSleepOutbox(
            database, SleepSessionTable.NAME, SleepSessionTable.START, SLEEP_SESSION_COLUMNS, SleepSession::start,
        ) { readSleepSession() }

        fun stages(database: SensorHistoryDatabase): CloudOutbox<SleepStage> = SqliteSleepOutbox(
            database, SleepStageTable.NAME, SleepStageTable.START, SLEEP_STAGE_COLUMNS, SleepStage::start,
        ) { readSleepStage() }
    }
}
