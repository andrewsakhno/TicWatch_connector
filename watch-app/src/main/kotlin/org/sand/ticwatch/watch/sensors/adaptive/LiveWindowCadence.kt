package org.sand.ticwatch.watch.sensors.adaptive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.sand.ticwatch.watch.datalayer.LiveSyncSignal
import java.time.Duration

/**
 * [delegate]'s cadence, except that windows run back to back while [liveSync] is active, so a window's readings reach
 * the open phone app as soon as they exist. Leaving live needs no re-plan: the loop only waits once it has ended.
 */
class LiveWindowCadence(
    private val delegate: WindowCadence,
    private val liveSync: LiveSyncSignal,
) : WindowCadence {
    override fun intervalFor(group: MeasurementGroup): Duration =
        if (liveSync.isActive()) Duration.ZERO else delegate.intervalFor(group)

    override val changes: Flow<Unit> = merge(delegate.changes, liveSync.active.filter { it }.map { })
}
