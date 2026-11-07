package org.sand.ticwatch.phone.advisor

import androidx.annotation.StringRes
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.health.BloodPressureCalibration
import org.sand.ticwatch.phone.health.BloodPressureCoefficients
import org.sand.ticwatch.phone.health.BloodPressureMonitor
import org.sand.ticwatch.phone.health.CalibrationRestLimits
import org.sand.ticwatch.phone.health.CalibrationRestViolation
import org.sand.ticwatch.phone.health.WristSignals
import org.sand.ticwatch.phone.medication.MedicationProtection
import org.sand.ticwatch.phone.medication.MedicationProtectionPhase
import org.sand.ticwatch.phone.text.JoinedText
import org.sand.ticwatch.phone.text.TimeOfDayText
import org.sand.ticwatch.phone.text.UiText
import org.sand.ticwatch.phone.text.uiText

/** Thresholds of [CalibrationAdvisor]. */
object CalibrationAdvisorLimits {
    val AGING_AFTER: Duration = Duration.ofDays(14)
    val STALE_AFTER: Duration = BloodPressureMonitor.CALIBRATION_MAX_AGE
    const val HYPERTENSIVE_SYSTOLIC = 135.0
    const val HYPERTENSIVE_DIASTOLIC = 88.0
    const val ELEVATED_ABOVE_RESTING_BPM = 10.0
    const val ELEVATED_FALLBACK_BPM = CalibrationRestLimits.FALLBACK_REST_CEILING_BPM
    const val REST_MAX_STEPS_PER_MINUTE = BloodPressureMonitor.REST_MAX_STEPS_PER_MINUTE

    /** Pulse-wave pairs spanning less than this systolic range can't teach the model how stiffness tracks pressure. */
    const val MIN_TRAINING_RANGE_MMHG = 12.0
}

/** Pulse-wave training set: the cuff systolic of each stored pair, newest first, and the model's leave-one-out error. */
data class PulseWaveTraining(val pairSystolics: List<Double>, val required: Int, val errorMmHg: Double?) {
    val pairs: Int get() = pairSystolics.size
    val remaining: Int get() = (required - pairs).coerceAtLeast(0)
    val active: Boolean get() = pairs >= required
    val minSystolic: Double? get() = pairSystolics.minOrNull()
    val maxSystolic: Double? get() = pairSystolics.maxOrNull()
    val rangeMmHg: Double? get() = maxSystolic?.let { max -> minSystolic?.let { max - it } }
}

/** Cuff-bias offset training: wrist-before-cuff comparisons counted under the current offset, and the recent errors. */
data class OffsetTraining(
    val comparisonsBeforeCuff: Int,
    val window: Int,
    val offsetMmHg: Double,
    val meanAbsoluteSystolicError: Double?,
    val meanAbsoluteDiastolicError: Double?,
) {
    val counted: Int get() = comparisonsBeforeCuff.coerceAtMost(window)
    val remaining: Int get() = window - counted
}

/** Everything [CalibrationAdvisor] judges, gathered at [at]. */
data class CalibrationAdvisorInputs(
    val at: Instant,
    val calibration: BloodPressureCalibration?,
    val restingHeartRateBpm: Double?,
    val stepsPerMinute: Double,
    val wrist: WristSignals?,
    val restViolation: CalibrationRestViolation?,
    val pulseWave: PulseWaveTraining,
    val offset: OffsetTraining,
    val autonomic: AutonomicBalance?,
    val medication: MedicationProtection,
    val forecast: List<ReadinessForecastHour>,
)

/** What is wrong with the stored baseline, most severe first; [needsRecalibration] issues ask for a new cuff baseline. */
enum class BaselineIssue(@StringRes val title: Int, val needsRecalibration: Boolean, val blocking: Boolean) {
    MISSING(R.string.calibration_issue_missing, true, true),
    STALE(R.string.calibration_issue_stale, true, true),
    HYPERTENSIVE_TRAP(R.string.calibration_issue_hypertensive, true, true),
    ELEVATED_HEART_RATE(R.string.calibration_issue_elevated_hr, true, false),
    INCOMPLETE_SIGNALS(R.string.calibration_issue_incomplete, true, false),
    AGING(R.string.calibration_issue_aging, false, false),
}

data class BaselineFinding(val issue: BaselineIssue, val detail: UiText)

