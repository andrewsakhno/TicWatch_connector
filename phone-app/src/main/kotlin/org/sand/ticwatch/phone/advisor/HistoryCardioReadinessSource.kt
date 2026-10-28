package org.sand.ticwatch.phone.advisor

import org.sand.ticwatch.phone.health.BloodPressureLog
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.medication.MedicationIntakeLog
import org.sand.ticwatch.phone.report.AmbulatoryPressureCalculator
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Readiness inputs plus the forecast context and vitals, gathered at one instant. */
data class ReadinessSnapshot(val inputs: CardioReadinessInputs, val context: ForecastContext, val vitals: Vitals)

/**
 * Reads the advisor's signals from the rollups (never raw rows), the blood pressure log, the medication log and the
 * stored NOAA forecast. Blocking stores (space weather prefs) are read here too - call off the main thread.
 */
class ReadinessSnapshotReader(
    private val queries: SensorHistoryQueries,
    private val pressureLog: BloodPressureLog,
    private val pressureCalculator: AmbulatoryPressureCalculator,
    private val intakes: MedicationIntakeLog,
    private val spaceWeather: SpaceWeatherStore,
    private val clock: Clock,
    private val zone: ZoneId,
) {

    suspend fun read(): ReadinessSnapshot {
        val now = clock.instant()
        val readings = pressureLog.readings(now.minus(DAY), now)
        val latestPressure = readings.lastOrNull()
        val normByHour = heartRateNormByHour(now)
        val heartRate = average(SensorType.HEART_RATE, now.minus(HEART_RATE_SPAN), now)
            ?: latest(SensorType.RESTING_HEART_RATE, RollupResolution.DAY, now.minus(SLEEP_LOOKBACK), now)
        val weather = spaceWeather.load()
        val slots = weather?.slots.orEmpty()
        val kp = slots.kpAt(now) ?: weather?.current?.kp
        val inputs = CardioReadinessInputs(
            at = now,
            heartRateBpm = heartRate,
            heartRateNormBpm = normByHour[now.atZone(zone).hour],
            diastolic = latestPressure?.diastolic,
            systolic = latestPressure?.systolic,
            pressureMeasuredAt = latestPressure?.measuredAt,
            rmssdMs = average(SensorType.HRV_RMSSD, now.minus(RMSSD_SPAN), now),
            dippingPercent = pressureCalculator.summarize(readings).dippingPercent,
            sleepScore = latest(SensorType.SLEEP_SCORE, RollupResolution.FIVE_MINUTES, now.minus(SLEEP_LOOKBACK), now)?.roundToInt(),
            steps24h = queries.summary(SensorType.STEPS, RollupResolution.HOUR, now.minus(DAY), now)?.sum,
            trainingLoad48h = queries.summary(SensorType.TRAINING_LOAD, RollupResolution.FIVE_MINUTES, now.minus(TWO_DAYS), now)?.sum,
            lastIntakeAt = intakes.intakes(now.minus(DAY), now).lastOrNull()?.takenAt,
            kp = kp,
        )
        val vitals = Vitals(
            pressure = latestPressure,
            heartRateBpm = heartRate,
            bodyTemperatureCelsius = latest(SensorType.BODY_TEMPERATURE, RollupResolution.FIVE_MINUTES, now.minus(DAY), now),
            spo2Percent = latest(SensorType.SPO2, RollupResolution.FIVE_MINUTES, now.minus(DAY), now),
        )
        return ReadinessSnapshot(inputs, ForecastContext(normByHour, slots), vitals)
    }

    /** Mean of the hourly HR buckets per local hour over the [NORM_DAYS] before today. */
    private suspend fun heartRateNormByHour(now: Instant): Map<Int, Double> {
        val todayStart = RollupResolution.DAY.bucketStart(now, zone)
        return queries.rollups(SensorType.HEART_RATE, RollupResolution.HOUR, todayStart.minus(NORM_SPAN), todayStart)
            .groupBy { it.bucketStart.atZone(zone).hour }
            .mapValues { (_, buckets) -> buckets.map { it.average }.average() }
    }

    private suspend fun average(type: SensorType, from: Instant, to: Instant): Double? =
        queries.summary(type, RollupResolution.FIVE_MINUTES, from, to)?.average

    private suspend fun latest(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant): Double? =
        queries.rollups(type, resolution, from, to).lastOrNull()?.average

    private companion object {
        const val NORM_DAYS = 14L
        val NORM_SPAN: Duration = Duration.ofDays(NORM_DAYS)
        val HEART_RATE_SPAN: Duration = Duration.ofMinutes(15)
        val RMSSD_SPAN: Duration = Duration.ofHours(2)
        val SLEEP_LOOKBACK: Duration = Duration.ofHours(36)
        val DAY: Duration = Duration.ofHours(24)
        val TWO_DAYS: Duration = Duration.ofHours(48)
    }
}

/** [CardioReadinessSource] composing the snapshot reader, the engine and the forecaster. */
class HistoryCardioReadinessSource(
    private val reader: ReadinessSnapshotReader,
    private val engine: CardioReadinessEngine,
    private val forecaster: ReadinessForecaster,
) : CardioReadinessSource {

    override suspend fun load(): CardioReadiness {
        val snapshot = reader.read()
        return CardioReadiness(
            assessment = engine.assess(snapshot.inputs),
            forecast = forecaster.forecast(snapshot.inputs, snapshot.context),
            vitals = snapshot.vitals,
        )
    }
}
