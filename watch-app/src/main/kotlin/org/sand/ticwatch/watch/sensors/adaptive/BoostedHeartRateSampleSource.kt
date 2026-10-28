package org.sand.ticwatch.watch.sensors.adaptive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.sand.ticwatch.watch.sensors.SampleSource
import org.sand.ticwatch.watch.sensors.SensorCollector
import org.sand.ticwatch.watch.sensors.SensorSample
import java.time.Duration
import java.time.Instant

/**
 * Continuous heart rate while [MeasurementGroup.HEART_RATE] is boosted (ALERT or RECOVERY): the live Measure API
 * stream, thinned to one sample per [spacing] so the sync buffer doesn't flush every couple of minutes. In IDLE it
 * is unregistered and the passive listener's batches are the only heart rate.
 */
class BoostedHeartRateSampleSource(
    private val profiles: MonitoringProfiles,
    private val collector: SensorCollector,
    private val spacing: Duration,
) : SampleSource {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun samples(): Flow<SensorSample> = profiles.profile(MeasurementGroup.HEART_RATE)
        .map { it != MonitoringProfile.IDLE }
        .distinctUntilChanged()
        .flatMapLatest { boosted -> if (boosted) thinned(collector.heartRateSamples()) else emptyFlow() }

    private fun thinned(samples: Flow<SensorSample>): Flow<SensorSample> {
        var lastEmitted: Instant? = null
        return samples.filter { sample ->
            val previous = lastEmitted
            (previous == null || Duration.between(previous, sample.recordedAt) >= spacing).also { keep ->
                if (keep) lastEmitted = sample.recordedAt
            }
        }
    }
}
