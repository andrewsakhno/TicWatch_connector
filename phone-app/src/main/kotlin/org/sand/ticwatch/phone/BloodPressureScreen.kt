package org.sand.ticwatch.phone

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import org.sand.ticwatch.phone.bpm.CuffStatus
import org.sand.ticwatch.phone.bpm.CuffStatusSource
import org.sand.ticwatch.phone.dashboard.BloodPressureDayChart
import org.sand.ticwatch.phone.dashboard.GaugeColors
import org.sand.ticwatch.phone.dashboard.categoryColor
import org.sand.ticwatch.phone.datalayer.WatchSyncRequester
import org.sand.ticwatch.phone.health.BloodPressureCalibration
import org.sand.ticwatch.phone.health.BloodPressureCategory
import org.sand.ticwatch.phone.health.BloodPressureMeasurement
import org.sand.ticwatch.phone.health.BloodPressureMonitor
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.CalibrationResult
import org.sand.ticwatch.phone.health.WristSignalsReader
import org.sand.ticwatch.phone.medication.MedicationDose
import org.sand.ticwatch.phone.medication.MedicationEffect
import org.sand.ticwatch.phone.history.HistoryRetentionStore
import org.sand.ticwatch.phone.medication.MedicationIntake
import org.sand.ticwatch.phone.medication.MedicationIntakeLog
import org.sand.ticwatch.phone.report.CardioReportComposer
import org.sand.ticwatch.phone.text.asString
import org.sand.ticwatch.phone.ui.rememberStore

/**
 * Dashboard "Blood Pressure / ECG" tab: calibrate against a cuff reading, estimate at rest from the wrist signals,
 * auto-save each estimate (history + Health Connect), record a watch rhythm strip ([EcgCard]) and chart the last [HISTORY_WINDOW] of readings (tap one to delete it).
 */
@Composable
fun BloodPressureScreen(
    monitor: BloodPressureMonitor,
    watchSync: WatchSyncRequester,
    cuffStatus: CuffStatusSource,
    medication: MedicationTracking,
    reports: CardioReportComposer,
    ecg: EcgDependencies,
    changes: Flow<*>,
) {
    val store = rememberStore(monitor) { scope, io -> BloodPressureStore(monitor, watchSync, cuffStatus, medication, changes, scope, io) }
    val state by store.state.collectAsState()
    val ecgStore = rememberEcgStore(ecg)

    // Lazy: a data push recomposes only the visible cards, not every chart of the tab mid-scroll.
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(CARD_SPACING.dp),
    ) {
        item(key = BloodPressureItem.CALIBRATION) { CalibrationCard(state.calibration, monitor, store::calibrate) }
        item(key = BloodPressureItem.MEASUREMENT) { MeasurementCard(measurement = state.measurement, onMeasure = store::measure) }
        state.message?.let { message ->
            item(key = BloodPressureItem.MESSAGE) { Text(text = message.asString(), style = MaterialTheme.typography.bodyMedium) }
        }
        item(key = BloodPressureItem.ECG) { EcgCard(ecgStore) }
        item(key = BloodPressureItem.MEDICATION) { MedicationCard(state.intakeHistory, store::logIntake) }
        state.cuff?.let { cuff -> item(key = BloodPressureItem.CUFF) { CuffCard(cuff) } }
        item(key = BloodPressureItem.HISTORY) {
            HistoryCard(
                state.recent, state.historyEnd.minus(HISTORY_WINDOW), state.historyEnd, state.intakes.map { it.takenAt },
                store::delete,
            )
        }
        item(key = BloodPressureItem.REPORT) { ReportExportCard(reports) }
    }
}

/** Stable keys of the tab's lazy list items. */
private enum class BloodPressureItem { CALIBRATION, MEASUREMENT, MESSAGE, ECG, MEDICATION, CUFF, HISTORY, REPORT }

/** What the medication card needs: the intake log and the history retention that bounds the listed intakes. */
class MedicationTracking(
    val intakes: MedicationIntakeLog,
    val retention: HistoryRetentionStore,
)

