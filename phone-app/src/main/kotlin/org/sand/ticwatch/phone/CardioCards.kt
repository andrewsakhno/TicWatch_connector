package org.sand.ticwatch.phone

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.medication.MedicationIntake
import org.sand.ticwatch.phone.medication.MedicationProtection
import org.sand.ticwatch.phone.report.AndroidCardioReportExporter
import org.sand.ticwatch.phone.report.CardioReportComposer
import org.sand.ticwatch.phone.report.CardioReportExporter
import org.sand.ticwatch.phone.report.CardioReportGenerator
import org.sand.ticwatch.phone.report.RenderedCardioReport
import org.sand.ticwatch.phone.text.asString
import org.sand.ticwatch.phone.ui.ConnectedChoiceRow

/**
 * Blood Pressure tab's medication card: a one-tap intake log (fact and time only - the therapy itself is managed
 * outside the app) and the logged intake times, newest first, within the "Keep history" retention.
 */
@Composable
internal fun MedicationCard(intakes: List<MedicationIntake>, onTaken: () -> Unit) =
    SectionCard(stringResource(R.string.card_medication)) {
        Button(onClick = onTaken) { Text(text = stringResource(R.string.medication_taken)) }
        if (intakes.isEmpty()) {
            Text(text = stringResource(R.string.no_intakes_logged), style = MaterialTheme.typography.bodyMedium)
        }
        intakes.forEach { Text(text = formatTime(it.takenAt), style = MaterialTheme.typography.bodyMedium) }
    }

/** Current pharmacodynamic phase of the last intake and when the next one starts; shared with the Advisor tab. */
@Composable
internal fun MedicationPhaseText(protection: MedicationProtection) {
    val next = protection.nextPhaseIn?.let { stringResource(R.string.next_phase_in, it.toHours(), it.toMinutes() % MINUTES_PER_HOUR) }.orEmpty()
    Text(text = stringResource(R.string.phase_line, stringResource(protection.phase.label), next), style = MaterialTheme.typography.titleSmall)
    Text(text = protection.phase.description.asString(), style = MaterialTheme.typography.bodySmall)
}

private const val MINUTES_PER_HOUR = 60

/** "Export report for doctor": pick 1-7 days, then print / save as PDF or share as Markdown. */
@Composable
internal fun ReportExportCard(composer: CardioReportComposer) = SectionCard(stringResource(R.string.card_report)) {
    val context = LocalContext.current
    val exporter: CardioReportExporter = remember(context) { AndroidCardioReportExporter(context) }
    val coroutineScope = rememberCoroutineScope()
    var days by rememberSaveable { mutableIntStateOf(DEFAULT_REPORT_DAYS) }
    var menuOpen by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }

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
    ConnectedChoiceRow(options = CardioReportGenerator.DAYS.toList(), selected = days, onSelect = { days = it }, label = { "$it" })
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
