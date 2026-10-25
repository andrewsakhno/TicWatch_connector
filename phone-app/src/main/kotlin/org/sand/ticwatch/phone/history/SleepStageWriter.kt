package org.sand.ticwatch.phone.history

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.SleepSessionTable
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.SleepStageTable

/**
 * Copies [SensorType.SLEEP_STAGE] intervals into `sleep_stage`, kept for the sleep retention like
 * `sleep_session`. Each stage is linked to the session containing its start when that session is
 * already stored; [SleepSessionWriter] links stages that arrived before their session. Keyed by
 * start, so a re-sent stage replaces itself. Stage codes outside [SleepStageType] are skipped.
 */
class SleepStageWriter : HistoryProjection {

    override fun accumulate(db: SQLiteDatabase, samples: List<SensorSample>) {
        val stages = samples.filter { it.type == SensorType.SLEEP_STAGE && it.endedAt != null }
        if (stages.isEmpty()) return
        val insert = db.compileStatement(
            "INSERT OR REPLACE INTO ${SleepStageTable.NAME} " +
                "(${SleepStageTable.START}, ${SleepStageTable.END}, ${SleepStageTable.STAGE_TYPE}, ${SleepStageTable.SESSION_START}) " +
                "VALUES (?1, ?2, ?3, (SELECT ${SleepSessionTable.START} FROM ${SleepSessionTable.NAME} " +
                "WHERE ${SleepSessionTable.START} <= ?1 AND ${SleepSessionTable.END} > ?1))",
        )
        insert.use { statement ->
            stages.forEach { stage ->
                val type = SleepStageType.fromCode(stage.value.toInt())
                if (type == null) {
                    Log.w(TAG, "Skipping sleep stage with unknown code: $stage")
                    return@forEach
                }
                statement.clearBindings()
                statement.bindLong(1, stage.recordedAt.toEpochMilli())
                statement.bindLong(2, checkNotNull(stage.endedAt).toEpochMilli())
                statement.bindLong(3, type.code.toLong())
                statement.executeInsert()
            }
        }
    }

    private companion object {
        const val TAG = "SleepStageWriter"
    }
}
