package org.sand.ticwatch.phone.history

import org.sand.ticwatch.phone.route.ExerciseRouteMaintenance
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

/**
 * Deletes raw rows older than the currently selected [HistoryRetention], rollup buckets older
 * than their tier's [RollupResolution.retention] and sleep sessions older than the selected
 * [SleepSessionRetention] (calendar months in [zone]), then compacts the file once. Workout GPS
 * fixes follow the raw retention, by recording time (Health Connect keeps the route itself).
 */
class SensorHistoryPurger(
    private val maintenance: SensorHistoryMaintenance,
    private val routeMaintenance: ExerciseRouteMaintenance,
    private val retentionStore: HistoryRetentionStore,
    private val sleepSessionRetentionStore: SleepSessionRetentionStore,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    /** Returns the number of deleted raw rows, route points, rollup buckets and sleep sessions. */
    suspend fun purge(): Int {
        val now = clock.instant()
        val retention = retentionStore.get()
        val rawCutoff = now.minus(Duration.ofDays(retention.days))
        var deleted = maintenance.purgeReceivedBefore(rawCutoff)
        deleted += routeMaintenance.purgeRecordedBefore(rawCutoff)
        for (resolution in RollupResolution.entries) {
            val tierRetention = resolution.retention ?: continue
            deleted += maintenance.purgeRollupsBefore(resolution, now.minus(tierRetention))
        }
        sleepSessionRetentionStore.get().months?.let { months ->
            deleted += maintenance.purgeSleepSessionsBefore(now.atZone(zone).minusMonths(months).toInstant())
        }
        if (deleted > 0) maintenance.compact()
        return deleted
    }
}
