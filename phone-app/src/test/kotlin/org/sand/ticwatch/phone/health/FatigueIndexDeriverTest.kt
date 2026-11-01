package org.sand.ticwatch.phone.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sand.ticwatch.phone.insights.FatigueComponent
import org.sand.ticwatch.phone.insights.FatigueIndex
import org.sand.ticwatch.phone.insights.FatigueIndexCalculator
import org.sand.ticwatch.phone.insights.FatigueInputs
import org.sand.ticwatch.phone.insights.FatigueLevel
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class FatigueIndexDeriverTest {

    private val calculator = FatigueIndexCalculator()
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val push = listOf(SensorSample(SensorType.HEART_RATE, 70.0, now))

    private val hardDay = FatigueInputs(
        steps24h = 18_000.0,
        trainingLoad24h = 0.0,
        rmssdMs = 28.0,
        rmssdBaselineMs = 45.0,
        restingHeartRateBpm = 72.0,
        heartRateNormBpm = 64.0,
        sleepScore = 50.0,
        vagalRecoveryRatio = 0.8,
    )

    private val restDay = FatigueInputs(
        steps24h = 2_000.0,
        trainingLoad24h = 0.0,
        rmssdMs = 55.0,
        rmssdBaselineMs = 45.0,
        restingHeartRateBpm = 60.0,
        heartRateNormBpm = 64.0,
        sleepScore = 88.0,
        vagalRecoveryRatio = 1.3,
    )

    private class MemoryLog(private var at: Instant? = null) : InstantLog {
        override suspend fun last(): Instant? = at
        override suspend fun record(at: Instant) {
            this.at = at
        }
    }

    private fun deriver(inputs: FatigueInputs, log: InstantLog = MemoryLog(), at: Instant = now) = FatigueIndexDeriver(
        source = { calculator.calculate(inputs) },
        log = log,
        clock = Clock.fixed(at, ZoneOffset.UTC),
    )

    private fun strain(index: FatigueIndex?, component: FatigueComponent): Double = checkNotNull(index?.components?.get(component))

    @Test
    fun `day after 18k steps and poor sleep reads as pronounced fatigue`() = runBlocking {
        val sample = deriver(hardDay).derive(push).single()

        assertEquals(SensorType.FATIGUE_INDEX, sample.type)
        assertEquals(now, sample.recordedAt)
        assertEquals(71.0, sample.value, 0.0)
        assertEquals(FatigueLevel.PRONOUNCED, FatigueLevel.of(sample.value.toInt()))
    }

    @Test
    fun `day of full rest reads as fresh`() = runBlocking {
        val sample = deriver(restDay).derive(push).single()

        assertEquals(3.0, sample.value, 0.0)
        assertEquals(FatigueLevel.FRESH, FatigueLevel.of(sample.value.toInt()))
    }

    @Test
    fun `second push within the interval derives nothing and a later one does`() = runBlocking {
        val log = MemoryLog()
        assertEquals(1, deriver(restDay, log).derive(push).size)

        assertTrue(deriver(restDay, log, now.plus(Duration.ofMinutes(29))).derive(push).isEmpty())
        assertEquals(1, deriver(restDay, log, now.plus(Duration.ofMinutes(30))).derive(push).size)
    }

    @Test
    fun `without enough data nothing is stored and the next push tries again`() = runBlocking {
        val log = MemoryLog()
        val onlySteps = FatigueInputs(18_000.0, null, null, null, null, null, null, null)

        assertTrue(deriver(onlySteps, log).derive(push).isEmpty())
        assertNull(log.last())
    }

    @Test
    fun `every signal at its worst reaches 100 and exceeding a ceiling does not pass it`() {
        val index = calculator.calculate(
            FatigueInputs(40_000.0, 500.0, 10.0, 60.0, 110.0, 60.0, 0.0, 0.2),
        )

        assertEquals(100, index?.percent)
        assertEquals(FatigueLevel.EXHAUSTED, index?.level)
    }

    @Test
    fun `no strain at all is 0`() {
        assertEquals(0, calculator.calculate(FatigueInputs(0.0, 0.0, 80.0, 50.0, 50.0, 60.0, 100.0, 1.6))?.percent)
    }

    @Test
    fun `steps and TRIMP count once - the larger of the two`() {
        val steps = calculator.calculate(hardDay.copy(steps24h = 10_000.0, trainingLoad24h = 40.0))
        val trimp = calculator.calculate(hardDay.copy(steps24h = 2_000.0, trainingLoad24h = 150.0))

        assertEquals(0.5, strain(steps, FatigueComponent.PHYSICAL_LOAD), 1e-9)
        assertEquals(0.75, strain(trimp, FatigueComponent.PHYSICAL_LOAD), 1e-9)
    }

    @Test
    fun `RMSSD under 25 ms is the full penalty even at the personal baseline`() {
        val index = calculator.calculate(restDay.copy(rmssdMs = 24.0, rmssdBaselineMs = 24.0))

        assertEquals(1.0, strain(index, FatigueComponent.AUTONOMIC_STRAIN), 1e-9)
    }

    @Test
    fun `RMSSD penalty starts at 30 ms and rises to 25 ms`() {
        fun penalty(rmssd: Double) = strain(calculator.calculate(restDay.copy(rmssdMs = rmssd, rmssdBaselineMs = null)), FatigueComponent.AUTONOMIC_STRAIN)

        assertEquals(0.0, penalty(30.0), 1e-9)
        assertEquals(0.5, penalty(27.5), 1e-9)
        assertEquals(1.0, penalty(25.0), 1e-9)
    }

    @Test
    fun `RMSSD half the baseline is full strain above the absolute penalty`() {
        val index = calculator.calculate(restDay.copy(rmssdMs = 40.0, rmssdBaselineMs = 80.0))

        assertEquals(1.0, strain(index, FatigueComponent.AUTONOMIC_STRAIN), 1e-9)
    }

    @Test
    fun `recovery deficit starts below sleep score 70 and vagal ratio 1`() {
        fun deficit(score: Double?, ratio: Double?) =
            strain(calculator.calculate(restDay.copy(sleepScore = score, vagalRecoveryRatio = ratio)), FatigueComponent.RECOVERY_DEFICIT)

        assertEquals(0.0, deficit(70.0, 1.0), 1e-9)
        assertEquals(1.0, deficit(40.0, 1.0), 1e-9)
        assertEquals(1.0, deficit(70.0, 0.7), 1e-9)
    }

    @Test
    fun `missing components are renormalised over the rest`() {
        val index = calculator.calculate(restDay.copy(steps24h = 20_000.0, trainingLoad24h = null, restingHeartRateBpm = null, sleepScore = null, vagalRecoveryRatio = null))

        assertEquals(setOf(FatigueComponent.PHYSICAL_LOAD, FatigueComponent.AUTONOMIC_STRAIN), index?.components?.keys)
        assertEquals(50, index?.percent)
    }

    @Test
    fun `bands follow the agreed edges`() {
        assertEquals(FatigueLevel.FRESH, FatigueLevel.of(30))
        assertEquals(FatigueLevel.MODERATE, FatigueLevel.of(31))
        assertEquals(FatigueLevel.MODERATE, FatigueLevel.of(60))
        assertEquals(FatigueLevel.PRONOUNCED, FatigueLevel.of(61))
        assertEquals(FatigueLevel.PRONOUNCED, FatigueLevel.of(80))
        assertEquals(FatigueLevel.EXHAUSTED, FatigueLevel.of(81))
        assertEquals(FatigueLevel.EXHAUSTED, FatigueLevel.of(100))
    }
}
