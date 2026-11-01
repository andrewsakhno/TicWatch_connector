package org.sand.ticwatch.backend.cloud

/**
 * Bucket tiers, mirroring phone-app's `history/RollupResolution` (same names): fixed-width tiers floor
 * epoch ms, DAY starts at local midnight of the requested zone. [bucketSql] yields the bucket start in ms.
 */
enum class RollupResolution(val bucketSql: String) {
    FIVE_MINUTES("(recorded_at / 300000) * 300000"),
    HOUR("(recorded_at / 3600000) * 3600000"),
    DAY(
        "(EXTRACT(EPOCH FROM (date_trunc('day', to_timestamp(recorded_at / 1000.0) AT TIME ZONE :zone) " +
            "AT TIME ZONE :zone)) * 1000)::BIGINT",
    ),
}
