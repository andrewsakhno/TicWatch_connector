package org.sand.ticwatch.watch.sensors.adaptive

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sand.ticwatch.watch.sensors.SensorSample
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** How often each [MeasurementGroup] is measured right now. */
interface WindowCadence {
    fun intervalFor(group: MeasurementGroup): Duration

    /** Emits on every profile change, so a loop waiting for the old interval re-plans at once. */
    val changes: Flow<Unit>
}

/** Read side of the per-group [MonitoringProfile], for streams that switch on/off with it and for the UI. */
fun interface MonitoringProfiles {
    fun profile(group: MeasurementGroup): StateFlow<MonitoringProfile>
}

/** Decay timing of [AdaptiveSensorScheduler]. */
class AdaptiveTiming(
    /** Normal readings for this long return a recovering trigger to IDLE. */
    val stableNormal: Duration,
    /** An alert whose sensor stopped delivering readings (e.g. motion rejected every SpO2 window) starts recovering after this. */
    val alertWithoutReadings: Duration,
    /** RECOVERY measures this many times slower than ALERT, never slower than IDLE. */
    val recoverySlowdown: Long,
)

/**
 * Adaptive polling: every collected sample goes through the [triggers]; an abnormal verdict puts that trigger in
 * ALERT with its [CadenceBoost]s, a normal one moves it to RECOVERY, and [AdaptiveTiming.stableNormal] of normal
 * readings later back to IDLE (an abnormal one in between re-arms ALERT). A group's profile is the most severe of
 * the triggers boosting it, its interval the shortest boost (IDLE: [baseIntervals]). Decay is evaluated lazily on
 * every sample and every read, so no timer of its own keeps the watch awake. Thread-safe: samples arrive from
 * several collectors at once.
 */
class AdaptiveSensorScheduler(
    private val triggers: List<AdaptiveTrigger>,
    private val baseIntervals: Map<MeasurementGroup, Duration>,
    private val timing: AdaptiveTiming,
    private val clock: Clock,
) : WindowCadence, MonitoringProfiles {

    private class TriggerState(
        val profile: MonitoringProfile,
        val boosts: List<CadenceBoost> = emptyList(),
        val lastAbnormalAt: Instant? = null,
        val normalSince: Instant? = null,
    )

    private val states = triggers.associate { it.id to TriggerState(MonitoringProfile.IDLE) }.toMutableMap()
    private val profiles = MeasurementGroup.entries.associateWith { MutableStateFlow(MonitoringProfile.IDLE) }
    private val changeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val changes: Flow<Unit> = changeSignal.asSharedFlow()

    override fun profile(group: MeasurementGroup): StateFlow<MonitoringProfile> {
        refresh()
        return profiles.getValue(group).asStateFlow()
    }

    override fun intervalFor(group: MeasurementGroup): Duration = synchronized(this) {
        decay(clock.instant())
        val base = baseIntervals.getValue(group)
        val active = states.values.filter { it.profile != MonitoringProfile.IDLE }
        val intervals = active.flatMap { state ->
            state.boosts.filter { it.group == group }.map { boost ->
                if (state.profile == MonitoringProfile.ALERT) boost.interval else boost.interval.multipliedBy(timing.recoverySlowdown)
            }
        }
        (intervals + base).min()
    }

    /** Feeds one collected sample to every trigger. */
    fun onSample(sample: SensorSample) = synchronized(this) {
        val now = clock.instant()
        triggers.forEach { trigger ->
            when (val verdict = trigger.evaluate(sample)) {
                is TriggerVerdict.Abnormal -> {
                    if (states.getValue(trigger.id).profile != MonitoringProfile.ALERT) Log.i(TAG, "ALERT ${trigger.id}: ${verdict.reason}")
                    states[trigger.id] = TriggerState(MonitoringProfile.ALERT, verdict.boosts, lastAbnormalAt = now)
                }
                TriggerVerdict.Normal -> onNormal(trigger.id, now)
                null -> Unit
            }
        }
        decay(now)
        publish()
    }

    private fun refresh() = synchronized(this) {
        decay(clock.instant())
        publish()
    }

    private fun onNormal(id: TriggerId, now: Instant) {
        val state = states.getValue(id)
        if (state.profile == MonitoringProfile.ALERT) {
            Log.i(TAG, "RECOVERY $id: reading back to normal")
            states[id] = TriggerState(MonitoringProfile.RECOVERY, state.boosts, state.lastAbnormalAt, normalSince = now)
        }
    }

    private fun decay(now: Instant) {
        states.entries.forEach { entry ->
            val state = entry.value
            when (state.profile) {
                MonitoringProfile.ALERT -> {
                    val silentSince = state.lastAbnormalAt ?: return@forEach
                    if (Duration.between(silentSince, now) >= timing.alertWithoutReadings) {
                        Log.i(TAG, "RECOVERY ${entry.key}: no abnormal reading for ${timing.alertWithoutReadings}")
                        entry.setValue(TriggerState(MonitoringProfile.RECOVERY, state.boosts, state.lastAbnormalAt, normalSince = now))
                    }
                }
                MonitoringProfile.RECOVERY -> {
                    val normalSince = state.normalSince ?: return@forEach
                    if (Duration.between(normalSince, now) >= timing.stableNormal) {
                        Log.i(TAG, "IDLE ${entry.key}: stable for ${timing.stableNormal}")
                        entry.setValue(TriggerState(MonitoringProfile.IDLE))
                    }
                }
                MonitoringProfile.IDLE -> Unit
            }
        }
    }

    private fun publish() {
        var changed = false
        profiles.forEach { (group, flow) ->
            val profile = states.values
                .filter { state -> state.boosts.any { it.group == group } }
                .maxByOrNull { it.profile.severity }?.profile
                ?: MonitoringProfile.IDLE
            if (flow.value != profile) {
                flow.value = profile
                changed = true
            }
        }
        if (changed) changeSignal.tryEmit(Unit)
    }

    private companion object {
        const val TAG = "AdaptiveScheduler"
    }
}
