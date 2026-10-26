package org.sand.ticwatch.watch.sensors

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Lets a screen ask for live (high-rate, battery-costly) readings only while it is visible. */
interface LiveMeasurementDemand {
    fun acquire()
    fun release()
}

/**
 * [LiveMeasurementDemand] that runs [collection] in [scope] while at least one holder has
 * acquired it and cancels it when the last one releases - so e.g. the Measure API keeps the PPG
 * lit only while someone is looking at the reading, not around the clock.
 */
class OnDemandCollection(
    private val scope: CoroutineScope,
    private val collection: suspend () -> Unit,
) : LiveMeasurementDemand {

    private var holders = 0
    private var job: Job? = null

    @Synchronized
    override fun acquire() {
        if (holders++ == 0) job = scope.launch { collection() }
    }

    @Synchronized
    override fun release() {
        if (holders == 0) return
        if (--holders == 0) {
            job?.cancel()
            job = null
        }
    }
}
