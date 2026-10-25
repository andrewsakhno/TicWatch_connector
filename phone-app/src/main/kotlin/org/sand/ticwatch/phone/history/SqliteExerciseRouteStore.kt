package org.sand.ticwatch.phone.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) : ExerciseRouteLog, ExerciseRouteQueries, ExerciseRouteMaintenance {

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
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            RoutePoint(
                                recordedAt = Instant.ofEpochMilli(cursor.getLong(0)),
                                source = RouteSource.fromCode(cursor.getInt(1)),
                                latitude = cursor.getDouble(2),
                                longitude = cursor.getDouble(3),
                                altitudeMeters = if (cursor.isNull(4)) null else cursor.getDouble(4),
                                horizontalAccuracyMeters = if (cursor.isNull(5)) null else cursor.getDouble(5),
                            ),
                        )
                    }
                }
            }
        }

    override suspend fun purgeRecordedBefore(cutoff: Instant): Int = withContext(Dispatchers.IO) {
        database.writableDatabase.delete(
            RoutePointTable.NAME,
            "${RoutePointTable.RECORDED_AT} < ?",
            arrayOf(cutoff.toEpochMilli().toString()),
        )
    }

    private companion object {
        val COLUMNS = arrayOf(
            RoutePointTable.RECORDED_AT,
            RoutePointTable.SOURCE,
            RoutePointTable.LATITUDE,
            RoutePointTable.LONGITUDE,
            RoutePointTable.ALTITUDE,
            RoutePointTable.ACCURACY,
        )
        val INSERT_SQL =
            "INSERT OR REPLACE INTO ${RoutePointTable.NAME} (${COLUMNS.joinToString(", ")}) VALUES (?, ?, ?, ?, ?, ?)"
    }
}
