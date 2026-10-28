package org.sand.ticwatch.phone.report

import org.sand.ticwatch.phone.health.BloodPressureLog
import org.sand.ticwatch.phone.health.PatientNameStore
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.UserProfileStore
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.medication.MedicationDose
import org.sand.ticwatch.phone.medication.MedicationEffectReader
import org.sand.ticwatch.phone.medication.MedicationIntakeLog
import org.sand.ticwatch.phone.medication.MedicationRegimen
import java.time.Clock
import java.time.Duration
import java.time.Year
import java.time.ZoneId

/** Report calculators, grouped so the generator's constructor stays readable. */
data class CardioReportCalculators(
    val pressure: AmbulatoryPressureCalculator,
    val heartRate: HeartRateReportCalculator,
    val accuracy: CalibrationAccuracyCalculator,
)

/** Collects the history of the last [days] days into a [CardioReport] (ABPM-style summary for a cardiologist). */
class CardioReportGenerator(
    private val samples: SampleWindowReader,
    private val queries: SensorHistoryQueries,
    private val bloodPressure: BloodPressureLog,
    private val intakes: MedicationIntakeLog,
    private val effects: MedicationEffectReader,
    private val profile: UserProfileStore,
    private val patientNames: PatientNameStore,
    private val regimen: MedicationRegimen,
    private val calculators: CardioReportCalculators,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    suspend fun generate(days: Int): CardioReport {
        require(days in DAYS) { "Report period must be $DAYS days" }
        val to = clock.instant()
        val from = to.minus(Duration.ofDays(days.toLong()))
        val readings = bloodPressure.readings(from, to)
        val heartRates = samples.samples(setOf(SensorType.HEART_RATE), from.minus(NIGHT_LOOKBACK), to, emptyList())
            .filter { it.source == SampleSource.WATCH }
        val workouts = samples.samples(WORKOUT_TYPES, from.minus(WORKOUT_LOOKBACK), to, emptyList())
            .mapNotNull { sample -> sample.endedAt?.let { TimeRange(sample.recordedAt, it) } }
        val sleepSessions = queries.sleepSessions(from.minus(NIGHT_LOOKBACK), to, null, MAX_SLEEP_SESSIONS)
        val intakeEffects = intakes.intakes(from, to).map { effects.effect(it) }
        val birthYear = profile.birthYear()
        return CardioReport(
            generatedAt = to,
            from = from,
            to = to,
            zone = zone,
            patient = PatientInfo(
                name = patientNames.patientName(),
                ageYears = birthYear?.let { Year.now(clock).value - it },
                sex = profile.sex(),
                heightCm = profile.heightCm(),
                weightKg = profile.weightKg(),
            ),
            therapy = MedicationDose.entries.flatMap(regimen::medications),
            pressure = calculators.pressure.summarize(readings),
            heartRate = calculators.heartRate.summarize(heartRates, from, to, sleepSessions, workouts, intakeEffects),
            accuracy = calculators.accuracy.compare(readings),
        )
    }

    companion object {
        val DAYS = 1..7
        private val WORKOUT_TYPES = setOf(SensorType.EXERCISE, SensorType.AUTO_EXERCISE)
        private val WORKOUT_LOOKBACK = Duration.ofHours(6)
        private val NIGHT_LOOKBACK = Duration.ofHours(12)
        private const val MAX_SLEEP_SESSIONS = 64
    }
}

/** A report rendered in both export formats; [name] doubles as print job name and share subject. */
data class RenderedCardioReport(val name: String, val html: String, val markdown: String)

/** Generates and renders a report for the export menu. */
class CardioReportComposer(
    private val generator: CardioReportGenerator,
    private val formatter: CardioReportFormatter,
    private val html: CardioReportRenderer,
    private val markdown: CardioReportRenderer,
) {
    suspend fun compose(days: Int): RenderedCardioReport {
        val report = generator.generate(days)
        val document = formatter.format(report)
        return RenderedCardioReport(
            name = "cardio-report-${reportFileStamp(report.generatedAt, report.zone)}",
            html = html.render(document),
            markdown = markdown.render(document),
        )
    }
}