/** Lowest resting estimate the baseline allows while the pulse stays inside the resting corridor. */
data class RestingFloor(val systolic: Double, val diastolic: Double)

data class BaselineAssessment(val calibration: BloodPressureCalibration?, val age: Duration?, val floor: RestingFloor?, val findings: List<BaselineFinding>) {
    val healthy: Boolean get() = findings.isEmpty()
    val needsRecalibration: Boolean get() = findings.any { it.issue.needsRecalibration }
}

/** Whether a cuff reading taken right now makes a good calibration; [READY_NOW] only without any blocker. */
enum class ImmediateState(@StringRes val title: Int) {
    NOT_AT_REST_STEPS(R.string.calibration_now_walking),
    UNSTEADY_OR_HIGH_PULSE(R.string.calibration_now_pulse),
    POST_STRESS_ACTIVE(R.string.calibration_now_post_stress),
    MISSING_WRIST_SIGNALS(R.string.calibration_now_missing_signals),
    READY_NOW(R.string.calibration_now_ready),
}

data class ImmediateFinding(val state: ImmediateState, val detail: UiText)

data class ImmediateReadiness(val findings: List<ImmediateFinding>, val instruction: UiText) {
    val state: ImmediateState get() = findings.firstOrNull()?.state ?: ImmediateState.READY_NOW
    val readyNow: Boolean get() = findings.isEmpty()
    fun has(state: ImmediateState): Boolean = findings.any { it.state == state }
}

/** How much of the systolic range the pulse-wave pairs cover. */
enum class PulseWaveCoverage { NONE, ONLY_CALM, ONLY_ELEVATED, NARROW, BROAD }

data class ModelProgress(val pulseWave: PulseWaveTraining, val coverage: PulseWaveCoverage, val offset: OffsetTraining) {
    val pulseWaveDetail: UiText get() = coverageText(coverage, pulseWave)
}

/** One near-term recommendation; [at] is when to act, null when no window is known. */
enum class PlanKind { RECALIBRATE, MORE_PAIRS, BROADEN_RANGE, OFFSET_COMPARISONS }

data class PlanItem(val kind: PlanKind, val at: Instant?, val detail: UiText)

/** Result of [CalibrationAdvisor.advise]; [urgency] reuses the readiness palette for its tint. */
data class CalibrationAdvice(
    val at: Instant,
    val urgency: ReadinessState,
    val headline: UiText,
    val baseline: BaselineAssessment,
    val immediate: ImmediateReadiness,
    val progress: ModelProgress,
    val plan: List<PlanItem>,
)

/** One diagnostic of the stored baseline; null = no problem of this kind. */
fun interface BaselineCheck {
    fun check(calibration: BloodPressureCalibration, inputs: CalibrationAdvisorInputs, floor: RestingFloor): BaselineFinding?
}

/** One reason a cuff reading right now would make a poor calibration; null = no objection. */
fun interface ImmediateCheck {
    fun check(inputs: CalibrationAdvisorInputs): ImmediateFinding?
}

/**
 * Pure evaluator of the blood pressure calibration: the stored baseline, whether right now is a good moment for a
 * cuff reading, how far the pulse-wave model and the cuff-bias offset are trained, and when to act next.
 */
