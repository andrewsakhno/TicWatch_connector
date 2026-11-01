package org.sand.ticwatch.phone.history

import android.database.Cursor
import android.database.DatabaseUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.cloud.CloudOutbox
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.RoutePointTable
import org.sand.ticwatch.phone.route.ExerciseRouteLog
import org.sand.ticwatch.phone.route.ExerciseRouteMaintenance
import org.sand.ticwatch.phone.route.ExerciseRouteQueries
import org.sand.ticwatch.phone.route.RouteSource
import org.sand.ticwatch.phone.route.RoutePoint
import java.time.Instant

/** Route history over `exercise_route_point` in [SensorHistoryDatabase]; all I/O runs on [Dispatchers.IO]. */
class SqliteExerciseRouteStore(
    private val database: SensorHistoryDatabase,
) : ExerciseRouteLog, ExerciseRouteQueries, ExerciseRouteMaintenance, CloudOutbox<RoutePoint> {

    override suspend fun insert(points: List<RoutePoint>) {
        if (points.isEmpty()) return
        withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                db.compileStatement(INSERT_SQL).use { statement ->
                    points.forEach { point ->
                        statement.clearBindings()
                        statement.bindLong(1, point.recordedAt.toEpochMilli())
                        statement.bindLong(2, point.source.code.toLong())
                        statement.bindDouble(3, point.latitude)
                        statement.bindDouble(4, point.longitude)
                        point.altitudeMeters?.let { statement.bindDouble(5, it) } ?: statement.bindNull(5)
                        point.horizontalAccuracyMeters?.let { statement.bindDouble(6, it) } ?: statement.bindNull(6)
                        statement.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override suspend fun routePoints(from: Instant, to: Instant, after: Instant?, limit: Int): List<RoutePoint> =
        withContext(Dispatchers.IO) {
            val lowerBound = after?.let { maxOf(it.toEpochMilli() + 1, from.toEpochMilli()) } ?: from.toEpochMilli()
            database.readableDatabase.query(
                RoutePointTable.NAME,
                COLUMNS,
                "${RoutePointTable.RECORDED_AT} >= ? AND ${RoutePointTable.RECORDED_AT} < ?",
                arrayOf(lowerBound.toString(), to.toEpochMilli().toString()),
                null,
                null,
                "${RoutePointTable.RECORDED_AT} ASC, ${RoutePointTable.SOURCE} ASC",
                limit.toString(),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readPoint()) } }
        }

    override suspend fun purgeRecordedBefore(cutoff: Instant, keepUnsynced: Boolean): Int = withContext(Dispatchers.IO) {
        database.writableDatabase.delete(
            RoutePointTable.NAME,
            "${RoutePointTable.RECORDED_AT} < ?${SensorHistoryDatabase.purgeableClause(RoutePointTable.CLOUD_SYNCED, keepUnsynced)}",
            arrayOf(cutoff.toEpochMilli().toString()),
        )
    }

    override suspend fun pendingUpload(limit: Int): List<RoutePoint> = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            RoutePointTable.NAME,
            COLUMNS,
            "${RoutePointTable.CLOUD_SYNCED} = ${SensorHistoryDatabase.NOT_CLOUD_SYNCED}",
            null,
            null,
            null,
            "${RoutePointTable.RECORDED_AT} ASC, ${RoutePointTable.SOURCE} ASC",
            limit.toString(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readPoint()) } }
    }

    override suspend fun markUploaded(items: List<RoutePoint>) {
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                db.compileStatement(MARK_UPLOADED_SQL).use { statement ->
                    items.forEach { point ->
                        statement.clearBindings()
                        statement.bindLong(1, point.recordedAt.toEpochMilli())
                        statement.bindLong(2, point.source.code.toLong())
                        statement.executeUpdateDelete()
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override suspend fun pendingUploadCount(): Long = withContext(Dispatchers.IO) {
        DatabaseUtils.longForQuery(
            database.readableDatabase,
            "SELECT COUNT(*) FROM ${RoutePointTable.NAME} " +
                "WHERE ${RoutePointTable.CLOUD_SYNCED} = ${SensorHistoryDatabase.NOT_CLOUD_SYNCED}",
            null,
        )
    }

    private fun Cursor.readPoint() = RoutePoint(
        recordedAt = Instant.ofEpochMilli(getLong(0)),
        source = RouteSource.fromCode(getInt(1)),
        latitude = getDouble(2),
        longitude = getDouble(3),
        altitudeMeters = if (isNull(4)) null else getDouble(4),
        horizontalAccuracyMeters = if (isNull(5)) null else getDouble(5),
    )

    private companion object {
        val COLUMNS = arrayOf(
            RoutePointTable.RECORDED_AT,
            RoutePointTable.SOURCE,
            RoutePointTable.LATITUDE,
            RoutePointTable.LONGITUDE,
            RoutePointTable.ALTITUDE,
            RoutePointTable.ACCURACY,
        )
        // A replaced (re-sent) fix starts unsynced again; the server's upsert absorbs the repeat.
        val INSERT_SQL =
            "INSERT OR REPLACE INTO ${RoutePointTable.NAME} (${COLUMNS.joinToString(", ")}) VALUES (?, ?, ?, ?, ?, ?)"
        val MARK_UPLOADED_SQL =
            "UPDATE ${RoutePointTable.NAME} SET ${RoutePointTable.CLOUD_SYNCED} = ${SensorHistoryDatabase.CLOUD_SYNCED} " +
                "WHERE ${RoutePointTable.RECORDED_AT} = ? AND ${RoutePointTable.SOURCE} = ?"
    }
}
