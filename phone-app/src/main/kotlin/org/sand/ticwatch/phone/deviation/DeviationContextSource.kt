package org.sand.ticwatch.phone.deviation

import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.WorkoutActivity
import org.sand.ticwatch.phone.health.WorkoutActivitySource
import org.sand.ticwatch.phone.medication.MedicationIntakeLog
import java.time.Duration
import java.time.Instant

interface DeviationContextSource {
    /** What surrounded an episode that began at [start]. */
    suspend fun contextAt(start: Instant): DeviationContext
}

/** Reads the context from the local history and the medication log; each part looks back only as far as it stays telling. */
class HistoryDeviationContextSource(
    private val reader: SampleWindowReader,
    private val activity: WorkoutActivitySource,
    private val medication: MedicationIntakeLog,
) : DeviationContextSource {

    override suspend fun contextAt(start: Instant): DeviationContext = DeviationContext(
        stepsBefore = stepsBefore(start),
        rmssdBefore = read(SensorType.HRV_RMSSD, start.minus(RMSSD_BACK), start).map { it.value }.takeIf { it.isNotEmpty() }?.average(),
        sleepScore = read(SensorType.SLEEP_SCORE, start.minus(SLEEP_SCORE_BACK), start).lastOrNull()?.value,
        fatiguePercent = read(SensorType.FATIGUE_INDEX, start.minus(FATIGUE_BACK), start).lastOrNull()?.value,
        lastMedicationAt = medication.intakes(start.minus(MEDICATION_BACK), start).lastOrNull()?.takenAt,
    )

    /** Workout steps only: the counted ones of the stored workouts, as every rest calculation sees activity. */
    private suspend fun stepsBefore(start: Instant): Double {
        val from = start.minus(WorkoutActivity.RECOVERY)
        return activity.activity(from, start).stepsIn(from, start)
    }

    private suspend fun read(type: SensorType, from: Instant, to: Instant): List<SensorSample> =
        reader.samples(setOf(type), from, to, emptyList())

    private companion object {
        val RMSSD_BACK: Duration = Duration.ofHours(1)
        val SLEEP_SCORE_BACK: Duration = Duration.ofHours(24)
        val FATIGUE_BACK: Duration = Duration.ofHours(1)
        val MEDICATION_BACK: Duration = Duration.ofHours(48)
    }
}
