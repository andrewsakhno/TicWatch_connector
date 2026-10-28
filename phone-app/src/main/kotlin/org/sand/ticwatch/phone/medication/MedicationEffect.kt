package org.sand.ticwatch.phone.medication

import org.sand.ticwatch.phone.health.BloodPressureLog
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.medianOrNull
import java.time.Duration
import java.time.Instant

/** Heart rate (watch median) and blood pressure (nearest reading) around [offset] after an intake; null = no data. */
data class EffectCheckpoint(val offset: Duration, val heartRate: Double?, val systolic: Double?, val diastolic: Double?)

/** Change of a checkpoint against the pre-intake baseline (negative = lowered). */
data class EffectDelta(val offset: Duration, val heartRate: Double?, val systolic: Double?, val diastolic: Double?)

/**
 * Wrist response to an intake: the [baseline] just before it, [checkpoints] at [MedicationEffectAnalyzer.CHECKPOINTS]
 * after it and the [onset] (first heart-rate drop of [MedicationEffectAnalyzer.ONSET_HEART_RATE_DROP]), null if none yet.
 */
data class MedicationEffect(
    val intake: MedicationIntake,
    val baseline: EffectCheckpoint,
    val checkpoints: List<EffectCheckpoint>,
    val onset: Duration?,
) {
    val deltas: List<EffectDelta>
        get() = checkpoints.map { point ->
            EffectDelta(
                point.offset,
                difference(point.heartRate, baseline.heartRate),
                difference(point.systolic, baseline.systolic),
                difference(point.diastolic, baseline.diastolic),
            )
        }

    private fun difference(value: Double?, base: Double?): Double? = if (value != null && base != null) value - base else null
}

/** Pure calculation of a [MedicationEffect] from the watch heart rates and blood pressure readings around an intake. */
class MedicationEffectAnalyzer {

    fun analyze(intake: MedicationIntake, heartRates: List<SensorSample>, readings: List<BloodPressureReading>): MedicationEffect {
        val at = intake.takenAt
        val baselineHeartRate = heartRates.medianBetween(at.minus(BASELINE_HEART_RATE_SPAN), at)
        val baselineReading = readings
            .filter { !it.measuredAt.isBefore(at.minus(BASELINE_READING_LOOKBACK)) && !it.measuredAt.isAfter(at.plus(BASELINE_READING_GRACE)) }
            .maxByOrNull { it.measuredAt }
        return MedicationEffect(
            intake = intake,
            baseline = EffectCheckpoint(Duration.ZERO, baselineHeartRate, baselineReading?.systolic, baselineReading?.diastolic),
            checkpoints = CHECKPOINTS.map { offset -> checkpoint(at, offset, heartRates, readings) },
            onset = baselineHeartRate?.let { onset(at, it, heartRates) },
        )
    }

    private fun checkpoint(at: Instant, offset: Duration, heartRates: List<SensorSample>, readings: List<BloodPressureReading>): EffectCheckpoint {
        val target = at.plus(offset)
        val reading = readings
            .filter { Duration.between(target, it.measuredAt).abs() <= CHECKPOINT_READING_TOLERANCE }
            .minByOrNull { Duration.between(target, it.measuredAt).abs() }
        return EffectCheckpoint(
            offset,
            heartRates.medianBetween(target.minus(CHECKPOINT_HEART_RATE_HALF_SPAN), target.plus(CHECKPOINT_HEART_RATE_HALF_SPAN)),
            reading?.systolic,
            reading?.diastolic,
        )
    }

    /** End of the first [ONSET_BUCKET] whose median heart rate is [ONSET_HEART_RATE_DROP] below [baseline]. */
    private fun onset(at: Instant, baseline: Double, heartRates: List<SensorSample>): Duration? =
        generateSequence(Duration.ZERO) { it.plus(ONSET_BUCKET) }
            .takeWhile { it < ONSET_HORIZON }
            .firstOrNull { start ->
                val median = heartRates.medianBetween(at.plus(start), at.plus(start).plus(ONSET_BUCKET))
                median != null && median <= baseline - ONSET_HEART_RATE_DROP
            }
            ?.plus(ONSET_BUCKET)

    private fun List<SensorSample>.medianBetween(from: Instant, to: Instant): Double? =
        filter { !it.recordedAt.isBefore(from) && it.recordedAt.isBefore(to) }.map { it.value }.medianOrNull()

    companion object {
        val CHECKPOINTS: List<Duration> = listOf(Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(4))
        const val ONSET_HEART_RATE_DROP = 5.0
        val ONSET_HORIZON: Duration = Duration.ofHours(4)
        private val ONSET_BUCKET = Duration.ofMinutes(15)
        private val BASELINE_HEART_RATE_SPAN = Duration.ofMinutes(30)
        private val BASELINE_READING_LOOKBACK = Duration.ofHours(2)
        private val BASELINE_READING_GRACE = Duration.ofMinutes(10)
        private val CHECKPOINT_HEART_RATE_HALF_SPAN = Duration.ofMinutes(15)
        private val CHECKPOINT_READING_TOLERANCE = Duration.ofMinutes(30)

        /** History needed around an intake: from the baseline lookback to the last checkpoint's tolerance. */
        val LOOKBACK: Duration = BASELINE_READING_LOOKBACK
        val LOOKAHEAD: Duration = CHECKPOINTS.last().plus(CHECKPOINT_READING_TOLERANCE)
    }
}

/** Reads the history around intakes and runs [MedicationEffectAnalyzer]. */
class MedicationEffectReader(
    private val samples: SampleWindowReader,
    private val bloodPressure: BloodPressureLog,
    private val analyzer: MedicationEffectAnalyzer,
) {
    suspend fun effect(intake: MedicationIntake): MedicationEffect {
        val from = intake.takenAt.minus(MedicationEffectAnalyzer.LOOKBACK)
        val to = intake.takenAt.plus(MedicationEffectAnalyzer.LOOKAHEAD)
        val heartRates = samples.samples(setOf(SensorType.HEART_RATE), from, to, emptyList()).filter { it.source == SampleSource.WATCH }
        return analyzer.analyze(intake, heartRates, bloodPressure.readings(from, to))
    }
}
