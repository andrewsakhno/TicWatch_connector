package org.sand.ticwatch.phone.health

import android.util.Log
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToLong
import kotlin.reflect.KClass

/** Converts one [SensorType]'s samples into Health Connect records (Strategy, OCP). */
interface HealthRecordMapper {
    val writePermissions: Set<String>
    suspend fun toRecords(samples: List<SensorSample>): List<Record>
}

/** Bounds and zone offsets of one series record built by [SeriesMapper]. */
class SeriesSpan(val start: Instant, val startOffset: ZoneOffset, val end: Instant, val endOffset: ZoneOffset)

/**
 * Series type: one record per watch push carrying every sample, instead of one record
 * per reading, which floods Health Connect with near-identical rows. Health Connect
 * rejects the whole record if any sample is out of range, so samples failing [isValid]
 * are dropped instead.
 */
class SeriesMapper<S>(
    recordType: KClass<out Record>,
    private val zoneId: ZoneId,
    private val isValid: (SensorSample) -> Boolean,
    private val toSeriesSample: (SensorSample) -> S,
    private val toRecord: (SeriesSpan, List<S>) -> Record,
) : HealthRecordMapper {
    override val writePermissions = setOf(HealthPermission.getWritePermission(recordType))

    override suspend fun toRecords(samples: List<SensorSample>): List<Record> {
        val sorted = samples.filter(isValid).sortedBy { it.recordedAt }
        if (sorted.isEmpty()) return emptyList()
        val start = sorted.first().recordedAt
        // A single-sample series would otherwise have start == end, which Health Connect rejects.
        val end = sorted.last().recordedAt.takeIf { it.isAfter(start) } ?: start.plus(MIN_SERIES_SPAN)
        val span = SeriesSpan(start, start.offsetIn(zoneId), end, end.offsetIn(zoneId))
        return listOf(toRecord(span, sorted.map(toSeriesSample)))
    }
}

/**
 * One record per sample; samples [toRecord] can't represent (null) are dropped, and so are
 * samples Health Connect's record validation rejects (the constructors throw
 * [IllegalArgumentException]) - one out-of-range reading must not sink its whole batch.
 */
class PerSampleMapper(
    recordType: KClass<out Record>,
    additionalPermissions: Set<String> = emptySet(),
    private val toRecord: suspend (SensorSample) -> Record?,
) : HealthRecordMapper {
    override val writePermissions = setOf(HealthPermission.getWritePermission(recordType)) + additionalPermissions
    override suspend fun toRecords(samples: List<SensorSample>): List<Record> = samples.mapNotNull { sample ->
        try {
            toRecord(sample)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Dropping ${sample.type} sample Health Connect rejects: $sample", error)
            null
        }
    }

    private companion object {
        const val TAG = "HealthRecordMapper"
    }
}

/**
 * Decorator for cumulative daily metrics written under a per-day client record id: keeps
 * only each local day's latest (largest) running total, so one push never carries two
 * records with the same id.
 */
class LatestPerDayMapper(
    private val zoneId: ZoneId,
    private val delegate: HealthRecordMapper,
) : HealthRecordMapper {
    override val writePermissions = delegate.writePermissions

    override suspend fun toRecords(samples: List<SensorSample>): List<Record> = delegate.toRecords(
        samples.groupBy { it.recordedAt.atZone(zoneId).toLocalDate() }.values.map { day -> day.maxBy { it.value } },
    )
}

/**
 * Interval bounds of [this]. Point samples (legacy watch builds) and zero-length intervals
 * (Health Services can report start == end) span [POINT_SAMPLE_SPAN]: Health Connect's
 * interval records require startTime strictly before endTime.
 */
private class Span(sample: SensorSample, zoneId: ZoneId) {
    val start: Instant = sample.recordedAt
    val end: Instant = sample.endedAt?.takeIf { it.isAfter(start) } ?: start.plus(POINT_SAMPLE_SPAN)
    val startOffset: ZoneOffset = start.offsetIn(zoneId)
    val endOffset: ZoneOffset = end.offsetIn(zoneId)
}

