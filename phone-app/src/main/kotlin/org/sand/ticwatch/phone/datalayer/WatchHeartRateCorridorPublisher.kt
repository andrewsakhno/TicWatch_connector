package org.sand.ticwatch.phone.datalayer

import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.sand.ticwatch.phone.deviation.HeartRateCorridorSource
import org.sand.ticwatch.phone.deviation.HeartRateCorridors
import org.sand.ticwatch.phone.health.HeartRateZoneCeilingSource
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import java.time.Clock
import kotlin.math.roundToInt

/**
 * Sends the personal resting heart rate corridors on [DataLayerPaths.MESSAGE_HEART_RATE_CORRIDOR] after sensor pushes,
 * so the watch's resting heart rate trigger boosts its sensors at the same edges the phone's detector uses, plus the
 * top of Karvonen zone 2, above which the watch stops thinning its daytime heart rate. Whole bpm,
 * so the slowly moving corridor deduplicates; mirrored by watch-app's `sensors/adaptive/HeartRateCorridor.kt`.
 */
class WatchHeartRateCorridorPublisher(
    corridors: HeartRateCorridorSource,
    zone2Ceiling: HeartRateZoneCeilingSource,
    clock: Clock,
    transport: DeviceLinkTransport,
    scope: CoroutineScope,
) : WatchStatePublisher {

    private val delegate = DedupingPayloadPublisher(DataLayerPaths.MESSAGE_HEART_RATE_CORRIDOR, transport, scope, TAG) {
        val now = clock.instant()
        encode(corridors.corridors(now), zone2Ceiling.ceilingBpm(now))
    }

    override fun publish() = delegate.publish()

    override suspend fun publishAndWait() = delegate.publishAndWait()

    /** The zone 2 ceiling is left out while unknown; the watch then falls back to the awake corridor's top. */
    private fun encode(corridors: HeartRateCorridors, zone2CeilingBpm: Double?): String = JSONObject()
        .put(AWAKE_LOW, corridors.awakeRest.lowBpm.roundToInt())
        .put(AWAKE_HIGH, corridors.awakeRest.highBpm.roundToInt())
        .put(SLEEP_LOW, corridors.sleep.lowBpm.roundToInt())
        .put(SLEEP_HIGH, corridors.sleep.highBpm.roundToInt())
        .apply { zone2CeilingBpm?.let { put(ZONE2_CEILING, it.roundToInt()) } }
        .toString()

    private companion object {
        const val TAG = "HrCorridorPublisher"
        const val AWAKE_LOW = "awake_low"
        const val AWAKE_HIGH = "awake_high"
        const val SLEEP_LOW = "sleep_low"
        const val SLEEP_HIGH = "sleep_high"
        const val ZONE2_CEILING = "zone2_ceiling"
    }
}
