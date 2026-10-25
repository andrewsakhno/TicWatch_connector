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
}
