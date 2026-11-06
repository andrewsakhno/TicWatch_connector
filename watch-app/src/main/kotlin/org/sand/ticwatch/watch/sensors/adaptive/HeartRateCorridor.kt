package org.sand.ticwatch.watch.sensors.adaptive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import org.sand.ticwatch.watch.settings.WatchSettings
import java.time.Duration

/** Resting heart rate range of one state: outside it the wearer is in a deviation. */
data class RestingHeartRateLimits(val lowBpm: Double, val highBpm: Double)

/** The phone's personal corridors (its `deviation/HeartRateCorridor`), awake at rest and asleep. */
data class HeartRateCorridorSnapshot(val awakeRest: RestingHeartRateLimits, val sleep: RestingHeartRateLimits) {
    fun of(asleep: Boolean): RestingHeartRateLimits = if (asleep) sleep else awakeRest
}

/** Decodes `/insights/hr-corridor`; mirrors phone-app's `datalayer/WatchHeartRateCorridorPublisher.kt`. */
object HeartRateCorridorPayload {
    private const val AWAKE_LOW = "awake_low"
    private const val AWAKE_HIGH = "awake_high"
    private const val SLEEP_LOW = "sleep_low"
    private const val SLEEP_HIGH = "sleep_high"

    fun decode(payload: String): HeartRateCorridorSnapshot = JSONObject(payload).let {
        HeartRateCorridorSnapshot(
            awakeRest = RestingHeartRateLimits(it.getDouble(AWAKE_LOW), it.getDouble(AWAKE_HIGH)),
            sleep = RestingHeartRateLimits(it.getDouble(SLEEP_LOW), it.getDouble(SLEEP_HIGH)),
        )
    }
}

/** The IDLE cadence of each [MeasurementGroup], which the user may change; [changes] fires when it does. */
interface BaseCadence {
    fun intervalFor(group: MeasurementGroup): Duration
    val changes: Flow<Unit>
}

/** [default] for every group but HRV, whose interval follows [WatchSettings.daytimeHrvInterval]. */
class SettingsBaseCadence(private val settings: StateFlow<WatchSettings>, private val default: Duration) : BaseCadence {

    override fun intervalFor(group: MeasurementGroup): Duration =
        if (group == MeasurementGroup.HRV) settings.value.daytimeHrvInterval.duration else default

    override val changes: Flow<Unit> = settings.map { it.daytimeHrvInterval }.distinctUntilChanged().drop(1).map { }
}