/** Records from the watch are sensor-recorded on the wrist, not typed in by the user. */
private val WATCH_DEVICE = Device(manufacturer = WATCH_MANUFACTURER, model = WATCH_MODEL, type = Device.TYPE_WATCH)
private val AUTOMATIC_METADATA = Metadata.autoRecorded(device = WATCH_DEVICE)

/**
 * One record per local day for a daily metric: Health Connect upserts by client record id,
 * and the write time as version makes each later daily value replace the earlier one.
 */
private fun dailyMetadata(recordIdPrefix: String, day: LocalDate): Metadata = Metadata.autoRecorded(
    device = WATCH_DEVICE,
    clientRecordId = "$recordIdPrefix$day",
    clientRecordVersion = Instant.now().toEpochMilli(),
)
private const val RESTING_HEART_RATE_RECORD_ID_PREFIX = "resting_hr_"
private const val TOTAL_CALORIES_RECORD_ID_PREFIX = "total_kcal_"
private const val VO2_MAX_RECORD_ID_PREFIX = "vo2_max_"
private const val BASAL_METABOLIC_RATE_RECORD_ID_PREFIX = "bmr_"

/** Health Connect's accepted `StepsCadenceRecord.Sample.rate` range (steps/min). */
private val VALID_STEPS_CADENCE = 0.0..10_000.0

/** Health Connect's accepted `HeartRateRecord.Sample.beatsPerMinute` range. */
private val VALID_BPM = 1L..300L
private const val WATCH_MANUFACTURER = "Mobvoi"
private const val WATCH_MODEL = "TicWatch Pro 5 Enduro"
private val POINT_SAMPLE_SPAN: Duration = Duration.ofMinutes(1)
private val MIN_SERIES_SPAN: Duration = Duration.ofSeconds(1)

private fun Instant.offsetIn(zoneId: ZoneId): ZoneOffset = atZone(zoneId).offset

