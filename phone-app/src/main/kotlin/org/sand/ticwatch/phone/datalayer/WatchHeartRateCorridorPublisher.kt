package org.sand.ticwatch.phone.datalayer

import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.sand.ticwatch.phone.deviation.HeartRateCorridorSource
import org.sand.ticwatch.phone.deviation.HeartRateCorridors
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import java.time.Clock
import kotlin.math.roundToInt

/**
 * Sends the personal resting heart rate corridors on [DataLayerPaths.MESSAGE_HEART_RATE_CORRIDOR] after sensor pushes,
 * so the watch's resting heart rate trigger boosts its sensors at the same edges the phone's detector uses. Whole bpm,
 * so the slowly moving corridor deduplicates; mirrored by watch-app's `sensors/adaptive/HeartRateCorridor.kt`.
 */
class WatchHeartRateCorridorPublisher(
    corridors: HeartRateCorridorSource,
    clock: Clock,
    transport: DeviceLinkTransport,
    scope: CoroutineScope,
) : WatchStatePublisher {

    private val delegate = DedupingPayloadPublisher(DataLayerPaths.MESSAGE_HEART_RATE_CORRIDOR, transport, scope, TAG) {
        encode(corridors.corridors(clock.instant()))
    }

    override fun publish() = delegate.publish()

    override suspend fun publishAndWait() = delegate.publishAndWait()

    private fun encode(corridors: HeartRateCorridors): String = JSONObject()
        .put(AWAKE_LOW, corridors.awakeRest.lowBpm.roundToInt())
        .put(AWAKE_HIGH, corridors.awakeRest.highBpm.roundToInt())
        .put(SLEEP_LOW, corridors.sleep.lowBpm.roundToInt())
        .put(SLEEP_HIGH, corridors.sleep.highBpm.roundToInt())
        .toString()

    private companion object {
        const val TAG = "HrCorridorPublisher"
        const val AWAKE_LOW = "awake_low"
        const val AWAKE_HIGH = "awake_high"
        const val SLEEP_LOW = "sleep_low"
        const val SLEEP_HIGH = "sleep_high"
    }
}
