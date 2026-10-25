package org.sand.ticwatch.phone.health

import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.units.Length
import org.sand.ticwatch.phone.route.ExerciseRouteQueries
import org.sand.ticwatch.phone.route.RoutePoint
import java.time.Instant

/**
 * Builds a session's Health Connect [ExerciseRoute] from the GPS fixes recorded inside it. When
 * both the phone and the watch tracked the same workout, only the source with more fixes is
 * used: interleaving two receivers' fixes would draw a zigzag. Fixes Health Connect would reject
 * (out-of-range coordinates, negative accuracy) are dropped instead of sinking the session.
 */
class ExerciseRouteFactory(private val routes: ExerciseRouteQueries) {

    /** The route recorded in [start, end], or null when there are no usable fixes. */
    suspend fun routeFor(start: Instant, end: Instant): ExerciseRoute? {
        // Health Connect accepts fixes up to and including the session end.
        val points = routes.routePoints(start, end.plusMillis(INCLUSIVE_END_MS), after = null, limit = MAX_ROUTE_POINTS)
            .filter(::isValid)
        if (points.isEmpty()) return null
        val chosen = points.groupBy { it.source }.values.maxBy { it.size }
        return ExerciseRoute(chosen.sortedBy { it.recordedAt }.map(::toLocation))
    }

    private fun isValid(point: RoutePoint): Boolean =
        point.latitude in LATITUDE_RANGE &&
            point.longitude in LONGITUDE_RANGE &&
            (point.horizontalAccuracyMeters ?: 0.0) >= 0.0

    private fun toLocation(point: RoutePoint) = ExerciseRoute.Location(
        time = point.recordedAt,
        latitude = point.latitude,
        longitude = point.longitude,
        horizontalAccuracy = point.horizontalAccuracyMeters?.let(Length::meters),
        altitude = point.altitudeMeters?.let(Length::meters),
    )

    private companion object {
        const val INCLUSIVE_END_MS = 1L

        /** ~8 h at the phone tracker's 4 s interval; far beyond any realistic single workout. */
        const val MAX_ROUTE_POINTS = 7_200
        val LATITUDE_RANGE = -90.0..90.0
        val LONGITUDE_RANGE = -180.0..180.0
    }
}
