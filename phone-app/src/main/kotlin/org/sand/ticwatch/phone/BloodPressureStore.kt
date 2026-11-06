package org.sand.ticwatch.phone

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import org.sand.ticwatch.phone.bpm.CuffStatus
import org.sand.ticwatch.phone.bpm.CuffStatusSource
import org.sand.ticwatch.phone.datalayer.WatchSyncOutcome
import org.sand.ticwatch.phone.datalayer.WatchSyncRequester
import org.sand.ticwatch.phone.health.BloodPressureCalibration
import org.sand.ticwatch.phone.health.BloodPressureMeasurement
import org.sand.ticwatch.phone.health.BloodPressureMonitor
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.CalibrationResult
import org.sand.ticwatch.phone.medication.MedicationDose
import org.sand.ticwatch.phone.medication.MedicationEffect
import org.sand.ticwatch.phone.medication.MedicationIntake
import org.sand.ticwatch.phone.medication.MedicationProtection
import org.sand.ticwatch.phone.text.JoinedText
import org.sand.ticwatch.phone.text.UiText
import org.sand.ticwatch.phone.text.uiText
import org.sand.ticwatch.phone.ui.UiStateStore

/** The Blood Pressure tab charts this much history. */
internal val HISTORY_WINDOW: Duration = Duration.ofDays(3)

/** How long an estimate waits for the watch to hand over its buffered samples before using the stored history. */
private val WATCH_SYNC_TIMEOUT = 45.seconds
private const val LINE_BREAK = "\n"

/** Everything the Blood Pressure tab shows. */
data class BloodPressureUiState(
    val calibration: BloodPressureCalibration? = null,
    val measurement: BloodPressureMeasurement? = null,
    val recent: List<BloodPressureReading> = emptyList(),
    val historyEnd: Instant = Instant.now(),
    val message: UiText? = null,
    val cuff: CuffStatus? = null,
    val intakes: List<MedicationIntake> = emptyList(),
    val latestEffect: MedicationEffect? = null,
    val protection: MedicationProtection? = null,
)

/** The persisted part of [BloodPressureUiState], read in one pass. */
private data class BloodPressureSnapshot(
    val calibration: BloodPressureCalibration?,
    val recent: List<BloodPressureReading>,
    val historyEnd: Instant,
    val cuff: CuffStatus?,
    val intakes: List<MedicationIntake>,
    val latestEffect: MedicationEffect?,
    val protection: MedicationProtection?,
)

/**
 * State holder of the Blood Pressure tab. Re-reads the history whenever [changes] emits - a reading saved by the cuff
 * or the watch shows up without a manual refresh - and after each of its own writes.
 */
class BloodPressureStore(
    private val monitor: BloodPressureMonitor,
    /** Pulls the watch's buffered samples first, so the estimate doesn't wait for the next sync window. */
    private val watchSync: WatchSyncRequester,
    private val cuffStatus: CuffStatusSource,
    private val medication: MedicationTracking,
    changes: Flow<*>,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<BloodPressureUiState>(BloodPressureUiState(), scope, ioDispatcher) {
    private var loadJob: Job? = null

    init {
        observe(changes) { reload() }
    }

    fun calibrate(systolic: Double, diastolic: Double) = runAction({ monitor.calibrate(systolic, diastolic) }) {
        when (it) {
            is CalibrationResult.Saved -> uiText(R.string.calibration_saved, it.calibration.heartRate.roundToInt())
            CalibrationResult.Implausible -> uiText(R.string.calibration_implausible)
            CalibrationResult.NoHeartRate -> uiText(R.string.calibration_no_hr)
            CalibrationResult.Outdated -> uiText(R.string.calibration_outdated)
            is CalibrationResult.NotAtRest -> uiText(R.string.calibration_not_at_rest, it.violation.heartRate.roundToInt())
        }
    }

    fun measure() {
        setState { it.copy(measurement = null, message = uiText(R.string.bp_syncing)) }
        launchIo({ measureAndSave() }) { result ->
            result.onSuccess { (sync, measured, saveFailed) ->
                val notes = listOfNotNull(syncMessage(sync), uiText(R.string.bp_save_failed).takeIf { saveFailed })
                setState { it.copy(measurement = measured, message = notes.takeIf { it.isNotEmpty() }?.let { JoinedText(it, LINE_BREAK) }) }
            }.onFailure { error -> setState { it.copy(message = uiText(R.string.estimate_failed, error.message.orEmpty())) } }
        }
    }

    /** Estimates and immediately stores an [BloodPressureMeasurement.Estimated] result; the log's listener fires [changes], which reloads the chart. */
    private suspend fun measureAndSave(): Triple<WatchSyncOutcome, BloodPressureMeasurement, Boolean> {
        val sync = watchSync.syncNow(WATCH_SYNC_TIMEOUT)
        val measured = monitor.measure()
        val saveFailed = measured is BloodPressureMeasurement.Estimated && monitor.log.save(measured.reading).isFailure
        return Triple(sync, measured, saveFailed)
    }

    private fun syncMessage(outcome: WatchSyncOutcome): UiText? = when (outcome) {
        WatchSyncOutcome.DELIVERED -> null
        WatchSyncOutcome.PARTIAL -> uiText(R.string.sync_partial)
        WatchSyncOutcome.UNREACHABLE -> uiText(R.string.sync_unreachable)
    }

    fun delete(reading: BloodPressureReading) {
        launchIo({ monitor.log.delete(reading) }) { result ->
            result.onFailure { error -> setState { it.copy(message = uiText(R.string.delete_failed, error.message.orEmpty())) } }
        }
    }

    fun logIntake() = runAction({ medication.intakes.record(MedicationDose.INTAKE) }) { result ->
        result.fold(
            onSuccess = { uiText(R.string.intake_logged, formatTime(it.takenAt)) },
            onFailure = { uiText(R.string.intake_failed, it.message.orEmpty()) },
        )
    }

    /** Runs [action] off the main thread, shows its [describe]d outcome and re-reads the tab. */
    private fun <T> runAction(action: suspend () -> T, describe: (T) -> UiText) {
        launchIo(action) { result ->
            val message = result.fold(onSuccess = describe, onFailure = { uiText(R.string.action_failed, it.message.orEmpty()) })
            setState { it.copy(message = message) }
            reload()
        }
    }

    private fun reload() {
        loadJob?.cancel()
        loadJob = launchLocalFirstRead(::read) { result ->
            result.onSuccess { read ->
                setState {
                    it.copy(
                        calibration = read.calibration, recent = read.recent, historyEnd = read.historyEnd,
                        cuff = read.cuff, intakes = read.intakes, latestEffect = read.latestEffect,
                        protection = read.protection,
                    )
                }
            }
        }
    }

    private suspend fun read(): BloodPressureSnapshot {
        val end = Instant.now()
        val intakes = medication.intakes.intakes(end.minus(HISTORY_WINDOW), end.plusMillis(1))
        return BloodPressureSnapshot(
            calibration = monitor.calibration(),
            recent = monitor.since(end.minus(HISTORY_WINDOW)),
            historyEnd = end,
            cuff = cuffStatus.cuffStatus(),
            intakes = intakes,
            latestEffect = intakes.lastOrNull()?.let { medication.effects.effect(it) },
            protection = medication.protection.current(),
        )
    }
}