class CalibrationAdvisor(
    private val coefficients: BloodPressureCoefficients = BloodPressureCoefficients(),
    private val baselineChecks: List<BaselineCheck> = defaultBaselineChecks(),
    private val immediateChecks: List<ImmediateCheck> = defaultImmediateChecks(),
) {

    fun advise(inputs: CalibrationAdvisorInputs): CalibrationAdvice {
        val baseline = baseline(inputs)
        val immediate = immediate(inputs, baseline)
        val progress = ModelProgress(inputs.pulseWave, coverage(inputs.pulseWave), inputs.offset)
        return CalibrationAdvice(
            at = inputs.at,
            urgency = urgency(baseline, progress),
            headline = headline(baseline, immediate, progress),
            baseline = baseline,
            immediate = immediate,
            progress = progress,
            plan = NearTermPlanner.plan(inputs, baseline, progress),
        )
    }

    /** `min(SYS, reference) - maxDrop` (see [org.sand.ticwatch.phone.health.BloodPressureEstimator]) and `DIA - maxDiastolicShift`. */
    fun restingFloor(calibration: BloodPressureCalibration): RestingFloor = RestingFloor(
        systolic = minOf(calibration.systolic, coefficients.normotensiveSystolicReference) - coefficients.maxRestingSystolicDrop,
        diastolic = calibration.diastolic - coefficients.maxDiastolicShift,
    )

    private fun baseline(inputs: CalibrationAdvisorInputs): BaselineAssessment {
        val calibration = inputs.calibration
            ?: return BaselineAssessment(null, null, null, listOf(BaselineFinding(BaselineIssue.MISSING, uiText(R.string.calibration_issue_missing_detail))))
        val floor = restingFloor(calibration)
        val findings = baselineChecks.mapNotNull { it.check(calibration, inputs, floor) }.sortedBy { it.issue.ordinal }
        return BaselineAssessment(calibration, Duration.between(calibration.calibratedAt, inputs.at), floor, findings)
    }

    private fun immediate(inputs: CalibrationAdvisorInputs, baseline: BaselineAssessment): ImmediateReadiness {
        val findings = immediateChecks.mapNotNull { it.check(inputs) }.sortedBy { it.state.ordinal }
        val instruction = when (findings.firstOrNull()?.state) {
            ImmediateState.NOT_AT_REST_STEPS -> uiText(R.string.calibration_do_sit)
            ImmediateState.UNSTEADY_OR_HIGH_PULSE -> uiText(R.string.calibration_do_wait_pulse)
            ImmediateState.POST_STRESS_ACTIVE -> uiText(R.string.calibration_do_post_stress)
            ImmediateState.MISSING_WRIST_SIGNALS -> uiText(R.string.calibration_do_sync)
            ImmediateState.READY_NOW, null ->
                if (baseline.needsRecalibration) uiText(R.string.calibration_do_recalibrate) else uiText(R.string.calibration_do_pair)
        }
        return ImmediateReadiness(findings, instruction)
    }

    private fun coverage(training: PulseWaveTraining): PulseWaveCoverage {
        val min = training.minSystolic ?: return PulseWaveCoverage.NONE
        val max = training.maxSystolic ?: return PulseWaveCoverage.NONE
        return when {
            max - min >= CalibrationAdvisorLimits.MIN_TRAINING_RANGE_MMHG -> PulseWaveCoverage.BROAD
            max < CalibrationAdvisorLimits.HYPERTENSIVE_SYSTOLIC -> PulseWaveCoverage.ONLY_CALM
            min >= CalibrationAdvisorLimits.HYPERTENSIVE_SYSTOLIC -> PulseWaveCoverage.ONLY_ELEVATED
            else -> PulseWaveCoverage.NARROW
        }
    }

    private fun urgency(baseline: BaselineAssessment, progress: ModelProgress): ReadinessState = when {
        baseline.findings.any { it.issue.blocking } -> ReadinessState.LIMITED
        !baseline.healthy || !progress.pulseWave.active || progress.coverage != PulseWaveCoverage.BROAD -> ReadinessState.MODERATE
        else -> ReadinessState.OPTIMAL
    }

    private fun headline(baseline: BaselineAssessment, immediate: ImmediateReadiness, progress: ModelProgress): UiText = when {
        !immediate.readyNow -> immediate.findings.first().detail
        baseline.needsRecalibration -> uiText(R.string.calibration_headline_recalibrate_now)
        !progress.pulseWave.active -> uiText(R.string.calibration_headline_pair_now, progress.pulseWave.remaining)
        else -> uiText(R.string.calibration_headline_check_now)
    }

    companion object {
        fun defaultBaselineChecks(): List<BaselineCheck> = listOf(StaleCheck, HypertensiveTrapCheck, ElevatedHeartRateCheck, IncompleteSignalsCheck)

        fun defaultImmediateChecks(): List<ImmediateCheck> = listOf(StepsCheck, PulseCheck, PostStressCheck, WristSignalsCheck)
    }
}

private fun Double.mmHg(): Int = roundToInt()

private val StaleCheck = BaselineCheck { calibration, inputs, _ ->
    val age = Duration.between(calibration.calibratedAt, inputs.at)
    val days = age.toDays()
    when {
        age > CalibrationAdvisorLimits.STALE_AFTER -> BaselineFinding(BaselineIssue.STALE, uiText(R.string.calibration_issue_stale_detail, days))
        age > CalibrationAdvisorLimits.AGING_AFTER -> BaselineFinding(BaselineIssue.AGING, uiText(R.string.calibration_issue_aging_detail, days))
        else -> null
    }
}

