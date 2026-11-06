package org.sand.ticwatch.phone.health

import org.sand.ticwatch.phone.history.DeliveryStatus
import org.sand.ticwatch.phone.history.MetricCoverage
import org.sand.ticwatch.phone.history.RollupPoint
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SampleCursor
import org.sand.ticwatch.phone.history.SampleEraser
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.SleepSession
import org.sand.ticwatch.phone.history.StoredSample
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** In-memory raw history: written through [HealthRecordWriter], read back through [SensorHistoryQueries]. */
class InMemoryHistory : SensorHistoryQueries, HealthRecordWriter, SampleEraser {
    private val rows = mutableListOf<StoredSample>()

    fun add(vararg samples: SensorSample) {
        samples.forEach { rows += StoredSample(rows.size.toLong(), it, it.recordedAt, DeliveryStatus.DELIVERED, null) }
    }

    override val requiredPermissions: Set<String> = emptySet()

    override suspend fun hasRequiredPermissions(): Boolean = true

    override suspend fun write(samples: List<SensorSample>): Result<Unit> = Result.success(add(*samples.toTypedArray()))

    override suspend fun eraseSamples(types: Set<SensorType>, at: Instant): Int {
        val erased = rows.filter { it.sample.type in types && it.sample.recordedAt == at }
        rows.removeAll(erased)
        return erased.size
    }

    override suspend fun rawSamples(
        types: Set<SensorType>,
        from: Instant,
        to: Instant,
        after: SampleCursor?,
        limit: Int,
    ): List<StoredSample> = rows
        .filter { it.sample.type in types && !it.sample.recordedAt.isBefore(from) && it.sample.recordedAt.isBefore(to) }
        .sortedWith(compareBy({ it.sample.recordedAt }, { it.id }))
        .filter { after == null || it.sample.recordedAt > after.recordedAt || (it.sample.recordedAt == after.recordedAt && it.id > after.id) }
        .take(limit)

    override suspend fun coverage(): List<MetricCoverage> = emptyList()

    override suspend fun rollups(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant): List<RollupPoint> =
        emptyList()

    override suspend fun summary(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant): RollupPoint? = null

    override suspend fun sleepSessions(from: Instant, to: Instant, afterStart: Instant?, limit: Int): List<SleepSession> = emptyList()

    override suspend fun deliveryCounts(from: Instant, to: Instant): Map<DeliveryStatus, Long> = emptyMap()
}

class InMemoryCalibrations(var current: BloodPressureCalibration? = null) : BloodPressureCalibrationStore {
    override suspend fun bloodPressureCalibration(): BloodPressureCalibration? = current

    override suspend fun setBloodPressureCalibration(calibration: BloodPressureCalibration?) {
        current = calibration
    }
}

/** A clock the test moves by hand. */
class MutableClock(var now: Instant) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

/** A [BloodPressureMonitor] over in-memory history and calibration. */
class BloodPressureTestFixture(start: Instant) {
    val clock = MutableClock(start)
    val history = InMemoryHistory()
    val calibrations = InMemoryCalibrations()
    val monitor = BloodPressureMonitor(
        calibrations = calibrations,
        signals = WristSignalsReader(SampleWindowReader(history)),
        estimator = BloodPressureEstimator(),
        systolicOffset = { 0.0 },
        restCheck = { _, _ -> null },
        restingFloor = { null },
        log = BloodPressureLog(history, SampleWindowReader(history), history, {}, {}),
        clock = clock,
    )

    fun watchHeartRate(bpm: Double, at: Instant) = history.add(SensorSample(SensorType.HEART_RATE, bpm, at))

    suspend fun saveCuffReading(systolic: Double, diastolic: Double, at: Instant) {
        monitor.log.save(BloodPressureReading(systolic, diastolic, at, SampleSource.BLOOD_PRESSURE_CUFF, CUFF_PULSE))
    }

    companion object {
        const val CUFF_PULSE = 70.0
    }
}
