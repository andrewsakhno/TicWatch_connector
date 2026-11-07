package org.sand.ticwatch.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.math.roundToInt
import org.sand.ticwatch.phone.advisor.BaselineAssessment
import org.sand.ticwatch.phone.advisor.CalibrationAdvice
import org.sand.ticwatch.phone.advisor.ImmediateReadiness
import org.sand.ticwatch.phone.advisor.ImmediateState
import org.sand.ticwatch.phone.advisor.ModelProgress
import org.sand.ticwatch.phone.advisor.OffsetTraining
import org.sand.ticwatch.phone.advisor.PlanItem
import org.sand.ticwatch.phone.advisor.ReadinessPalette
import org.sand.ticwatch.phone.advisor.ReadinessState
import org.sand.ticwatch.phone.text.asString

private val SHEET_PADDING = 16.dp
private val ITEM_SPACING = 12.dp
private val LINE_SPACING = 4.dp
private const val TINT_ALPHA = 0.18f
private const val NO_VALUE = "--"
private const val CHECK_OK = "✓"
private const val CHECK_FAIL = "✗"

private val PROTOCOL_STEPS = listOf(
    R.string.calibration_protocol_rest,
    R.string.calibration_protocol_watch_estimate,
    R.string.calibration_protocol_cuff,
    R.string.calibration_protocol_strip,
)

private fun ReadinessState.tint(): Color = Color(ReadinessPalette.argb(this))

private fun ReadinessState.calibrationLabel(): Int = when (this) {
    ReadinessState.OPTIMAL -> R.string.calibration_status_good
    ReadinessState.MODERATE -> R.string.calibration_status_attention
    ReadinessState.LIMITED, ReadinessState.UNLOAD -> R.string.calibration_status_recalibrate
}

