package org.sand.ticwatch.phone.health

import org.sand.ticwatch.phone.route.ExerciseRouteQueries
import org.sand.ticwatch.phone.route.RoutePoint
import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The GPS fixes of one session. When both the phone and the watch tracked the same workout, only the
 * source with more fixes is used: interleaving two receivers' fixes would draw a zigzag. Fixes Health
 * Connect would reject (out-of-range coordinates, negative accuracy) are dropped.
 */
class SessionRoutePoints(private val routes: ExerciseRouteQueries) {

    /** Usable fixes recorded in [start, end] (end inclusive, as Health Connect accepts), ascending. */
    suspend fun pointsFor(start: Instant, end: Instant): List<RoutePoint> {
        val points = routes.routePoints(start, end.plusMillis(INCLUSIVE_END_MS), after = null, limit = MAX_ROUTE_POINTS)
            .filter(::isValid)
        if (points.isEmpty()) return emptyList()
        return points.groupBy { it.source }.values.maxBy { it.size }.sortedBy { it.recordedAt }
    }

    private fun isValid(point: RoutePoint): Boolean =
        point.latitude in LATITUDE_RANGE &&
            point.longitude in LONGITUDE_RANGE &&
            (point.horizontalAccuracyMeters ?: 0.0) >= 0.0

    private companion object {
        const val INCLUSIVE_END_MS = 1L

        /** ~8 h at the phone tracker's 4 s interval; far beyond any realistic single workout. */
        const val MAX_ROUTE_POINTS = 7_200
        val LATITUDE_RANGE = -90.0..90.0
        val LONGITUDE_RANGE = -180.0..180.0
    }
}

/** A stretch of a track: [meters] travelled and [climbMeters] gained (null without altitude) over [start]..[end]. */
data class TrackSegment(val start: Instant, val end: Instant, val meters: Double, val climbMeters: Double?) {
    val seconds: Double get() = Duration.between(start, end).toMillis() / MILLIS_PER_SECOND

    /** m/s. */
    val speed: Double get() = if (seconds > 0) meters / seconds else 0.0

    /** Rise over run, 0 without altitude or distance. */
    val grade: Double get() = if (meters > 0 && climbMeters != null) climbMeters / meters else 0.0

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

/** A lap boundary: the [meters] covered between [start] and [end]. */
data class TrackLap(val start: Instant, val end: Instant, val meters: Double)

/** Geometry of a GPS track (ascending fixes): distances, fixed-duration segments and distance laps. */
class ExerciseTrack(private val points: List<RoutePoint>) {

    /** Consecutive segments of at least [length] each; the trailing remainder shorter than that is dropped. */
    fun segments(length: Duration): List<TrackSegment> {
        val segments = mutableListOf<TrackSegment>()
        var startIndex = 0
        for (index in 1 until points.size) {
            if (Duration.between(points[startIndex].recordedAt, points[index].recordedAt) < length) continue
            segments += segment(startIndex, index)
            startIndex = index
        }
        return segments
    }

    /**
     * Laps of [lapMeters] each, the crossing time interpolated between the two fixes around it; the last
     * lap runs to [sessionEnd] with whatever distance remains.
     */
    fun laps(lapMeters: Double, sessionStart: Instant, sessionEnd: Instant): List<TrackLap> {
        if (points.size < 2) return emptyList()
        val laps = mutableListOf<TrackLap>()
        var lapStart = sessionStart
        var lapCovered = 0.0
        for (index in 1 until points.size) {
            var step = meters(points[index - 1], points[index])
            var stepStart = points[index - 1].recordedAt
            val stepEnd = points[index].recordedAt
            while (lapCovered + step >= lapMeters && step > 0) {
                val needed = lapMeters - lapCovered
                val crossing = interpolate(stepStart, stepEnd, needed / step)
                laps += TrackLap(lapStart, crossing, lapMeters)
                step -= needed
                stepStart = crossing
                lapStart = crossing
                lapCovered = 0.0
            }
            lapCovered += step
        }
        if (lapCovered > 0 && sessionEnd.isAfter(lapStart)) laps += TrackLap(lapStart, sessionEnd, lapCovered)
        return laps
    }

    private fun segment(from: Int, to: Int): TrackSegment {
        val meters = (from + 1..to).sumOf { meters(points[it - 1], points[it]) }
        val startAltitude = points[from].altitudeMeters
        val endAltitude = points[to].altitudeMeters
        val climb = if (startAltitude != null && endAltitude != null) endAltitude - startAltitude else null
        return TrackSegment(points[from].recordedAt, points[to].recordedAt, meters, climb)
    }

    private fun interpolate(start: Instant, end: Instant, fraction: Double): Instant =
        start.plusMillis((Duration.between(start, end).toMillis() * fraction).toLong())

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0

        /** Haversine great-circle distance. */
        fun meters(a: RoutePoint, b: RoutePoint): Double {
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
