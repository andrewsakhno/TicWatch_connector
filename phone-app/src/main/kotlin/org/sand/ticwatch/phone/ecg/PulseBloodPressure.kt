package org.sand.ticwatch.phone.ecg

import android.util.Log
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.HealthRecordWriter
import org.sand.ticwatch.phone.health.PulseWaveBloodPressure
import org.sand.ticwatch.phone.health.RecentPulseWaveBloodPressure
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SampleSource
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/** What the blood pressure model reads from one strip. */
data class PulseFeatures(val heartRate: Double, val riseTimeMs: Double, val halfWidthMs: Double, val perfusionIndex: Double) {
    fun vector(): DoubleArray = doubleArrayOf(heartRate, riseTimeMs, halfWidthMs, perfusionIndex)

    /** False for a strip under severe (e.g. cold) peripheral vasoconstriction, whose shape doesn't reflect central pressure. */
    val isWellPerfused: Boolean get() = perfusionIndex >= MIN_CALIBRATION_PERFUSION_PERCENT

    companion object {
        /** Perfusion index (%) below which a strip neither trains nor feeds the personal pulse-wave model. */
        const val MIN_CALIBRATION_PERFUSION_PERCENT = 0.22

        fun of(analysis: EcgAnalysis): PulseFeatures? {
            val pulse = analysis.pulse ?: return null
            return PulseFeatures(analysis.meanHeartRate ?: return null, pulse.riseTimeMs, pulse.halfWidthMs ?: return null, pulse.perfusionIndexPercent)
        }
    }
}

/** A cuff reading with the pulse wave recorded around it: one training example of the personal model. */
@Entity(tableName = "pulse_bp_pair", indices = [Index(value = ["cuff_at"], unique = true)])
data class PulseBloodPressurePairEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "cuff_at") val cuffAtMs: Long,
    @ColumnInfo(name = "systolic") val systolic: Double,
    @ColumnInfo(name = "diastolic") val diastolic: Double,
    @ColumnInfo(name = "recorded_at") val recordedAtMs: Long,
    @ColumnInfo(name = "heart_rate") val heartRate: Double,
    @ColumnInfo(name = "rise_time_ms") val riseTimeMs: Double,
    @ColumnInfo(name = "half_width_ms") val halfWidthMs: Double,
    @ColumnInfo(name = "perfusion_index") val perfusionIndex: Double,
) {
    val features: PulseFeatures get() = PulseFeatures(heartRate, riseTimeMs, halfWidthMs, perfusionIndex)
}

@Dao
interface PulseBloodPressurePairDao {
    /** A cuff reading pairs once: a later strip near it doesn't replace the first. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pair: PulseBloodPressurePairEntity): Long

    @Query("SELECT * FROM pulse_bp_pair ORDER BY cuff_at DESC LIMIT :limit")
    suspend fun newest(limit: Int): List<PulseBloodPressurePairEntity>
}

/** The cuff readings available for pairing. */
fun interface CuffReadingSource {
    suspend fun cuffReadings(from: Instant, to: Instant): List<BloodPressureReading>

    companion object {
        /** Keeps only cuff readings of a reading [query] that returns every source. */
        fun of(query: suspend (Instant, Instant) -> List<BloodPressureReading>) =
            CuffReadingSource { from, to -> query(from, to).filter { it.source == SampleSource.BLOOD_PRESSURE_CUFF } }
    }
}

/** Runs after a strip is stored. */
fun interface EcgRecordingStoredListener {
    suspend fun onStored(recording: EcgRecording)
}

/**
 * Pairs a stored strip with the cuff reading taken up to [PAIRING_WINDOW] before it (or [AFTER_WINDOW] after its
 * start): the phone records a strip automatically after each cuff measurement, and a manual recording next to one
 * counts too.
 */
class PulseBloodPressureCalibrator(
    private val pairs: PulseBloodPressurePairDao,
    private val cuff: CuffReadingSource,
    private val analyzer: CardiogramAnalyzer,
) : EcgRecordingStoredListener {

    override suspend fun onStored(recording: EcgRecording) {
        val features = PulseFeatures.of(analyzer.analyze(recording.beats, recording.wave))?.takeIf { it.isWellPerfused } ?: return
        val reading = cuff.cuffReadings(recording.startedAt.minus(PAIRING_WINDOW), recording.startedAt.plus(AFTER_WINDOW))
            .minByOrNull { abs(Duration.between(it.measuredAt, recording.startedAt).toMillis()) } ?: return
        val id = pairs.insert(
            PulseBloodPressurePairEntity(
                cuffAtMs = reading.measuredAt.toEpochMilli(),
                systolic = reading.systolic,
                diastolic = reading.diastolic,
                recordedAtMs = recording.startedAt.toEpochMilli(),
                heartRate = features.heartRate,
                riseTimeMs = features.riseTimeMs,
                halfWidthMs = features.halfWidthMs,
                perfusionIndex = features.perfusionIndex,
            ),
        )
        if (id > 0) Log.i(TAG, "Paired cuff ${reading.systolic}/${reading.diastolic} at ${reading.measuredAt} with the strip at ${recording.startedAt}")
    }

    private companion object {
        const val TAG = "PulseBpCalibrator"
        val PAIRING_WINDOW: Duration = Duration.ofMinutes(10)
        val AFTER_WINDOW: Duration = Duration.ofMinutes(2)
    }
}

/** The pulse-wave blood pressure of one strip, or how far the personal model still is from usable. */
sealed interface PulseBloodPressure {
    data class Estimate(val systolic: Double, val diastolic: Double, val pairs: Int, val systolicErrorMmHg: Double) : PulseBloodPressure