@Composable
private fun CalibrationCard(
    calibration: BloodPressureCalibration?,
    monitor: BloodPressureMonitor,
    onSave: (Double, Double) -> Unit,
) = SectionCard(stringResource(R.string.card_calibration)) {
    var systolicText by rememberSaveable { mutableStateOf("") }
    var diastolicText by rememberSaveable { mutableStateOf("") }
    Text(
        text = stringResource(R.string.calibration_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(FIELD_SPACING.dp)) {
        PressureField(systolicText, stringResource(R.string.field_sys), Modifier.weight(1f)) { systolicText = it }
        PressureField(diastolicText, stringResource(R.string.field_dia), Modifier.weight(1f)) { diastolicText = it }
    }
    Button(
        onClick = {
            val systolic = systolicText.toDoubleOrNull()
            val diastolic = diastolicText.toDoubleOrNull()
            if (systolic != null && diastolic != null) onSave(systolic, diastolic)
        },
        enabled = systolicText.isNotEmpty() && diastolicText.isNotEmpty(),
    ) { Text(text = stringResource(R.string.save_calibration)) }
    if (calibration == null) {
        Text(text = stringResource(R.string.not_calibrated), style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(
            text = stringResource(
                R.string.calibration_summary, calibration.systolic.roundToInt(), calibration.diastolic.roundToInt(),
                calibration.heartRate.roundToInt(), formatTime(calibration.calibratedAt),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (monitor.isStale(calibration)) {
            Text(
                text = stringResource(R.string.calibration_stale, BloodPressureMonitor.CALIBRATION_MAX_AGE.toDays()),
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
) = SectionCard(stringResource(R.string.card_estimate)) {
    Button(onClick = onMeasure) { Text(text = stringResource(R.string.calculate_bp)) }
    when (measurement) {
        null -> Unit
        BloodPressureMeasurement.NotCalibrated -> Text(text = stringResource(R.string.save_calibration_first))
        BloodPressureMeasurement.NoHeartRate ->
            Text(text = stringResource(R.string.no_hr_wear_watch,WristSignalsReader.HEART_RATE_LOOKBACK.toMinutes()))
        is BloodPressureMeasurement.NotAtRest -> Text(
            text = stringResource(R.string.rest_to_measure, WristSignalsReader.REST_WINDOW.toMinutes(), measurement.stepsPerMinute.roundToInt()),
            color = GaugeColors.POOR,
        )
        is BloodPressureMeasurement.Estimated -> {
            val reading = measurement.reading
            Text(
                text = stringResource(R.string.bp_big, reading.systolic.roundToInt(), reading.diastolic.roundToInt()),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = categoryColor(reading.category),
            )
            Text(text = stringResource(R.string.mmhg_with_category, stringResource(reading.category.label)), color = categoryColor(reading.category))
            Text(
                text = stringResource(
                    R.string.estimate_pulse_line, measurement.signals.heartRate.roundToInt(),
                    formatTime(measurement.signals.heartRateAt), formatTime(reading.measuredAt),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.estimate_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CuffCard(status: CuffStatus) = SectionCard(stringResource(R.string.card_cuff)) {
    Text(text = stringResource(R.string.cuff_battery, status.batteryPercent, status.serial))
    Text(text = stringResource(R.string.cuff_last_connected, formatTime(status.seenAt)), style = MaterialTheme.typography.bodySmall)
    Text(
        text = stringResource(R.string.cuff_hint, BloodPressureMonitor.CUFF_SIGNAL_MAX_GAP.toMinutes()),
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
) = SectionCard(stringResource(R.string.card_last_days, HISTORY_WINDOW.toDays())) {
    BloodPressureDayChart(
        readings, start, end,
        selectedActions = { reading -> TextButton(onClick = { onDelete(reading) }) { Text(text = stringResource(R.string.delete)) } },
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

private const val PRESSURE_DIGITS = 3
private const val CARD_SPACING = 12
private const val FIELD_SPACING = 8
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")
