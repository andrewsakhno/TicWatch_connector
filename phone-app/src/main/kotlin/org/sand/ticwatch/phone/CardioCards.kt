package org.sand.ticwatch.phone

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.medication.EffectDelta
import org.sand.ticwatch.phone.medication.MedicationEffect
import org.sand.ticwatch.phone.medication.MedicationProtection
import org.sand.ticwatch.phone.report.AndroidCardioReportExporter
import org.sand.ticwatch.phone.report.CardioReportComposer
import org.sand.ticwatch.phone.report.CardioReportExporter
import org.sand.ticwatch.phone.report.CardioReportGenerator
import org.sand.ticwatch.phone.report.RenderedCardioReport
import org.sand.ticwatch.phone.text.asString

/**
 * Blood Pressure tab's medication card: a one-tap intake log (fact and time only - the therapy itself is managed
 * outside the app) and the latest intake's
 * heart-rate / blood-pressure change at each checkpoint (a checkpoint still in the future shows as pending).
 */
@Composable
internal fun MedicationCard(latest: MedicationEffect?, protection: MedicationProtection?, now: Instant, onTaken: () -> Unit) =
    SectionCard(stringResource(R.string.card_medication)) {
        Button(onClick = onTaken) { Text(text = stringResource(R.string.medication_taken)) }
        protection?.let { MedicationPhaseText(it) }
        if (latest == null) {
            Text(text = stringResource(R.string.no_intake_24h), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Text(text = stringResource(R.string.taken_at, formatTime(latest.intake.takenAt)), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(
                R.string.before,
                listOfNotNull(
                    latest.baseline.heartRate?.let { stringResource(R.string.bpm_int, it.roundToInt()) },
                    latest.baseline.systolic?.let {
                        stringResource(R.string.bp_pair_int, it.roundToInt(), latest.baseline.diastolic?.roundToInt()?.toString() ?: MISSING)
                    },
                ).ifEmpty { listOf(stringResource(R.string.no_data)) }.joinToString(VALUE_SEPARATOR),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        latest.deltas.forEach { delta ->
            val due = latest.intake.takenAt.plus(delta.offset)
            Text(text = stringResource(R.string.checkpoint, delta.offset.toHours(), if (due.isAfter(now)) stringResource(R.string.pending) else deltaText(delta)), style = MaterialTheme.typography.bodySmall)
        }
        latest.onset?.let { Text(text = stringResource(R.string.hr_dropped_after, it.toMinutes()), style = MaterialTheme.typography.bodySmall) }
    }

/** Current pharmacodynamic phase of the last intake and when the next one starts; shared with the Advisor tab. */
@Composable
internal fun MedicationPhaseText(protection: MedicationProtection) {
    val next = protection.nextPhaseIn?.let { stringResource(R.string.next_phase_in, it.toHours(), it.toMinutes() % MINUTES_PER_HOUR) }.orEmpty()
    Text(text = stringResource(R.string.phase_line, stringResource(protection.phase.label), next), style = MaterialTheme.typography.titleSmall)
    Text(text = protection.phase.description.asString(), style = MaterialTheme.typography.bodySmall)
}

private const val MINUTES_PER_HOUR = 60
private const val VALUE_SEPARATOR = " · "
private const val MISSING = "--"

@Composable
private fun deltaText(delta: EffectDelta): String = listOfNotNull(
    delta.heartRate?.let { stringResource(R.string.hr_delta, it.roundToInt()) },
    delta.systolic?.let { stringResource(R.string.bp_delta, it.roundToInt(), delta.diastolic?.roundToInt() ?: 0) },
).ifEmpty { listOf(stringResource(R.string.no_data)) }.joinToString(VALUE_SEPARATOR)

/** "Export report for doctor": pick 1-7 days, then print / save as PDF or share as Markdown. */
@Composable
internal fun ReportExportCard(composer: CardioReportComposer) = SectionCard(stringResource(R.string.card_report)) {
    val context = LocalContext.current
    val exporter: CardioReportExporter = remember(context) { AndroidCardioReportExporter(context) }
    val coroutineScope = rememberCoroutineScope()
    var days by remember { mutableIntStateOf(DEFAULT_REPORT_DAYS) }
    var menuOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun export(action: (RenderedCardioReport) -> Unit) {
        menuOpen = false
        status = context.getString(R.string.report_preparing)
        coroutineScope.launch {
            runCatching { withContext(Dispatchers.IO) { composer.compose(days) } }
                .onSuccess { status = null; action(it) }
                .onFailure { status = context.getString(R.string.report_failed, it.message.orEmpty()) }
        }
    }

    Text(text = stringResource(R.string.report_period_days), style = MaterialTheme.typography.bodySmall)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING.dp)) {
        CardioReportGenerator.DAYS.forEach { option ->
            FilterChip(selected = option == days, onClick = { days = option }, label = { Text(text = "$option") })
        }
    }
    Box {
        OutlinedButton(onClick = { menuOpen = true }) { Text(text = stringResource(R.string.report_export)) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(text = stringResource(R.string.report_share_pdf)) }, onClick = { export { exporter.print(it.html, it.name) } })
            DropdownMenuItem(text = { Text(text = stringResource(R.string.report_share_markdown)) }, onClick = { export { exporter.shareMarkdown(it.markdown, it.name) } })
        }
    }
    status?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
}

private const val DEFAULT_REPORT_DAYS = 7
private const val CHIP_SPACING = 6
