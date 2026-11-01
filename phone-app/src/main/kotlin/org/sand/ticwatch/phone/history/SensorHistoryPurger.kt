package org.sand.ticwatch.phone.history

import org.sand.ticwatch.phone.cloud.CloudRetentionHold
import org.sand.ticwatch.phone.route.ExerciseRouteMaintenance
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

/**
 * Deletes raw rows older than the currently selected [HistoryRetention], rollup buckets older
 * than their tier's [RollupResolution.retention] and sleep sessions older than the selected
 * [SleepSessionRetention] (calendar months in [zone]), then compacts the file once. Workout GPS
 * fixes follow the raw retention, by recording time (Health Connect keeps the route itself).
 * Raw rows, fixes and sleep rows the cloud server hasn't acknowledged yet stay, per [CloudRetentionHold]. Rollups
 * carry no such mark: they are derived, and the server computes its own from the raw rows that are kept.
 */
class SensorHistoryPurger(
    private val maintenance: SensorHistoryMaintenance,
    private val routeMaintenance: ExerciseRouteMaintenance,
    private val retentionStore: HistoryRetentionStore,
    private val sleepSessionRetentionStore: SleepSessionRetentionStore,
    private val cloudHold: CloudRetentionHold,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    /** Returns the number of deleted raw rows, route points, rollup buckets and sleep sessions. */
    suspend fun purge(): Int {
        val now = clock.instant()
        val retention = retentionStore.get()
        val rawCutoff = now.minus(Duration.ofDays(retention.days))
        val keepUnsynced = cloudHold.keepsUnsynced()
        var deleted = maintenance.purgeReceivedBefore(rawCutoff, keepUnsynced)
        deleted += routeMaintenance.purgeRecordedBefore(rawCutoff, keepUnsynced)
        for (resolution in RollupResolution.entries) {
            val tierRetention = resolution.retention ?: continue
            deleted += maintenance.purgeRollupsBefore(resolution, now.minus(tierRetention))
        }
        sleepSessionRetentionStore.get().months?.let { months ->
            deleted += maintenance.purgeSleepSessionsBefore(now.atZone(zone).minusMonths(months).toInstant(), keepUnsynced)
        }
        if (deleted > 0) maintenance.compact()
        return deleted
    }
}