/** The mapper registry: adding a data type means adding an entry here, nothing else. */
fun defaultHealthRecordMappers(
    zoneId: ZoneId,
    exerciseRoutes: ExerciseRouteFactory,
    sleepStages: SleepStageFactory,
): Map<SensorType, HealthRecordMapper> = mapOf(
    // A 0 bpm reading with no skin contact would sink the whole record.
    SensorType.HEART_RATE to SeriesMapper(
        recordType = HeartRateRecord::class,
        zoneId = zoneId,
        isValid = { it.value.toLong() in VALID_BPM },
        toSeriesSample = { HeartRateRecord.Sample(time = it.recordedAt, beatsPerMinute = it.value.toLong()) },
    ) { span, samples ->
        HeartRateRecord(span.start, span.startOffset, span.end, span.endOffset, samples, AUTOMATIC_METADATA)
    },

    SensorType.SPEED to SeriesMapper(
        recordType = SpeedRecord::class,
        zoneId = zoneId,
        isValid = { it.value > 0 },
        toSeriesSample = { SpeedRecord.Sample(time = it.recordedAt, speed = Velocity.metersPerSecond(it.value)) },
    ) { span, samples ->
        SpeedRecord(span.start, span.startOffset, span.end, span.endOffset, samples, AUTOMATIC_METADATA)
    },

    // Phone-derived from walking/running step intervals (see StepsCadenceDeriver).
    SensorType.STEPS_CADENCE to SeriesMapper(
        recordType = StepsCadenceRecord::class,
        zoneId = zoneId,
        isValid = { it.value in VALID_STEPS_CADENCE },
        toSeriesSample = { StepsCadenceRecord.Sample(time = it.recordedAt, rate = it.value) },
    ) { span, samples ->
        StepsCadenceRecord(span.start, span.startOffset, span.end, span.endOffset, samples, AUTOMATIC_METADATA)
    },

    // Profile-based estimate (see BasalMetabolicRateDeriver), one record per local day.
    SensorType.BASAL_METABOLIC_RATE to PerSampleMapper(BasalMetabolicRateRecord::class) { sample ->
        BasalMetabolicRateRecord(
            time = sample.recordedAt,
            zoneOffset = sample.recordedAt.offsetIn(zoneId),
            basalMetabolicRate = Power.kilocaloriesPerDay(sample.value),
            metadata = dailyMetadata(BASAL_METABOLIC_RATE_RECORD_ID_PREFIX, sample.recordedAt.atZone(zoneId).toLocalDate()),
        )
    },

    SensorType.RESTING_HEART_RATE to PerSampleMapper(RestingHeartRateRecord::class) { sample ->
        RestingHeartRateRecord(
            time = sample.recordedAt,
            zoneOffset = sample.recordedAt.offsetIn(zoneId),
            beatsPerMinute = sample.value.roundToLong(),
            metadata = dailyMetadata(RESTING_HEART_RATE_RECORD_ID_PREFIX, sample.recordedAt.atZone(zoneId).toLocalDate()),
        )
    },

    // Uth-Sorensen estimate (HRmax / HRrest ratio), one record per local day.
    SensorType.VO2_MAX to PerSampleMapper(Vo2MaxRecord::class) { sample ->
        Vo2MaxRecord(
            time = sample.recordedAt,
            zoneOffset = sample.recordedAt.offsetIn(zoneId),
            vo2MillilitersPerMinuteKilogram = sample.value,
            measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO,
            metadata = dailyMetadata(VO2_MAX_RECORD_ID_PREFIX, sample.recordedAt.atZone(zoneId).toLocalDate()),
        )
    },

    SensorType.STEPS to PerSampleMapper(StepsRecord::class) { sample ->
        val span = Span(sample, zoneId)
        StepsRecord(span.start, span.startOffset, span.end, span.endOffset, sample.value.toLong(), AUTOMATIC_METADATA)
            .takeIf { it.count > 0 }
    },

    SensorType.DISTANCE to PerSampleMapper(DistanceRecord::class) { sample ->
        val span = Span(sample, zoneId)
        DistanceRecord(span.start, span.startOffset, span.end, span.endOffset, Length.meters(sample.value), AUTOMATIC_METADATA)
            .takeIf { sample.value > 0 }
    },

    SensorType.FLOORS to PerSampleMapper(FloorsClimbedRecord::class) { sample ->
        val span = Span(sample, zoneId)
        FloorsClimbedRecord(span.start, span.startOffset, span.end, span.endOffset, sample.value, AUTOMATIC_METADATA)
            .takeIf { sample.value > 0 }
    },

    SensorType.ELEVATION_GAIN to PerSampleMapper(ElevationGainedRecord::class) { sample ->
        val span = Span(sample, zoneId)
        ElevationGainedRecord(span.start, span.startOffset, span.end, span.endOffset, Length.meters(sample.value), AUTOMATIC_METADATA)
            .takeIf { sample.value > 0 }
    },

    // On this watch Health Services' passive CALORIES excludes the basal rate (observed
    // ~0.01-0.08 kcal/min at rest), i.e. it is active burn only.
    SensorType.CALORIES to PerSampleMapper(ActiveCaloriesBurnedRecord::class) { sample ->
        val span = Span(sample, zoneId)
        ActiveCaloriesBurnedRecord(span.start, span.startOffset, span.end, span.endOffset, Energy.kilocalories(sample.value), AUTOMATIC_METADATA)
            .takeIf { sample.value > 0 }
    },

    // CALORIES_DAILY is cumulative since the day's start, basal included: one total record
    // per local day, each push replacing the previous (lower) running total.
    SensorType.CALORIES_DAILY to LatestPerDayMapper(
        zoneId,
        PerSampleMapper(TotalCaloriesBurnedRecord::class) { sample ->
            val span = Span(sample, zoneId)
            TotalCaloriesBurnedRecord(
                startTime = span.start,
                startZoneOffset = span.startOffset,
                endTime = span.end,
                endZoneOffset = span.endOffset,
                energy = Energy.kilocalories(sample.value),
                metadata = dailyMetadata(TOTAL_CALORIES_RECORD_ID_PREFIX, span.start.atZone(zoneId).toLocalDate()),
            ).takeIf { sample.value > 0 }
        },
    ),

    // Stages (SLEEP_STAGE samples, stored before this push is mapped) ride on the session record.
    SensorType.SLEEP to PerSampleMapper(SleepSessionRecord::class) { sample ->
        sample.endedAt?.let {
            val span = Span(sample, zoneId)
            SleepSessionRecord(
                startTime = span.start,
                startZoneOffset = span.startOffset,
                endTime = span.end,
                endZoneOffset = span.endOffset,
                stages = sleepStages.stagesFor(span.start, span.end),
                metadata = AUTOMATIC_METADATA,
            )
        }
    },

    // The route, when GPS fixes were recorded during the session, rides on the session record itself.
    SensorType.EXERCISE to PerSampleMapper(
        recordType = ExerciseSessionRecord::class,
        additionalPermissions = setOf(HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE),
        toRecord = { sample ->
            sample.endedAt?.let {
                val span = Span(sample, zoneId)
                ExerciseSessionRecord(
                    startTime = span.start,
                    startZoneOffset = span.startOffset,
                    endTime = span.end,
                    endZoneOffset = span.endOffset,
                    exerciseType = HealthServicesExerciseTypes.toHealthConnect(sample.value.toInt()),
                    exerciseRoute = exerciseRoutes.routeFor(span.start, span.end),
                    metadata = AUTOMATIC_METADATA,
                )
            }
        },
    ),

    SensorType.SPO2 to PerSampleMapper(OxygenSaturationRecord::class) { sample ->
        OxygenSaturationRecord(
            time = sample.recordedAt,
            zoneOffset = sample.recordedAt.offsetIn(zoneId),
            percentage = Percentage(sample.value),
            metadata = AUTOMATIC_METADATA,
        )
    },

    SensorType.HRV_RMSSD to PerSampleMapper(HeartRateVariabilityRmssdRecord::class) { sample ->
        HeartRateVariabilityRmssdRecord(
            time = sample.recordedAt,
            zoneOffset = sample.recordedAt.offsetIn(zoneId),
            heartRateVariabilityMillis = sample.value,
            metadata = AUTOMATIC_METADATA,
        )
    },

    // The watch's window median; stamped at the window end, when the rate was settled.
    SensorType.RESPIRATION_RATE to PerSampleMapper(RespiratoryRateRecord::class) { sample ->
        val time = sample.endedAt ?: sample.recordedAt
        RespiratoryRateRecord(
            time = time,
            zoneOffset = time.offsetIn(zoneId),
            rate = sample.value,
            metadata = AUTOMATIC_METADATA,
        )
    },

    // The watch sends the window's mean absolute skin temperature: it becomes the
    // record's baseline, with a single zero delta at the window start.
    SensorType.SKIN_TEMPERATURE to PerSampleMapper(SkinTemperatureRecord::class) { sample ->
        val span = Span(sample, zoneId)
        SkinTemperatureRecord(
            startTime = span.start,
            startZoneOffset = span.startOffset,
            endTime = span.end,
            endZoneOffset = span.endOffset,
            deltas = listOf(SkinTemperatureRecord.Delta(span.start, TemperatureDelta.celsius(NO_TEMPERATURE_DELTA))),
            baseline = Temperature.celsius(sample.value),
            measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST,
            metadata = AUTOMATIC_METADATA,
        )
    },

    // Phone-side estimate from wrist skin temperature (see CorrelatedBodyTemperatureEstimator).
    SensorType.BODY_TEMPERATURE to PerSampleMapper(BodyTemperatureRecord::class) { sample ->
        BodyTemperatureRecord(
            time = sample.recordedAt,
            zoneOffset = sample.recordedAt.offsetIn(zoneId),
            temperature = Temperature.celsius(sample.value),
            measurementLocation = BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_WRIST,
            metadata = AUTOMATIC_METADATA,
        )
    },
)

private const val NO_TEMPERATURE_DELTA = 0.0
