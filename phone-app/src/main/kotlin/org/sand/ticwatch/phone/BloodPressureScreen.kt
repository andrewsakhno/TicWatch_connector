package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.sand.ticwatch.phone.dashboard.BloodPressureDayChart
import org.sand.ticwatch.phone.dashboard.GaugeColors
import org.sand.ticwatch.phone.dashboard.categoryColor
import org.sand.ticwatch.phone.bpm.CuffStatus
import org.sand.ticwatch.phone.bpm.CuffStatusSource
import org.sand.ticwatch.phone.health.BloodPressureCalibration
import org.sand.ticwatch.phone.health.BloodPressureCategory
import org.sand.ticwatch.phone.health.BloodPressureMeasurement
import org.sand.ticwatch.phone.health.BloodPressureMonitor
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.medication.MedicationDose
import org.sand.ticwatch.phone.medication.MedicationEffect
import org.sand.ticwatch.phone.medication.MedicationEffectReader
import org.sand.ticwatch.phone.medication.MedicationIntake
import org.sand.ticwatch.phone.medication.MedicationIntakeLog
import org.sand.ticwatch.phone.medication.MedicationRegimen
import org.sand.ticwatch.phone.report.CardioReportComposer
import org.sand.ticwatch.phone.health.CalibrationResult
import org.sand.ticwatch.phone.health.WristSignalsReader
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Dashboard "Blood Pressure" tab: calibrate against a cuff reading, estimate at rest from the wrist signals,
 * save the estimate (history + Health Connect) and chart the last [HISTORY_WINDOW] of readings (tap one to delete it).
 */
@Composable
fun BloodPressureScreen(
    monitor: BloodPressureMonitor,
    cuffStatus: CuffStatusSource,
    medication: MedicationTracking,
    reports: CardioReportComposer,
) {
    val coroutineScope = rememberCoroutineScope()
    var calibration by remember { mutableStateOf<BloodPressureCalibration?>(null) }
    var measurement by remember { mutableStateOf<BloodPressureMeasurement?>(null) }
    var recent by remember { mutableStateOf<List<BloodPressureReading>>(emptyList()) }
    var historyEnd by remember { mutableStateOf(Instant.now()) }
    var message by remember { mutableStateOf<String?>(null) }
    var cuff by remember { mutableStateOf<CuffStatus?>(null) }
    var intakes by remember { mutableStateOf<List<MedicationIntake>>(emptyList()) }
    var latestEffect by remember { mutableStateOf<MedicationEffect?>(null) }

    suspend fun reload() {
        calibration = monitor.calibration()
        historyEnd = Instant.now()
        recent = monitor.since(historyEnd.minus(HISTORY_WINDOW))
        cuff = withContext(Dispatchers.IO) { cuffStatus.cuffStatus() }
        intakes = medication.intakes.intakes(historyEnd.minus(HISTORY_WINDOW), historyEnd.plusMillis(1))
        latestEffect = intakes.lastOrNull()?.let { medication.effects.effect(it) }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(CARD_SPACING.dp),
    ) {
        CalibrationCard(calibration, monitor) { systolic, diastolic ->
            coroutineScope.launch {
                message = when (val result = monitor.calibrate(systolic, diastolic)) {
                    is CalibrationResult.Saved -> "Calibration saved at ${result.calibration.heartRate.roundToInt()} bpm"
                    CalibrationResult.Implausible -> "Enter a plausible cuff reading (systolic above diastolic)"
                    CalibrationResult.NoHeartRate -> "No recent heart rate from the watch - wear it and sync first"
                    CalibrationResult.Outdated -> "A newer calibration is already in use"
                }
                reload()
            }
        }
        MeasurementCard(
            measurement = measurement,
            onMeasure = { coroutineScope.launch { measurement = monitor.measure() } },
            onSave = { reading ->
                coroutineScope.launch {
                    message = if (monitor.log.save(reading).isSuccess) "Saved to history" else "Save failed"
                    measurement = null
                    reload()
                }
            },
        )
        message?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        MedicationCard(medication.regimen.medications(MedicationDose.MORNING), latestEffect, historyEnd) {
            coroutineScope.launch {
                message = medication.intakes.record(MedicationDose.MORNING).fold(
                    onSuccess = { "Intake logged at ${formatTime(it.takenAt)}" },
                    onFailure = { "Logging the intake failed: ${it.message}" },
                )
                reload()
            }
        }
        cuff?.let { CuffCard(it) }
        HistoryCard(recent, historyEnd.minus(HISTORY_WINDOW), historyEnd, intakes.map { it.takenAt }) { reading ->
            coroutineScope.launch {
                runCatching { monitor.log.delete(reading) }.onFailure { message = "Delete failed: ${it.message}" }
                reload()
            }
        }
        ReportExportCard(reports)
    }
}

/** What the medication card needs: the regimen, the intake log and the effect reader. */
class MedicationTracking(val regimen: MedicationRegimen, val intakes: MedicationIntakeLog, val effects: MedicationEffectReader)

