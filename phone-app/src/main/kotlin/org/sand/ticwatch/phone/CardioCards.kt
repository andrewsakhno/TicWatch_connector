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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.medication.EffectDelta
import org.sand.ticwatch.phone.medication.Medication
import org.sand.ticwatch.phone.medication.MedicationEffect
import org.sand.ticwatch.phone.report.AndroidCardioReportExporter
import org.sand.ticwatch.phone.report.CardioReportComposer
import org.sand.ticwatch.phone.report.CardioReportExporter
import org.sand.ticwatch.phone.report.CardioReportGenerator
import org.sand.ticwatch.phone.report.RenderedCardioReport
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Blood Pressure tab's medication card: the regimen, a one-tap "took my morning dose" log and the latest intake's
 * heart-rate / blood-pressure change at each checkpoint (a checkpoint still in the future shows as pending).
 */
@Composable
internal fun MedicationCard(medications: List<Medication>, latest: MedicationEffect?, now: Instant, onTaken: () -> Unit) =
    SectionCard("Medication") {
        medications.forEach { Text(text = "${it.name}: ${it.doseDescription()}", style = MaterialTheme.typography.bodySmall) }
        Button(onClick = onTaken) { Text(text = "Took morning medication (${medications.joinToString(" + ") { it.name }})") }
        if (latest == null) {
            Text(text = "No intake logged in the last 24 hours.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Text(text = "Taken ${formatTime(latest.intake.takenAt)}", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "Before: " + listOfNotNull(
                latest.baseline.heartRate?.let { "${it.roundToInt()} bpm" },
                latest.baseline.systolic?.let { "${it.roundToInt()}/${latest.baseline.diastolic?.roundToInt()} mmHg" },
            ).ifEmpty { listOf("no data") }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        latest.deltas.forEach { delta ->
            val due = latest.intake.takenAt.plus(delta.offset)
            Text(text = "+${delta.offset.toHours()} h: ${if (due.isAfter(now)) "pending" else deltaText(delta)}", style = MaterialTheme.typography.bodySmall)
        }
        latest.onset?.let { Text(text = "Heart rate dropped after ${it.toMinutes()} min", style = MaterialTheme.typography.bodySmall) }
    }

private fun deltaText(delta: EffectDelta): String = listOfNotNull(
    delta.heartRate?.let { "HR %+d bpm".format(it.roundToInt()) },
    delta.systolic?.let { "BP %+d/%+d mmHg".format(it.roundToInt(), delta.diastolic?.roundToInt() ?: 0) },
).ifEmpty { listOf("no data") }.joinToString(" · ")

/** "Export report for doctor": pick 1-7 days, then print / save as PDF or share as Markdown. */
@Composable
internal fun ReportExportCard(composer: CardioReportComposer) = SectionCard("Report for your doctor") {
    val context = LocalContext.current
    val exporter: CardioReportExporter = remember(context) { AndroidCardioReportExporter(context) }
    val coroutineScope = rememberCoroutineScope()
    var days by remember { mutableIntStateOf(DEFAULT_REPORT_DAYS) }
    var menuOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun export(action: (RenderedCardioReport) -> Unit) {
        menuOpen = false
        status = "Preparing report..."
        coroutineScope.launch {
            runCatching { withContext(Dispatchers.IO) { composer.compose(days) } }
                .onSuccess { status = null; action(it) }
                .onFailure { status = "Report failed: ${it.message}" }
        }
    }

    Text(text = "Period, days", style = MaterialTheme.typography.bodySmall)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING.dp)) {
        CardioReportGenerator.DAYS.forEach { option ->
            FilterChip(selected = option == days, onClick = { days = option }, label = { Text(text = "$option") })
        }
    }
    Box {
        OutlinedButton(onClick = { menuOpen = true }) { Text(text = "Export report for doctor") }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(text = "Share PDF / Print") }, onClick = { export { exporter.print(it.html, it.name) } })
            DropdownMenuItem(text = { Text(text = "Share as Markdown") }, onClick = { export { exporter.shareMarkdown(it.markdown, it.name) } })
        }
    }
    status?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
}

private const val DEFAULT_REPORT_DAYS = 7
private const val CHIP_SPACING = 6
