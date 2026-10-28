package org.sand.ticwatch.phone.advisor

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** Signals at [at]; each null when there is no recent data. */
data class CardioReadinessInputs(
    val at: Instant,
    /** 15-minute mean heart rate, else the latest resting HR. */
    val heartRateBpm: Double?,
    /** Personal mean heart rate of this local hour of day. */
    val heartRateNormBpm: Double?,
    val diastolic: Double?,
    val systolic: Double?,
    val pressureMeasuredAt: Instant?,
    val rmssdMs: Double?,
    val dippingPercent: Double?,
    val sleepScore: Int?,
    val steps24h: Double?,
    val trainingLoad48h: Double?,
    val lastIntakeAt: Instant?,
    val kp: Double?,
)

/** One scoring rule (OCP: add a rule by registering another implementation); null when its data is missing. */
fun interface ReadinessRule {
    fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor?
}

/**
 * Rule-based cardio readiness: [BASE_SCORE] plus every rule's points, clamped to 0..100, then advice for the
 * resulting [ReadinessState]. Heuristic for pacing everyday effort, not a medical device.
 */
class CardioReadinessEngine(private val rules: List<ReadinessRule>, private val adviceSelector: AdviceSelector) {

    fun assess(inputs: CardioReadinessInputs): ReadinessAssessment {
        val factors = rules.mapNotNull { it.evaluate(inputs) }
        val score = (BASE_SCORE + factors.sumOf { it.points }).coerceIn(MIN_SCORE, BASE_SCORE)
        val state = ReadinessState.of(score)
        return ReadinessAssessment(score, state, adviceSelector.select(state, factors), factors, inputs.at)
    }

    private companion object {
        const val BASE_SCORE = 100
        const val MIN_SCORE = 0
    }
}

/** Picks the card's action: the state's default, or no stimulants when resting tachycardia is the main limit. */
class AdviceSelector {

    fun select(state: ReadinessState, factors: List<ReadinessFactor>): Advice {
        val limiting = factors.filter { it.isLimiting }.sortedBy { it.points }
        val action = if (state in STIMULANT_STATES && limiting.firstOrNull()?.kind == ReadinessFactorKind.HEART_RATE) {
            AdviceAction.NO_STIMULANTS
        } else {
            state.defaultAction
        }
        val rationale = limiting.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "Because of: ", separator = "; ") { "${it.kind.label.lowercase()} ${it.detail}" }
        return Advice(action, state.guidance, rationale)
    }

    private companion object {
        val STIMULANT_STATES = setOf(ReadinessState.MODERATE, ReadinessState.LIMITED)
    }
}

/** Raised diastolic pressure as a sign of vascular spasm; readings older than [maxAge] are ignored. */
class DiastolicPressureRule(private val maxAge: Duration = Duration.ofHours(12)) : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor? {
        val diastolic = inputs.diastolic ?: return null
        val measuredAt = inputs.pressureMeasuredAt ?: return null
        if (Duration.between(measuredAt, inputs.at) > maxAge) return null
        val points = when {
            diastolic >= SEVERE_MMHG -> SEVERE_PENALTY
            diastolic >= RAISED_MMHG -> RAISED_PENALTY
            else -> 0
        }
        val note = if (points < 0) " (vascular spasm)" else ""
        return ReadinessFactor(ReadinessFactorKind.DIASTOLIC, "${diastolic.roundToInt()} mmHg$note", points)
    }

    private companion object {
        const val RAISED_MMHG = 90.0
        const val SEVERE_MMHG = 95.0
        const val RAISED_PENALTY = -25
        const val SEVERE_PENALTY = -40
    }
}