private val HypertensiveTrapCheck = BaselineCheck { calibration, _, floor ->
    if (calibration.systolic < CalibrationAdvisorLimits.HYPERTENSIVE_SYSTOLIC && calibration.diastolic < CalibrationAdvisorLimits.HYPERTENSIVE_DIASTOLIC) {
        null
    } else {
        BaselineFinding(
            BaselineIssue.HYPERTENSIVE_TRAP,
            uiText(
                R.string.calibration_issue_hypertensive_detail,
                calibration.systolic.mmHg(), calibration.diastolic.mmHg(), floor.systolic.mmHg(), floor.diastolic.mmHg(),
            ),
        )
    }
}

private val ElevatedHeartRateCheck = BaselineCheck { calibration, inputs, _ ->
    val limit = inputs.restingHeartRateBpm?.let { it + CalibrationAdvisorLimits.ELEVATED_ABOVE_RESTING_BPM } ?: CalibrationAdvisorLimits.ELEVATED_FALLBACK_BPM
    if (calibration.heartRate <= limit) {
        null
    } else {
        BaselineFinding(
            BaselineIssue.ELEVATED_HEART_RATE,
            uiText(R.string.calibration_issue_elevated_hr_detail, calibration.heartRate.roundToInt(), limit.roundToInt()),
        )
    }
}

private val IncompleteSignalsCheck = BaselineCheck { calibration, _, _ ->
    val missing = listOfNotNull(
        R.string.calibration_signal_rmssd.takeIf { calibration.rmssdMs == null },
        R.string.calibration_signal_skin.takeIf { calibration.skinTemperatureCelsius == null },
    )
    if (missing.isEmpty()) null else BaselineFinding(BaselineIssue.INCOMPLETE_SIGNALS, uiText(R.string.calibration_issue_incomplete_detail, missingSignals(missing)))
}

private val StepsCheck = ImmediateCheck { inputs ->
    if (inputs.stepsPerMinute <= CalibrationAdvisorLimits.REST_MAX_STEPS_PER_MINUTE) {
        null
    } else {
        ImmediateFinding(ImmediateState.NOT_AT_REST_STEPS, uiText(R.string.calibration_now_walking_detail, inputs.stepsPerMinute.roundToInt()))
    }
}

private val PulseCheck = ImmediateCheck { inputs ->
    when (val violation = inputs.restViolation) {
        null -> null
        is CalibrationRestViolation.Unsteady -> ImmediateFinding(
            ImmediateState.UNSTEADY_OR_HIGH_PULSE,
            uiText(R.string.calibration_now_unsteady_detail, violation.heartRate.roundToInt(), violation.bpmPerMinute),
        )
        is CalibrationRestViolation.AboveResting -> ImmediateFinding(
            ImmediateState.UNSTEADY_OR_HIGH_PULSE,
            uiText(R.string.calibration_now_above_resting_detail, violation.heartRate.roundToInt(), violation.restingHeartRate.roundToInt()),
        )
        is CalibrationRestViolation.AboveRestCeiling -> ImmediateFinding(
            ImmediateState.UNSTEADY_OR_HIGH_PULSE,
            uiText(R.string.calibration_now_above_resting_detail, violation.heartRate.roundToInt(), violation.ceilingBpm.roundToInt()),
        )
    }
}

private val PostStressCheck = ImmediateCheck { inputs ->
    val autonomic = inputs.autonomic ?: return@ImmediateCheck null
    if (!autonomic.postStressActive && autonomic.tone != AutonomicTone.STRAINED) return@ImmediateCheck null
    val detail = autonomic.window?.let { uiText(R.string.calibration_now_post_stress_until, TimeOfDayText(it.endsAt)) }
        ?: uiText(R.string.calibration_now_strained_detail)
    ImmediateFinding(ImmediateState.POST_STRESS_ACTIVE, detail)
}

private val WristSignalsCheck = ImmediateCheck { inputs ->
    val wrist = inputs.wrist
    val missing = listOfNotNull(
        R.string.calibration_signal_hr.takeIf { wrist == null },
        R.string.calibration_signal_rmssd.takeIf { wrist?.rmssdMs == null },
        R.string.calibration_signal_skin.takeIf { wrist?.skinTemperatureCelsius == null },
    )
    if (missing.isEmpty()) null else ImmediateFinding(ImmediateState.MISSING_WRIST_SIGNALS, uiText(R.string.calibration_now_missing_detail, missingSignals(missing)))
}

