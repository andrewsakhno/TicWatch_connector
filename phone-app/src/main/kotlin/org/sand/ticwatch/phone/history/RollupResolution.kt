package org.sand.ticwatch.phone.history

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Aggregation tiers of `sensor_rollup`, finest first. Every sample is folded into all tiers
 * on insert; each tier is kept for its own [retention] (null = forever), independently of
 * the raw-row retention chosen in Settings.
 */
enum class RollupResolution(val code: Int, val bucket: Duration, val retention: Duration?) {
    FIVE_MINUTES(1, Duration.ofMinutes(5), Duration.ofDays(90)) {
        override fun bucketStart(at: Instant, zone: ZoneId): Instant = floorToBucket(at)
    },
    HOUR(2, Duration.ofHours(1), Duration.ofDays(365)) {
        override fun bucketStart(at: Instant, zone: ZoneId): Instant = floorToBucket(at)
    },

    /** Aligned to local midnight in [ZoneId], so a day bucket is the user's calendar day (23/25h across DST). */
    DAY(3, Duration.ofDays(1), null) {
        override fun bucketStart(at: Instant, zone: ZoneId): Instant =
            at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

        override fun advance(bucketStart: Instant, buckets: Long, zone: ZoneId): Instant =
            bucketStart.atZone(zone).toLocalDate().plusDays(buckets).atStartOfDay(zone).toInstant()
    };

    abstract fun bucketStart(at: Instant, zone: ZoneId): Instant

    /** Start of the bucket [buckets] after the one starting at [bucketStart]. */
    open fun advance(bucketStart: Instant, buckets: Long, zone: ZoneId): Instant =
        bucketStart.plus(bucket.multipliedBy(buckets))

    protected fun floorToBucket(at: Instant): Instant {
        val bucketMillis = bucket.toMillis()
        return Instant.ofEpochMilli(Math.floorDiv(at.toEpochMilli(), bucketMillis) * bucketMillis)
    }

    /** True when this tier still holds data as old as [from]. */
    fun covers(from: Instant, now: Instant): Boolean = retention == null || !from.isBefore(now.minus(retention))
}