/** Heart rate above the personal circadian norm of the hour. */
class RestingTachycardiaRule : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor? {
        val heartRate = inputs.heartRateBpm ?: return null
        val norm = inputs.heartRateNormBpm
            ?: return ReadinessFactor(ReadinessFactorKind.HEART_RATE, "${heartRate.roundToInt()} bpm (no norm yet)", 0)
        val excess = heartRate - norm
        val points = when {
            excess > SEVERE_EXCESS_BPM -> SEVERE_PENALTY
            excess > RAISED_EXCESS_BPM -> RAISED_PENALTY
            else -> 0
        }
        val sign = if (excess >= 0) "+" else ""
        return ReadinessFactor(ReadinessFactorKind.HEART_RATE, "$sign${excess.roundToInt()} vs norm (${heartRate.roundToInt()} bpm)", points)
    }

    private companion object {
        const val RAISED_EXCESS_BPM = 10.0
        const val SEVERE_EXCESS_BPM = 20.0
        const val RAISED_PENALTY = -15
        const val SEVERE_PENALTY = -30
    }
}

/** Low RMSSD as autonomic exhaustion. */
class AutonomicExhaustionRule : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor? {
        val rmssd = inputs.rmssdMs ?: return null
        val points = if (rmssd < LOW_RMSSD_MS) PENALTY else 0
        return ReadinessFactor(ReadinessFactorKind.HRV, "${rmssd.roundToInt()} ms", points)
    }

    private companion object {
        const val LOW_RMSSD_MS = 25.0
        const val PENALTY = -15
    }
}

/** Many steps in the last 24 h after a poor night. */
class FatigueRule : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor? {
        val steps = inputs.steps24h ?: return null
        val poorSleep = inputs.sleepScore?.let { it < SleepThresholds.GOOD_SLEEP_SCORE } ?: false
        val points = if (steps > HIGH_STEPS && poorSleep) PENALTY else 0
        val sleep = inputs.sleepScore?.let { ", sleep $it" } ?: ""
        return ReadinessFactor(ReadinessFactorKind.FATIGUE, "%.1fk steps in 24 h$sleep".format(steps / THOUSAND), points)
    }

    private companion object {
        const val HIGH_STEPS = 15_000.0
        const val THOUSAND = 1_000.0
        const val PENALTY = -15
    }
}

/** Geomagnetic storm (Kp >= 5, NOAA G1+). */
class GeomagneticStormRule : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor? {
        val kp = inputs.kp ?: return null
        val points = if (kp >= STORM_KP) PENALTY else 0
        return ReadinessFactor(ReadinessFactorKind.GEOMAGNETIC, "Kp %.1f".format(kp), points)
    }

    private companion object {
        const val STORM_KP = 5.0
        const val PENALTY = -10
    }
}

/** Good sleep with a normal nocturnal pressure dip (10-20 %). */
class NightRecoveryRule : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor? {
        val sleep = inputs.sleepScore
        val dipping = inputs.dippingPercent
        if (sleep == null && dipping == null) return null
        val recovered = sleep != null && sleep >= SleepThresholds.GOOD_SLEEP_SCORE &&
            dipping != null && dipping in NORMAL_DIP_MIN_PERCENT..NORMAL_DIP_MAX_PERCENT
        val detail = listOfNotNull(sleep?.let { "sleep $it" }, dipping?.let { "dip %.0f%%".format(it) }).joinToString(", ")
        return ReadinessFactor(ReadinessFactorKind.RECOVERY, detail, if (recovered) BONUS else 0)
    }

    private companion object {
        const val NORMAL_DIP_MIN_PERCENT = 10.0
        const val NORMAL_DIP_MAX_PERCENT = 20.0
        const val BONUS = 10
    }
}

/** Informational: workout TRIMP of the last 48 h. */
class TrainingLoadInfoRule : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor? =
        inputs.trainingLoad48h?.let { ReadinessFactor(ReadinessFactorKind.TRAINING_LOAD, "TRIMP ${it.roundToInt()} in 48 h", 0) }
}

/** Informational: time since the last logged intake. */
class MedicationInfoRule : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor {
        val detail = inputs.lastIntakeAt?.let { "taken %.1f h ago".format(Duration.between(it, inputs.at).toMinutes() / MINUTES_PER_HOUR) }
            ?: "no intake logged"
        return ReadinessFactor(ReadinessFactorKind.MEDICATION, detail, 0)
    }

    private companion object {
        const val MINUTES_PER_HOUR = 60.0
    }
}

internal object SleepThresholds {
    const val GOOD_SLEEP_SCORE = 70
}
