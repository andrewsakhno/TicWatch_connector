package org.sand.ticwatch.phone.advisor

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.medication.MedicationProtectionPhase
import org.sand.ticwatch.phone.medication.MedicationProtectionTracker
import org.sand.ticwatch.phone.text.JoinedText
import org.sand.ticwatch.phone.text.LowercaseText
import org.sand.ticwatch.phone.text.uiText

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
    /** Workout steps of the last 24 h: the only steps the rules weigh. */
    val steps24h: Double?,
    /** Every step of the last 24 h, for reference only: shown next to [steps24h], never weighed. */
    val totalSteps24h: Double?,
    val trainingLoad48h: Double?,
    val lastIntakeAt: Instant?,
    val kp: Double?,
    val fatiguePercent: Int? = null,
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
        val rationale = limiting.takeIf { it.isNotEmpty() }?.let { factors ->
            uiText(
                R.string.because_of,
                JoinedText(factors.map { uiText(R.string.factor_item, LowercaseText(uiText(it.kind.label)), it.detail) }, RATIONALE_SEPARATOR),
            )
        }
        return Advice(action, uiText(state.guidance), rationale)
    }

    private companion object {
        val STIMULANT_STATES = setOf(ReadinessState.MODERATE, ReadinessState.LIMITED)
        const val RATIONALE_SEPARATOR = "; "
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
        val detail = if (points < 0) R.string.detail_mmhg_spasm else R.string.detail_mmhg
        return ReadinessFactor(ReadinessFactorKind.DIASTOLIC, uiText(detail, diastolic.roundToInt()), points)
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
            ?: return ReadinessFactor(ReadinessFactorKind.HEART_RATE, uiText(R.string.detail_bpm_no_norm, heartRate.roundToInt()), 0)
        val excess = heartRate - norm
        val points = when {
            excess > SEVERE_EXCESS_BPM -> SEVERE_PENALTY
            excess > RAISED_EXCESS_BPM -> RAISED_PENALTY
            else -> 0
        }
        val detail = uiText(R.string.detail_bpm_vs_norm, excess.roundToInt(), heartRate.roundToInt())
        return ReadinessFactor(ReadinessFactorKind.HEART_RATE, detail, points)
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
        return ReadinessFactor(ReadinessFactorKind.HRV, uiText(R.string.detail_ms, rmssd.roundToInt()), points)
    }

    private companion object {
        const val LOW_RMSSD_MS = 25.0
        const val PENALTY = -15
    }
}

/** Many workout steps in the last 24 h after a poor night; all steps are only shown beside them. */
class FatigueRule : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor? {
        val steps = inputs.steps24h ?: return null
        val poorSleep = inputs.sleepScore?.let { it < SleepThresholds.GOOD_SLEEP_SCORE } ?: false
        val points = if (steps > HIGH_STEPS && poorSleep) PENALTY else 0
        val workoutThousands = steps / THOUSAND
        val totalThousands = (inputs.totalSteps24h ?: steps) / THOUSAND
        val detail = inputs.sleepScore?.let { uiText(R.string.detail_steps_sleep, totalThousands, workoutThousands, it) }
            ?: uiText(R.string.detail_steps, totalThousands, workoutThousands)
        return ReadinessFactor(ReadinessFactorKind.FATIGUE, detail, points)
    }

    private companion object {
        const val HIGH_STEPS = 15_000.0
        const val THOUSAND = 1_000.0
        const val PENALTY = -15
    }
}

/** Multi-component fatigue index from [org.sand.ticwatch.phone.insights.FatigueIndexSource]; keeps [FatigueRule] intact. */
class SystemicFatigueRule : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor? {
        val percent = inputs.fatiguePercent ?: return null
        val points = when {
            percent >= EXHAUSTED_PERCENT -> EXHAUSTED_PENALTY
            percent >= PRONOUNCED_PERCENT -> PRONOUNCED_PENALTY
            else -> 0
        }
        val detail = uiText(R.string.detail_fatigue_index_percent, percent)
        return ReadinessFactor(ReadinessFactorKind.FATIGUE_INDEX, detail, points)
    }

    private companion object {
        const val PRONOUNCED_PERCENT = 60
        const val EXHAUSTED_PERCENT = 80
        const val PRONOUNCED_PENALTY = -15
        const val EXHAUSTED_PENALTY = -30
    }
}

/** Geomagnetic storm (Kp >= 5, NOAA G1+). */
class GeomagneticStormRule : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor? {
        val kp = inputs.kp ?: return null
        val points = if (kp >= STORM_KP) PENALTY else 0
        return ReadinessFactor(ReadinessFactorKind.GEOMAGNETIC, uiText(R.string.detail_kp, kp), points)
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
        val detail = JoinedText(
            listOfNotNull(sleep?.let { uiText(R.string.detail_sleep, it) }, dipping?.let { uiText(R.string.detail_dip, it) }),
            DETAIL_SEPARATOR,
        )
        return ReadinessFactor(ReadinessFactorKind.RECOVERY, detail, if (recovered) BONUS else 0)
    }

    private companion object {
        const val NORMAL_DIP_MIN_PERCENT = 10.0
        const val NORMAL_DIP_MAX_PERCENT = 20.0
        const val DETAIL_SEPARATOR = ", "
        const val BONUS = 10
    }
}

/** Informational: workout TRIMP of the last 48 h. */
class TrainingLoadInfoRule : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor? =
        inputs.trainingLoad48h?.let { ReadinessFactor(ReadinessFactorKind.TRAINING_LOAD, uiText(R.string.detail_trimp, it.roundToInt()), 0) }
}

/**
 * Medication phase from [MedicationProtectionTracker]: peak protection helps, the waning trough (morning tachycardia
 * risk) and an overdue intake limit. Evaluated per forecast hour too, so the morning forecast follows the dose.
 */
class MedicationProtectionRule(private val tracker: MedicationProtectionTracker) : ReadinessRule {
    override fun evaluate(inputs: CardioReadinessInputs): ReadinessFactor {
        val protection = tracker.protection(inputs.lastIntakeAt, inputs.at)
        val points = when (protection.phase) {
            MedicationProtectionPhase.PEAK -> PEAK_BONUS
            MedicationProtectionPhase.WANING -> WANING_PENALTY
            MedicationProtectionPhase.OVERDUE -> OVERDUE_PENALTY
            MedicationProtectionPhase.ONSET, MedicationProtectionPhase.PLATEAU, MedicationProtectionPhase.NOT_LOGGED -> 0
        }
        val phase = LowercaseText(uiText(protection.phase.label))
        val detail = protection.sinceIntake?.let { uiText(R.string.detail_after_intake, phase, it.toMinutes() / MINUTES_PER_HOUR) } ?: phase
        return ReadinessFactor(ReadinessFactorKind.MEDICATION, detail, points)
    }

    private companion object {
        const val MINUTES_PER_HOUR = 60.0
        const val PEAK_BONUS = 5
        const val WANING_PENALTY = -5
        const val OVERDUE_PENALTY = -15
    }
}

internal object SleepThresholds {
    const val GOOD_SLEEP_SCORE = 70
}
