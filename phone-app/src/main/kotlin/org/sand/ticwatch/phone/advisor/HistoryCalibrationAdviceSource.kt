package org.sand.ticwatch.phone.advisor

import java.time.Duration
import java.time.Instant
import org.sand.ticwatch.phone.ecg.PulseBloodPressure
import org.sand.ticwatch.phone.ecg.PulseBloodPressureEstimator
import org.sand.ticwatch.phone.ecg.PulseBloodPressurePairDao
import org.sand.ticwatch.phone.ecg.RidgePulseBloodPressureEstimator
import org.sand.ticwatch.phone.health.BloodPressureCalibrationStore
import org.sand.ticwatch.phone.health.BloodPressureLog
import org.sand.ticwatch.phone.health.CalibrationRestCheck
import org.sand.ticwatch.phone.health.SystolicOffsetStore
import org.sand.ticwatch.phone.health.WristSignalsReader
import org.sand.ticwatch.phone.medication.MedicationProtection
import org.sand.ticwatch.phone.report.CalibrationAccuracyCalculator
import org.sand.ticwatch.phone.report.CuffBiasSystolicOffset

/** The readiness context the calibration advice is judged against, already computed by the readiness source. */
data class CalibrationContext(
    val at: Instant,
    val autonomic: AutonomicBalance?,
    val medication: MedicationProtection,
    val forecast: List<ReadinessForecastHour>,
)

fun interface CalibrationAdviceSource {
    suspend fun advice(context: CalibrationContext): CalibrationAdvice
}

/** Reads the stored calibration, offset, pulse-wave pairs and live wrist signals, then runs [CalibrationAdvisor]. */
class HistoryCalibrationAdviceSource(
    private val calibrations: BloodPressureCalibrationStore,
    private val offsets: SystolicOffsetStore,
    private val pairs: PulseBloodPressurePairDao,
    private val pulseEstimator: PulseBloodPressureEstimator,
    private val wrist: WristSignalsReader,
    private val restCheck: CalibrationRestCheck,
    private val log: BloodPressureLog,
    private val accuracy: CalibrationAccuracyCalculator,
    private val advisor: CalibrationAdvisor,
) : CalibrationAdviceSource {

    override suspend fun advice(context: CalibrationContext): CalibrationAdvice {
        val now = context.at
        val signals = wrist.current(now)
        val inputs = CalibrationAdvisorInputs(
            at = now,
            calibration = calibrations.bloodPressureCalibration(),
            restingHeartRateBpm = wrist.restingHeartRate(now),
            stepsPerMinute = wrist.recentStepRate(now),
            wrist = signals,
            restViolation = signals?.let { restCheck.violation(it.heartRate, it.heartRateAt) },
            pulseWave = pulseWave(),
            offset = offset(now),
            autonomic = context.autonomic,
            medication = context.medication,
            forecast = context.forecast,
        )
        return advisor.advise(inputs)
    }

    private suspend fun pulseWave(): PulseWaveTraining {
        val stored = pairs.newest(RidgePulseBloodPressureEstimator.MAX_PAIRS)
        val required = RidgePulseBloodPressureEstimator.MIN_PAIRS
        val error = stored.firstOrNull()?.takeIf { stored.size >= required }
            ?.let { (pulseEstimator.estimate(it.features) as? PulseBloodPressure.Estimate)?.systolicErrorMmHg }
        return PulseWaveTraining(stored.map { it.systolic }, required, error)
    }

    private suspend fun offset(now: Instant): OffsetTraining {
        val end = now.plusMillis(1)
        val lookbackStart = now.minus(LOOKBACK)
        val current = offsets.systolicOffset()
        val recent = accuracy.compare(log.readings(lookbackStart, end))
        val sinceOffset = log.readings(maxOf(current?.since ?: lookbackStart, lookbackStart), end)
        return OffsetTraining(
            comparisonsBeforeCuff = accuracy.comparisonsBeforeCuff(sinceOffset).size,
            window = CuffBiasSystolicOffset.COMPARISON_WINDOW,
            offsetMmHg = current?.mmHg ?: 0.0,
            meanAbsoluteSystolicError = recent.meanAbsoluteSystolicDelta,
            meanAbsoluteDiastolicError = recent.meanAbsoluteDiastolicDelta,
        )
    }

    private companion object {
        val LOOKBACK: Duration = Duration.ofDays(30)
    }
}
