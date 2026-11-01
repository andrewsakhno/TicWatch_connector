package org.sand.ticwatch.watch.sensors

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/** Collects [delegate] (so its sensors stay registered) only while [enabled] says so. */
class GatedSampleSource(
    private val enabled: Flow<Boolean>,
    private val delegate: SampleSource,
) : SampleSource {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun samples(): Flow<SensorSample> =
        enabled.distinctUntilChanged().flatMapLatest { on -> if (on) delegate.samples() else emptyFlow() }
}
