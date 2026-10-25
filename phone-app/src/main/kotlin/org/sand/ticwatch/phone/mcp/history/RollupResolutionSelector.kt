package org.sand.ticwatch.phone.mcp.history

import org.sand.ticwatch.phone.history.RollupResolution
import java.time.Instant
import java.time.ZoneId

/** One page of a bucketed series: the tier, the part of the requested range it covers and where the next page starts. */
data class SeriesPage(val resolution: RollupResolution, val window: TimeRange, val nextFrom: Instant?)

/** Picks the rollup tier a history query should read, so tool output stays bounded and complete. */
class RollupResolutionSelector(private val zone: ZoneId) {

    /** Finest tier still holding [range]'s start whose bucket count fits [maxPoints]; [RollupResolution.DAY] otherwise. */
    fun forSeries(range: TimeRange, maxPoints: Int, now: Instant): RollupResolution =
        RollupResolution.entries.firstOrNull { resolution ->
            resolution.covers(range.from, now) && range.duration.toMillis() / resolution.bucket.toMillis() <= maxPoints
        } ?: RollupResolution.DAY

    /** Finest tier still holding [range]'s start: bucket edges are the only imprecision. */
    fun forSummary(range: TimeRange, now: Instant): RollupResolution =
        RollupResolution.entries.first { it.covers(range.from, now) }

    /**
     * [forced] (validated against retention) or the automatic tier, with [range] cut to at most
     * [maxPoints] buckets; the remainder is reported as [SeriesPage.nextFrom] for the caller to page through.
     */
    fun page(range: TimeRange, maxPoints: Int, forced: RollupResolution?, now: Instant): SeriesPage {
        val resolution = forced ?: forSeries(range, maxPoints, now)
        require(resolution.covers(range.from, now)) {
            "${resolution.name.lowercase()} buckets are kept for ${resolution.retention?.toDays()} days only; " +
                "move 'from' later or use a coarser resolution"
        }
        val windowEnd = resolution.advance(resolution.bucketStart(range.from, zone), maxPoints.toLong(), zone)
        return if (windowEnd.isBefore(range.to)) {
            SeriesPage(resolution, TimeRange(range.from, windowEnd), windowEnd)
        } else {
            SeriesPage(resolution, range, null)
        }
    }
}