private const val SIGNAL_SEPARATOR = ", "

/** Signal names joined in the app language. */
private fun missingSignals(@StringRes signals: List<Int>): UiText = JoinedText(signals.map { uiText(it) }, SIGNAL_SEPARATOR)

private fun coverageText(coverage: PulseWaveCoverage, training: PulseWaveTraining): UiText {
    val min = training.minSystolic?.mmHg() ?: 0
    val max = training.maxSystolic?.mmHg() ?: 0
    return when (coverage) {
        PulseWaveCoverage.NONE -> uiText(R.string.calibration_coverage_none)
        PulseWaveCoverage.ONLY_CALM -> uiText(R.string.calibration_coverage_only_calm, min, max)
        PulseWaveCoverage.ONLY_ELEVATED -> uiText(R.string.calibration_coverage_only_elevated, min, max)
        PulseWaveCoverage.NARROW -> uiText(R.string.calibration_coverage_narrow, min, max)
        PulseWaveCoverage.BROAD -> uiText(R.string.calibration_coverage_broad, min, max)
    }
}

/** Ties the recommendations to the medication phase and the readiness forecast. */
internal object NearTermPlanner {
    private val CALM_FORECAST_STATES = setOf(ReadinessState.OPTIMAL, ReadinessState.MODERATE)
    private val CALM_PHASES = setOf(MedicationProtectionPhase.PEAK, MedicationProtectionPhase.PLATEAU)
    private val UNPROTECTED_PHASES = setOf(MedicationProtectionPhase.WANING, MedicationProtectionPhase.OVERDUE, MedicationProtectionPhase.NOT_LOGGED)

    fun plan(inputs: CalibrationAdvisorInputs, baseline: BaselineAssessment, progress: ModelProgress): List<PlanItem> = buildList {
        if (baseline.needsRecalibration) {
            add(PlanItem(PlanKind.RECALIBRATE, calmWindow(inputs), uiText(R.string.calibration_plan_recalibrate)))
        }
        if (!progress.pulseWave.active) {
            add(PlanItem(PlanKind.MORE_PAIRS, null, uiText(R.string.calibration_plan_more_pairs, progress.pulseWave.remaining, progress.pulseWave.required)))
        }
        when (progress.coverage) {
            PulseWaveCoverage.ONLY_CALM -> add(PlanItem(PlanKind.BROADEN_RANGE, unprotectedWindow(inputs), uiText(R.string.calibration_plan_pair_unprotected)))
            PulseWaveCoverage.ONLY_ELEVATED -> add(PlanItem(PlanKind.BROADEN_RANGE, calmWindow(inputs), uiText(R.string.calibration_plan_pair_calm)))
            PulseWaveCoverage.NARROW -> add(PlanItem(PlanKind.BROADEN_RANGE, unprotectedWindow(inputs), uiText(R.string.calibration_plan_pair_both)))
            PulseWaveCoverage.NONE, PulseWaveCoverage.BROAD -> Unit
        }
        if (progress.offset.remaining > 0) {
            add(PlanItem(PlanKind.OFFSET_COMPARISONS, null, uiText(R.string.calibration_plan_offset_order, progress.offset.remaining)))
        }
    }

    /** Now during the medication's peak/plateau, else when the peak begins, else the next calm forecast hour. */
    private fun calmWindow(inputs: CalibrationAdvisorInputs): Instant? {
        val medication = inputs.medication
        if (medication.phase in CALM_PHASES) return inputs.at
        if (medication.phase == MedicationProtectionPhase.ONSET) medication.nextPhaseIn?.let { return inputs.at.plus(it) }
        return inputs.forecast.firstOrNull { it.state in CALM_FORECAST_STATES && !it.start.isBefore(inputs.at) }?.start
    }

    /** Now when the dose has worn off, else when the waning phase begins. */
    private fun unprotectedWindow(inputs: CalibrationAdvisorInputs): Instant? {
        val medication = inputs.medication
        if (medication.phase in UNPROTECTED_PHASES) return inputs.at
        val waningAfter = MedicationProtectionPhase.WANING.startsAfter ?: return null
        return medication.lastIntakeAt?.plus(waningAfter)
    }
}
