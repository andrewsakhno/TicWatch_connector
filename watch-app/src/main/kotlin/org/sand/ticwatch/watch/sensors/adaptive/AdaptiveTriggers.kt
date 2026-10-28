package org.sand.ticwatch.watch.sensors.adaptive

import org.sand.ticwatch.watch.sensors.ReadingHistory
import org.sand.ticwatch.watch.sensors.SensorSample
import org.sand.ticwatch.watch.sensors.SensorType
import org.sand.ticwatch.watch.sensors.WindowGate
import org.sand.ticwatch.watch.sensors.median
import java.time.Duration

/** Identity of an [AdaptiveTrigger]; the scheduler keeps one alert state per trigger. */
enum class TriggerId {
    RESTING_HEART_RATE,
    DESATURATION,
    SKIN_TEMPERATURE_RISE,
}

/** Outcome of judging one reading. */
sealed interface TriggerVerdict {
    /** Out of range: measure [boosts] more often. */
    data class Abnormal(val reason: String, val boosts: List<CadenceBoost>) : TriggerVerdict

    /** In range: an alert raised by this trigger may decay. */
    data object Normal : TriggerVerdict
}

/** Watches one kind of reading for out-of-range values (Strategy: add a trigger, not a branch in the scheduler). */
interface AdaptiveTrigger {
    val id: TriggerId

    /** Null when [sample] is not this trigger's concern. */
    fun evaluate(sample: SensorSample): TriggerVerdict?
}

/**
 * Tachycardia (> [high]) or bradycardia (< [low]) while [rest] says the wearer isn't moving (no steps, no workout):
 * heart rate goes continuous and HRV + SpO2 are measured sooner. Moving explains a high rate, so it counts as normal.
 */
class RestingHeartRateTrigger(
    private val rest: WindowGate,
    private val low: Double,
    private val high: Double,
    private val boosts: List<CadenceBoost>,
) : AdaptiveTrigger {
    override val id = TriggerId.RESTING_HEART_RATE

    override fun evaluate(sample: SensorSample): TriggerVerdict? {
        if (sample.type != SensorType.HEART_RATE) return null
        if (!rest.isOpen()) return TriggerVerdict.Normal
        return when {
            sample.value > high -> TriggerVerdict.Abnormal("Resting tachycardia ${sample.value} bpm", boosts)
            sample.value < low -> TriggerVerdict.Abnormal("Resting bradycardia ${sample.value} bpm", boosts)
            else -> TriggerVerdict.Normal
        }
    }
}

/** SpO2 below [threshold] %: SpO2 is measured again [retryAfter] after the window, until it recovers. */
class DesaturationTrigger(
    private val threshold: Double,
    retryAfter: Duration,
) : AdaptiveTrigger {
    override val id = TriggerId.DESATURATION
    private val boosts = listOf(CadenceBoost(MeasurementGroup.SPO2, retryAfter))

    override fun evaluate(sample: SensorSample): TriggerVerdict? {
        if (sample.type != SensorType.SPO2) return null
        return if (sample.value < threshold) TriggerVerdict.Abnormal("SpO2 ${sample.value} %", boosts) else TriggerVerdict.Normal
    }
}

/**
 * Skin temperature at least [rise] °C above the median of the earlier readings in [history] (the in-memory 6 h trend):
 * skin temperature is measured every [boostedInterval]. Needs [minBaselinePoints] earlier readings. Only judged while
 * [applies] is open - the wrist warms at sleep onset every night, which is no fever.
 */
class SkinTemperatureRiseTrigger(
    private val history: ReadingHistory,
    private val applies: WindowGate,
    private val rise: Double,
    private val minBaselinePoints: Int,
    boostedInterval: Duration,
) : AdaptiveTrigger {
    override val id = TriggerId.SKIN_TEMPERATURE_RISE
    private val boosts = listOf(CadenceBoost(MeasurementGroup.SKIN_TEMPERATURE, boostedInterval))

    override fun evaluate(sample: SensorSample): TriggerVerdict? {
        if (sample.type != SensorType.SKIN_TEMPERATURE || !applies.isOpen()) return null
        val baseline = history.history(SensorType.SKIN_TEMPERATURE).value
            .filter { it.at.isBefore(sample.recordedAt) }
            .map { it.value }
            .takeIf { it.size >= minBaselinePoints }
            ?.median()
            ?: return null
        val delta = sample.value - baseline
        return if (delta >= rise) {
            TriggerVerdict.Abnormal("Skin temperature +%.1f °C over %.1f".format(delta, baseline), boosts)
        } else {
            TriggerVerdict.Normal
        }
    }
}
