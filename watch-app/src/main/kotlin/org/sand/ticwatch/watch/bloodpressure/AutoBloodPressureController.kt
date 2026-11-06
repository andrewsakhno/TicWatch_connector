package org.sand.ticwatch.watch.bloodpressure

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.sand.ticwatch.watch.power.AwakeRunner
import org.sand.ticwatch.watch.sensors.MorningWindows
import org.sand.ticwatch.watch.sensors.QualifierProbe
import org.sand.ticwatch.watch.sensors.SleepState
import org.sand.ticwatch.watch.sensors.WindowGate
import org.sand.ticwatch.watch.sensors.WindowQualifier
import org.sand.ticwatch.watch.sensors.WindowScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** How often [AutoBloodPressureController] measures in each situation. */
data class AutoBloodPressureCadence(
    val awake: Duration,
    val asleep: Duration,
    /** Asleep, shortly before the usual wake-up. */
    val beforeWake: Duration,
    /** Just after a night, for the morning surge. */
    val afterWake: Duration,
)

/** How a due measurement is taken. */
enum class AutoBloodPressureMode {
    /** Asleep: estimated on the watch from the cached calibration and queued, no radio and no stillness probe. */
    ASLEEP_LOCAL,

    /** Awake: a gyroscope probe first, then the phone is asked. */
    AWAKE_PROBED,
}

data class AutoBloodPressurePlan(val mode: AutoBloodPressureMode, val interval: Duration)

/**
 * Decides whether and how often to measure: [dayEnabled] ("Auto at rest") covers waking hours, [nightEnabled]
 * ("Blood pressure in sleep") a sleep session detected by [sleepState] (sensor, not the clock), denser before the
 * usual wake-up, plus the hours after it ([morning]) for the morning surge. Null = nothing to do until a change.
 */
class AutoBloodPressurePlanner(
    private val dayEnabled: () -> Boolean,
    private val nightEnabled: () -> Boolean,
    private val sleepState: SleepState,
    private val morning: MorningWindows,
    private val cadence: AutoBloodPressureCadence,
) {

    fun plan(now: Instant): AutoBloodPressurePlan? = when {
        sleepState.isAsleep() -> if (nightEnabled()) {
            AutoBloodPressurePlan(AutoBloodPressureMode.ASLEEP_LOCAL, if (morning.isBeforeUsualWake(now)) cadence.beforeWake else cadence.asleep)
        } else {
            null
        }
        dayEnabled() -> AutoBloodPressurePlan(AutoBloodPressureMode.AWAKE_PROBED, cadence.awake)
        nightEnabled() && morning.isAfterWake(now) -> AutoBloodPressurePlan(AutoBloodPressureMode.AWAKE_PROBED, cadence.afterWake)
        else -> null
    }
}

/** Takes one sleep-time estimate on the watch. */
fun interface AsleepBloodPressureMeasurer {
    suspend fun measure()
}

/** Runs each of [measurers] in turn at a sleep measurement (the estimate first, then slower recordings). */
class CompositeAsleepBloodPressureMeasurer(private val measurers: List<AsleepBloodPressureMeasurer>) : AsleepBloodPressureMeasurer {
    override suspend fun measure() = measurers.forEach { it.measure() }
}

/**
 * Measures blood pressure while the wearer rests, as the [planner] says: all [gates] open (worn, few steps, no
 * workout); awake the [stillness] qualifiers (gyroscope) must accept a [probeDuration] probe before the phone is
 * asked; asleep the wrist is still by definition, so only the [asleepGates] (settled sleep stage) are checked and the
 * [asleep] measurer estimates locally. Re-plans on every [changes] emission (settings, sleep session); paced by an
 * alarm [scheduler], never `delay`.
 */
class AutoBloodPressureController(
    private val changes: Flow<*>,
    private val planner: AutoBloodPressurePlanner,
    private val gates: List<WindowGate>,
    private val asleepGates: List<WindowGate>,
    private val stillness: () -> List<WindowQualifier>,
    private val probe: QualifierProbe,
    private val requester: AwaitedBloodPressureRequester,
    private val asleep: AsleepBloodPressureMeasurer,
    private val scheduler: WindowScheduler,
    private val awakeRunner: AwakeRunner,
    private val clock: Clock,
    private val probeDuration: Duration,
) {

    suspend fun run() {
        while (true) {
            // Nothing planned (both switches off, or only the night one while awake): no alarm wakes the watch.
            val planned = changes.map { planner.plan(clock.instant()) }.first { it != null } ?: continue
            scheduler.awaitNext(planned.interval)
            val due = planner.plan(clock.instant()) ?: continue
            if (gates.any { !it.isOpen() }) continue
            when (due.mode) {
                AutoBloodPressureMode.ASLEEP_LOCAL -> measureAsleep()
                AutoBloodPressureMode.AWAKE_PROBED -> measureAwake()
            }
        }
    }

    private suspend fun measureAsleep() {
        if (asleepGates.any { !it.isOpen() }) {
            Log.i(TAG, "Awake stage or just after one, skipping the sleep measurement")
            return
        }
        awakeRunner.runAwake(LOCAL_TIMEOUT) { asleep.measure() }
    }

    private suspend fun measureAwake() {
        awakeRunner.runAwake(probeDuration.plus(REQUEST_TIMEOUT)) {
            if (probe.accepts(stillness(), probeDuration)) {
                requester.requestAndAwait()
            } else {
                Log.i(TAG, "Wrist not still, skipping the automatic measurement")
            }
        }
    }

    private companion object {
        const val TAG = "AutoBloodPressure"

        /** Flush plus the request message; the flush holds its own wake lock beyond this. */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** Estimate plus two buffer inserts. */
        val LOCAL_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
