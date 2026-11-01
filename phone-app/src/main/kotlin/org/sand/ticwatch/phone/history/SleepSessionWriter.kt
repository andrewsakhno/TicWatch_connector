package org.sand.ticwatch.phone.history

import android.database.sqlite.SQLiteDatabase
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.SleepSessionTable
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.SleepStageTable

/**
 * Copies [SensorType.SLEEP] sessions into `sleep_session`, kept for the sleep retention chosen
 * in Settings (months) instead of the raw-row days. Keyed by start, so a session the watch
 * re-sends replaces itself instead of duplicating. Stages already stored inside a session are linked to it.
 * A session also supersedes any it overlaps (one the phone restored from orphan stages, see
 * `OrphanSleepSessionDeriver`, before the watch's own arrived with another start); their stages go back to orphans
 * until re-linked here.
 */
class SleepSessionWriter : HistoryProjection {

    override fun accumulate(db: SQLiteDatabase, samples: List<SensorSample>) {
        val sessions = samples.filter { it.type == SensorType.SLEEP && it.endedAt != null }
        if (sessions.isEmpty()) return
        val insert = db.compileStatement(
            "INSERT OR REPLACE INTO ${SleepSessionTable.NAME} " +
                "(${SleepSessionTable.START}, ${SleepSessionTable.END}) VALUES (?, ?)",
        )
        val linkStages = db.compileStatement(
            "UPDATE ${SleepStageTable.NAME} SET ${SleepStageTable.SESSION_START} = ?1 " +
                "WHERE ${SleepStageTable.START} >= ?1 AND ${SleepStageTable.START} < ?2",
        )
        insert.use { statement ->
            linkStages.use { link ->
                sessions.forEach { session ->
                    val start = session.recordedAt.toEpochMilli()
                    val end = checkNotNull(session.endedAt).toEpochMilli()
                    supersedeOverlapped(db, start, end)
                    statement.clearBindings()
                    statement.bindLong(1, start)
                    statement.bindLong(2, end)
                    statement.executeInsert()
                    link.clearBindings()
                    link.bindLong(1, start)
                    link.bindLong(2, end)
                    link.executeUpdateDelete()
                }
            }
        }
    }

    private fun supersedeOverlapped(db: SQLiteDatabase, start: Long, end: Long) {
        val overlapping = "${SleepSessionTable.START} < ?2 AND ${SleepSessionTable.END} > ?1"
        val range = arrayOf<Any>(start, end)
        // Stages first: the subquery reads the rows the delete removes.
        db.execSQL(
            "UPDATE ${SleepStageTable.NAME} SET ${SleepStageTable.SESSION_START} = NULL WHERE ${SleepStageTable.SESSION_START} IN " +
                "(SELECT ${SleepSessionTable.START} FROM ${SleepSessionTable.NAME} WHERE $overlapping)",
            range,
        )
        db.execSQL("DELETE FROM ${SleepSessionTable.NAME} WHERE $overlapping", range)
    }
}