    data class NeedsCalibration(val pairs: Int, val required: Int) : PulseBloodPressure
}

/** Estimates blood pressure from a strip's pulse features with the personal model. */
fun interface PulseBloodPressureEstimator {
    suspend fun estimate(features: PulseFeatures): PulseBloodPressure
}

/**
 * Ridge regression of the cuff readings on the pulse features (standardized; [RIDGE_LAMBDA] keeps a handful of pairs
 * from overfitting), fitted separately for systolic and diastolic on the newest [MAX_PAIRS] pairs. The error shown is
 * the leave-one-out RMS of systolic. Predictions are kept within [EXTRAPOLATION_MMHG] of the trained range.
 */
class RidgePulseBloodPressureEstimator(private val pairs: PulseBloodPressurePairDao) : PulseBloodPressureEstimator {

    override suspend fun estimate(features: PulseFeatures): PulseBloodPressure {
        val training = pairs.newest(MAX_PAIRS)
        if (training.size < MIN_PAIRS) return PulseBloodPressure.NeedsCalibration(training.size, MIN_PAIRS)
        val inputs = training.map { it.features.vector() }
        val systolic = training.map { it.systolic }
        val diastolic = training.map { it.diastolic }
        val x = features.vector()
        return PulseBloodPressure.Estimate(
            systolic = clampToRange(Ridge.fit(inputs, systolic).predict(x), systolic),
            diastolic = clampToRange(Ridge.fit(inputs, diastolic).predict(x), diastolic),
            pairs = training.size,
            systolicErrorMmHg = leaveOneOutRms(inputs, systolic),
        )
    }

    private fun clampToRange(value: Double, observed: List<Double>): Double =
        value.coerceIn(observed.min() - EXTRAPOLATION_MMHG, observed.max() + EXTRAPOLATION_MMHG)

    private fun leaveOneOutRms(inputs: List<DoubleArray>, outputs: List<Double>): Double {
        val squared = inputs.indices.map { left ->
            val model = Ridge.fit(inputs.filterIndexed { i, _ -> i != left }, outputs.filterIndexed { i, _ -> i != left })
            val error = model.predict(inputs[left]) - outputs[left]
            error * error
        }
        return sqrt(squared.average())
    }

    /** Linear model on standardized inputs with an unpenalized intercept. */
    private class Ridge(private val means: DoubleArray, private val scales: DoubleArray, private val weights: DoubleArray, private val intercept: Double) {

        fun predict(x: DoubleArray): Double = intercept + weights.indices.sumOf { weights[it] * standardize(x[it], it) }

        private fun standardize(value: Double, index: Int): Double = if (scales[index] == 0.0) 0.0 else (value - means[index]) / scales[index]

