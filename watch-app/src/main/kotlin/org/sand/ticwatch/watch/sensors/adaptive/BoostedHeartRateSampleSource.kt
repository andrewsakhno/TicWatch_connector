package org.sand.ticwatch.watch.sensors.adaptive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import org.sand.ticwatch.watch.datalayer.LiveSyncSignal
import org.sand.ticwatch.watch.sensors.SampleSource
import org.sand.ticwatch.watch.sensors.SensorCollector
import org.sand.ticwatch.watch.sensors.SensorSample
import java.time.Duration
import java.time.Instant

/**
 * Continuous heart rate while [MeasurementGroup.HEART_RATE] is boosted (ALERT or RECOVERY): the live Measure API
 * stream, thinned to one sample per [spacing] so the sync buffer doesn't flush every couple of minutes. In IDLE it
 * is unregistered and the passive listener's batches are the only heart rate. While [liveSync] is active (the phone
 * app is on screen) it streams regardless, one sample per [liveSpacing].
 */
class BoostedHeartRateSampleSource(
    private val profiles: MonitoringProfiles,
    private val liveSync: LiveSyncSignal,
    private val collector: SensorCollector,
    private val spacing: Duration,
    private val liveSpacing: Duration,
) : SampleSource {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun samples(): Flow<SensorSample> = combine(profiles.profile(MeasurementGroup.HEART_RATE), liveSync.active) { profile, live ->
        when {
            live -> liveSpacing
            profile != MonitoringProfile.IDLE -> spacing
            else -> null
        }
    }
        .distinctUntilChanged()
        .flatMapLatest { current -> if (current != null) thinned(collector.heartRateSamples(), current) else emptyFlow() }

    private fun thinned(samples: Flow<SensorSample>, minSpacing: Duration): Flow<SensorSample> {
        var lastEmitted: Instant? = null
        return samples.filter { sample ->
            val previous = lastEmitted
            (previous == null || Duration.between(previous, sample.recordedAt) >= minSpacing).also { keep ->
                if (keep) lastEmitted = sample.recordedAt
            }
        }
    }
}