@Composable
private fun CalibrationCard(
    calibration: BloodPressureCalibration?,
    monitor: BloodPressureMonitor,
    onSave: (Double, Double) -> Unit,
) = SectionCard("Calibration") {
    var systolicText by remember { mutableStateOf("") }
    var diastolicText by remember { mutableStateOf("") }
    Text(
        text = "Enter a cuff reading taken just now, sitting at rest with the watch on.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(FIELD_SPACING.dp)) {
        PressureField(systolicText, "SYS, mmHg", Modifier.weight(1f)) { systolicText = it }
        PressureField(diastolicText, "DIA, mmHg", Modifier.weight(1f)) { diastolicText = it }
    }
    Button(
        onClick = {
            val systolic = systolicText.toDoubleOrNull()
            val diastolic = diastolicText.toDoubleOrNull()
            if (systolic != null && diastolic != null) onSave(systolic, diastolic)
        },
        enabled = systolicText.isNotEmpty() && diastolicText.isNotEmpty(),
    ) { Text(text = "Save calibration") }
    if (calibration == null) {
        Text(text = "Not calibrated yet.", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(
            text = "${calibration.systolic.roundToInt()}/${calibration.diastolic.roundToInt()} mmHg at " +
                "${calibration.heartRate.roundToInt()} bpm, ${formatTime(calibration.calibratedAt)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (monitor.isStale(calibration)) {
            Text(
                text = "Calibration is older than ${BloodPressureMonitor.CALIBRATION_MAX_AGE.toDays()} days - recalibrate with a cuff.",
                color = GaugeColors.POOR,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MeasurementCard(
    measurement: BloodPressureMeasurement?,
    onMeasure: () -> Unit,
    onSave: (BloodPressureReading) -> Unit,
) = SectionCard("Estimate") {
    Button(onClick = onMeasure) { Text(text = "Calculate blood pressure") }
    when (measurement) {
        null -> Unit
        BloodPressureMeasurement.NotCalibrated -> Text(text = "Save a calibration first.")
        BloodPressureMeasurement.NoHeartRate ->
            Text(text = "No heart rate in the last ${WristSignalsReader.HEART_RATE_LOOKBACK.toMinutes()} min - sync the watch first.")
        is BloodPressureMeasurement.NotAtRest -> Text(
            text = "To measure, sit down and rest for ${WristSignalsReader.REST_WINDOW.toMinutes()} minutes " +
                "(${measurement.stepsPerMinute.roundToInt()} steps/min recently).",
            color = GaugeColors.POOR,
        )
        is BloodPressureMeasurement.Estimated -> {
            val reading = measurement.reading
            Text(
                text = "${reading.systolic.roundToInt()} / ${reading.diastolic.roundToInt()}",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = categoryColor(reading.category),
            )
            Text(text = "mmHg · ${reading.category.label}", color = categoryColor(reading.category))
            Text(
                text = "Pulse ${measurement.signals.heartRate.roundToInt()} bpm (watch, ${formatTime(measurement.signals.heartRateAt)})" +
                    " · calculated ${formatTime(reading.measuredAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Estimate from the watch, not a cuff measurement.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { onSave(reading) }) { Text(text = "Save to Health Connect") }
        }
    }
}

@Composable
private fun CuffCard(status: CuffStatus) = SectionCard("Bioland cuff") {
    Text(text = "Battery ${status.batteryPercent}%, serial ${status.serial}")
    Text(text = "Last connected ${formatTime(status.seenAt)}", style = MaterialTheme.typography.bodySmall)
    Text(
        text = "Readings sync when the cuff is on near the phone; one with watch heart rate within " +
            "${BloodPressureMonitor.CUFF_SIGNAL_MAX_GAP.toMinutes()} min recalibrates the estimate.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun HistoryCard(
    readings: List<BloodPressureReading>,
    start: Instant,
    end: Instant,
    intakes: List<Instant>,
    onDelete: (BloodPressureReading) -> Unit,
) = SectionCard("Last 24 hours") {
    BloodPressureDayChart(
        readings, start, end,
        selectedActions = { reading -> TextButton(onClick = { onDelete(reading) }) { Text(text = "Delete") } },
        markers = intakes,
    )
}

@Composable
private fun PressureField(text: String, label: String, modifier: Modifier, onChange: (String) -> Unit) = OutlinedTextField(
    value = text,
    onValueChange = { onChange(it.filter(Char::isDigit).take(PRESSURE_DIGITS)) },
    label = { Text(text = label) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    modifier = modifier,
)

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(FIELD_SPACING.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

internal fun formatTime(instant: Instant): String = TIME_FORMAT.format(instant.atZone(ZoneId.systemDefault()))

private val HISTORY_WINDOW: Duration = Duration.ofHours(24)
private const val PRESSURE_DIGITS = 3
private const val CARD_SPACING = 12
private const val FIELD_SPACING = 8
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")