/** Advisor tab summary of the blood pressure calibration; tapping it opens [CalibrationAdvisorSheet]. */
@Composable
internal fun CalibrationAdvisorCard(advice: CalibrationAdvice, onOpen: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.elevatedCardColors(containerColor = advice.urgency.tint().copy(alpha = TINT_ALPHA)),
    ) {
        Column(modifier = Modifier.padding(SHEET_PADDING), verticalArrangement = Arrangement.spacedBy(LINE_SPACING)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.card_calibration_advisor), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusBadge(advice.urgency)
            }
            Text(text = advice.headline.asString(), style = MaterialTheme.typography.bodyMedium)
            advice.baseline.findings.firstOrNull()?.let {
                Text(text = stringResource(R.string.bullet, stringResource(it.issue.title)), style = MaterialTheme.typography.bodySmall)
            }
            Text(text = progressSummary(advice), style = MaterialTheme.typography.bodySmall)
            Text(text = stringResource(R.string.calibration_advisor_open), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusBadge(urgency: ReadinessState) {
    Text(
        text = stringResource(urgency.calibrationLabel()),
        style = MaterialTheme.typography.labelLarge,
        color = Color.Black,
        modifier = Modifier.background(urgency.tint(), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun progressSummary(advice: CalibrationAdvice): String {
    val baseline = advice.baseline.calibration?.let {
        stringResource(R.string.calibration_baseline_short, it.systolic.roundToInt(), it.diastolic.roundToInt(), it.heartRate.roundToInt())
    } ?: NO_VALUE
    val pulse = advice.progress.pulseWave
    val offset = advice.progress.offset
    return stringResource(
        R.string.calibration_progress_summary,
        baseline, pulse.pairs.coerceAtMost(pulse.required), pulse.required, offset.counted, offset.window, maeText(offset),
    )
}

@Composable
private fun maeText(offset: OffsetTraining): String {
    val systolic = offset.meanAbsoluteSystolicError ?: return NO_VALUE
    val diastolic = offset.meanAbsoluteDiastolicError ?: return NO_VALUE
    return stringResource(R.string.calibration_mae, systolic.roundToInt(), diastolic.roundToInt())
}

/** Full breakdown: the baseline, readiness right now, model training and the near-term plan with the measurement protocol. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalibrationAdvisorSheet(advice: CalibrationAdvice, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SHEET_PADDING),
            contentPadding = PaddingValues(bottom = SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
        ) {
            item { Text(text = stringResource(R.string.calibration_sheet_title), style = MaterialTheme.typography.titleLarge) }
            item { BaselineSection(advice.baseline) }
            item { RightNowSection(advice) }
            item { ModelsSection(advice.progress) }
            item { PlanSection(advice.plan, advice.at) }
        }
    }
}

@Composable
private fun BaselineSection(baseline: BaselineAssessment) = SectionCard(stringResource(R.string.calibration_section_baseline)) {
    val calibration = baseline.calibration
    if (calibration != null) {
        Detail(stringResource(R.string.calibration_label_pressure), stringResource(R.string.unit_mmhg_pair, calibration.systolic.roundToInt(), calibration.diastolic.roundToInt()))
        Detail(stringResource(R.string.calibration_label_pulse), stringResource(R.string.bpm_int, calibration.heartRate.roundToInt()))
        Detail(stringResource(R.string.calibration_signal_rmssd), calibration.rmssdMs?.let { stringResource(R.string.calibration_ms, it.roundToInt()) })
        Detail(stringResource(R.string.calibration_signal_skin), calibration.skinTemperatureCelsius?.let { stringResource(R.string.celsius_value, it) })
        Detail(stringResource(R.string.calibration_label_taken), formatTime(calibration.calibratedAt))
    }
    if (baseline.healthy) {
        Text(text = stringResource(R.string.calibration_baseline_healthy), style = MaterialTheme.typography.bodyMedium)
    }
    baseline.findings.forEach {
        Text(text = stringResource(it.issue.title), style = MaterialTheme.typography.titleSmall)
        Text(text = it.detail.asString(), style = MaterialTheme.typography.bodyMedium)
    }
    baseline.floor?.let {
        Text(text = stringResource(R.string.calibration_floor_explain, it.systolic.roundToInt(), it.diastolic.roundToInt()), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RightNowSection(advice: CalibrationAdvice) = SectionCard(stringResource(R.string.calibration_section_now)) {
    val immediate = advice.immediate
    Check(!immediate.has(ImmediateState.NOT_AT_REST_STEPS), stringResource(R.string.calibration_check_rest))
    Check(!immediate.has(ImmediateState.UNSTEADY_OR_HIGH_PULSE), stringResource(R.string.calibration_check_pulse))
    Check(!immediate.has(ImmediateState.POST_STRESS_ACTIVE), stringResource(R.string.calibration_check_autonomic))
    Check(!immediate.has(ImmediateState.MISSING_WRIST_SIGNALS), stringResource(R.string.calibration_check_signals))
    immediate.findings.forEach { Text(text = stringResource(R.string.bullet, it.detail.asString()), style = MaterialTheme.typography.bodyMedium) }
    Instruction(immediate)
}

@Composable
private fun Check(ok: Boolean, label: String) {
    Text(text = "${if (ok) CHECK_OK else CHECK_FAIL} $label", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Instruction(immediate: ImmediateReadiness) {
    Text(text = stringResource(R.string.calibration_do_now_title), style = MaterialTheme.typography.titleSmall)
    Text(text = immediate.instruction.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ModelsSection(progress: ModelProgress) = SectionCard(stringResource(R.string.calibration_section_models)) {
    val pulse = progress.pulseWave
    Text(text = stringResource(R.string.calibration_model_pulse_title), style = MaterialTheme.typography.titleSmall)
    ProgressLine(pulse.pairs.coerceAtMost(pulse.required), pulse.required)
    Text(text = progress.pulseWaveDetail.asString(), style = MaterialTheme.typography.bodyMedium)
    Detail(stringResource(R.string.calibration_label_loo_error), pulse.errorMmHg?.let { stringResource(R.string.calibration_plus_minus_mmhg, it.roundToInt()) })
    Text(text = stringResource(R.string.calibration_model_pulse_explain), style = MaterialTheme.typography.bodySmall)

    val offset = progress.offset
    Text(text = stringResource(R.string.calibration_model_offset_title), style = MaterialTheme.typography.titleSmall)
    ProgressLine(offset.counted, offset.window)
    Detail(stringResource(R.string.calibration_label_offset), stringResource(R.string.calibration_offset_value, offset.offsetMmHg.roundToInt()))
    Detail(stringResource(R.string.calibration_label_mae), maeText(offset))
    Text(text = stringResource(R.string.calibration_model_offset_explain), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ProgressLine(done: Int, required: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = { done.toFloat() / required }, modifier = Modifier.weight(1f))
        Text(text = stringResource(R.string.calibration_fraction, done, required), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PlanSection(plan: List<PlanItem>, now: Instant) = SectionCard(stringResource(R.string.calibration_section_plan)) {
    if (plan.isEmpty()) Text(text = stringResource(R.string.calibration_plan_nothing), style = MaterialTheme.typography.bodyMedium)
    plan.forEach { item ->
        Column {
            item.at?.let { Text(text = whenText(it, now), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            Text(text = item.detail.asString(), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(text = stringResource(R.string.calibration_protocol_title), style = MaterialTheme.typography.titleSmall)
    PROTOCOL_STEPS.forEachIndexed { index, step ->
        Text(text = stringResource(R.string.calibration_protocol_step, index + 1, stringResource(step)), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun whenText(at: Instant, now: Instant): String =
    if (!at.isAfter(now)) stringResource(R.string.calibration_when_now) else stringResource(R.string.calibration_when_at, formatTime(at))