        companion object {
            fun fit(inputs: List<DoubleArray>, outputs: List<Double>): Ridge {
                val size = inputs.first().size
                val means = DoubleArray(size) { j -> inputs.map { it[j] }.average() }
                val scales = DoubleArray(size) { j -> sqrt(inputs.map { (it[j] - means[j]).let { d -> d * d } }.average()) }
                val z = inputs.map { row -> DoubleArray(size) { j -> if (scales[j] == 0.0) 0.0 else (row[j] - means[j]) / scales[j] } }
                val mean = outputs.average()
                val gram = Array(size) { a -> DoubleArray(size) { b -> z.sumOf { it[a] * it[b] } + if (a == b) RIDGE_LAMBDA else 0.0 } }
                val target = DoubleArray(size) { a -> z.indices.sumOf { z[it][a] * (outputs[it] - mean) } }
                return Ridge(means, scales, solve(gram, target), mean)
            }

            /** Gaussian elimination with partial pivoting; the ridge term keeps [matrix] well-conditioned. */
            private fun solve(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray {
                val n = vector.size
                val a = Array(n) { matrix[it].copyOf() }
                val b = vector.copyOf()
                for (column in 0 until n) {
                    val pivot = (column until n).maxBy { abs(a[it][column]) }
                    a[column] = a[pivot].also { a[pivot] = a[column] }
                    b[column] = b[pivot].also { b[pivot] = b[column] }
                    for (row in column + 1 until n) {
                        val factor = a[row][column] / a[column][column]
                        for (k in column until n) a[row][k] -= factor * a[column][k]
                        b[row] -= factor * b[column]
                    }
                }
                val solution = DoubleArray(n)
                for (row in n - 1 downTo 0) {
                    solution[row] = (b[row] - (row + 1 until n).sumOf { a[row][it] * solution[it] }) / a[row][row]
                }
                return solution
            }
        }
    }

    companion object {
        /** Cuff + strip pairs needed before the model estimates at all. */
        const val MIN_PAIRS = 4
        const val MAX_PAIRS = 60

        /** Strong enough that a few pairs can't fit rise-time noise. */
        private const val RIDGE_LAMBDA = 2.0
        private const val EXTRAPOLATION_MMHG = 15.0
    }
}

/**
 * The personal pulse-wave estimate of the newest strip started within [STRIP_MAX_AGE] before a moment; null without
 * such a strip, a usable pulse wave or a trained model.
 */
class RecentStripPulseBloodPressure(
    private val log: EcgRecordingLog,
    private val analyzer: CardiogramAnalyzer,
    private val estimator: PulseBloodPressureEstimator,
) : RecentPulseWaveBloodPressure {

    override suspend fun near(at: Instant): PulseWaveBloodPressure? {
        val strip = log.startedBetween(at.minus(STRIP_MAX_AGE), at.plusMillis(1)).firstOrNull() ?: return null
        val features = PulseFeatures.of(analyzer.analyze(strip.beats, strip.wave))?.takeIf { it.isWellPerfused } ?: return null
        val estimate = estimator.estimate(features) as? PulseBloodPressure.Estimate ?: return null
        return PulseWaveBloodPressure(estimate.systolic, estimate.diastolic, estimate.systolicErrorMmHg)
    }

    companion object {
        val STRIP_MAX_AGE: Duration = Duration.ofMinutes(15)
    }
}

/**
 * Estimates blood pressure from each stored [EcgRecordingKind.NIGHT_PULSE] strip with the personal pulse-wave
 * [estimator] and stores it through [writer] as PULSE_WAVE_DIASTOLIC + PULSE_WAVE_SYSTOLIC (history only, so it
 * reaches the cloud with the other samples). Until the model has enough cuff pairs nothing is stored; the strip itself
 * stays, so its features can be re-read once the model can use them.
 */
class NightPulseBloodPressureRecorder(
    private val analyzer: CardiogramAnalyzer,
    private val estimator: PulseBloodPressureEstimator,
    private val writer: HealthRecordWriter,
) : EcgRecordingStoredListener {

    override suspend fun onStored(recording: EcgRecording) {
        if (recording.kind != EcgRecordingKind.NIGHT_PULSE) return
        val features = PulseFeatures.of(analyzer.analyze(recording.beats, recording.wave)) ?: run {
            Log.i(TAG, "Night strip at ${recording.startedAt}: no usable pulse wave")
            return
        }
        when (val estimate = estimator.estimate(features)) {
            is PulseBloodPressure.NeedsCalibration -> Log.i(TAG, "Night strip kept, model has ${estimate.pairs}/${estimate.required} cuff pairs")
            is PulseBloodPressure.Estimate -> writer.write(
                listOf(
                    SensorSample(SensorType.PULSE_WAVE_DIASTOLIC, estimate.diastolic, recording.startedAt),
                    SensorSample(SensorType.PULSE_WAVE_SYSTOLIC, estimate.systolic, recording.startedAt),
                ),
            ).onFailure { Log.w(TAG, "Night pulse-wave estimate not stored", it) }
        }
    }

    private companion object {
        const val TAG = "NightPulseBp"
    }
}
