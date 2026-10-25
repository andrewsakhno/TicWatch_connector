package org.sand.ticwatch.phone.history

import android.database.sqlite.SQLiteDatabase
import org.sand.ticwatch.phone.health.SensorSample

/**
 * A derived table kept in step with the raw rows: fed every inserted batch inside the insert's
 * transaction, and replayed over existing raw rows when a schema upgrade introduces it.
 * Survives the raw-row purge, which is its whole point. The caller owns the transaction.
 */
interface HistoryProjection {
    fun accumulate(db: SQLiteDatabase, samples: List<SensorSample>)
}
