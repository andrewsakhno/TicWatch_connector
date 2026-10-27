package org.sand.ticwatch.phone.health

import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.units.Length
import org.sand.ticwatch.phone.route.RoutePoint
import java.time.Instant

/**
 * Builds a session's Health Connect [ExerciseRoute] and distance laps from the GPS fixes recorded
 * inside it ([SessionRoutePoints] picks the source and drops unusable fixes).
 */
class ExerciseRouteFactory(private val routePoints: SessionRoutePoints, private val lapMeters: Double = DEFAULT_LAP_METERS) {

    /** The route recorded in [start, end], or null when there are no usable fixes. */
    suspend fun routeFor(start: Instant, end: Instant): ExerciseRoute? {
        val points = routePoints.pointsFor(start, end)
        if (points.isEmpty()) return null
        return ExerciseRoute(points.map(::toLocation))
    }

    /** One lap per [lapMeters] of the route (the last one partial), or none without a route. */
    suspend fun lapsFor(start: Instant, end: Instant): List<ExerciseLap> =
        ExerciseTrack(routePoints.pointsFor(start, end)).laps(lapMeters, start, end)
            .filter { it.end.isAfter(it.start) && !it.end.isAfter(end) }
            .map { ExerciseLap(startTime = it.start, endTime = it.end, length = Length.meters(it.meters)) }

    private fun toLocation(point: RoutePoint) = ExerciseRoute.Location(
        time = point.recordedAt,
        latitude = point.latitude,
        longitude = point.longitude,
        horizontalAccuracy = point.horizontalAccuracyMeters?.let(Length::meters),
        altitude = point.altitudeMeters?.let(Length::meters),
    )

    private companion object {
        const val DEFAULT_LAP_METERS = 1_000.0
    }
}
