package org.sand.ticwatch.phone.route

import java.time.Instant

/** Which device recorded a [RoutePoint]; [code] is the stored value (explicit, never `ordinal`). */
enum class RouteSource(val code: Int) {
    PHONE(0),
    WATCH(1);

    companion object {
        fun fromCode(code: Int): RouteSource = entries.first { it.code == code }
    }
}

/**
 * One GPS fix of a workout. Points are not keyed by a session id: the watch only reports an
 * EXERCISE session once it has ended, so points are joined to their session by time range.
 */
data class RoutePoint(
    val recordedAt: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val horizontalAccuracyMeters: Double?,
    val source: RouteSource,
)

/** Write side of the route history. Re-sending a point (same time and source) replaces it. */
interface ExerciseRouteLog {
    suspend fun insert(points: List<RoutePoint>)
}

/** Read side of the route history. Ranges are half-open: [from, to). */
interface ExerciseRouteQueries {
    /**
     * Up to [limit] points recorded in [from, to), ascending by (time, source); with [after] set,
     * only points recorded after it.
     */
    suspend fun routePoints(from: Instant, to: Instant, after: Instant?, limit: Int): List<RoutePoint>
}

/** Retention side of the route history, run by the daily purge ("Clear history" wipes the whole database). */
interface ExerciseRouteMaintenance {
    /** Deletes points recorded before [cutoff]; returns the number of deleted points. */
    suspend fun purgeRecordedBefore(cutoff: Instant): Int
}
