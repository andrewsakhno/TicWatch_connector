package org.sand.ticwatch.watch.sensors

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PhaseAwareHeartRateThinnerTest {

    private var fullStream = false
    private var asleep = false

    private val policy = ZoneHeartRatePhasePolicy(
        fullStream = { fullStream },
        asleep = { asleep },
        zone2Ceiling = { CEILING },
        stableNormal = STABLE_NORMAL,
    )

    private val thinner = PhaseAwareHeartRateThinner(
        periods = mapOf(HeartRateListeningPhase.REST to PERIOD, HeartRateListeningPhase.SLEEP to PERIOD),
        policy = policy,
    )

    private fun hr(second: Long, bpm: Double) = SensorSample(SensorType.HEART_RATE, bpm, START.plusSeconds(second))

    private fun thin(samples: List<SensorSample>) = runBlocking { thinner.thin(samples.asFlow()).toList() }

    @Test
    fun restInZone2SendsOneMedianPerPeriod() {
        val out = thin((0L until 60L).map { hr(it, RESTING_BPM + it % 3) } + hr(60, RESTING_BPM))

        assertEquals(2, out.size)
        assertEquals(listOf(START.plusSeconds(29), START.plusSeconds(59)), out.map { it.recordedAt })
    }

    @Test
    fun aboveZone2OpensFullStreamAtOnce() {
        val out = thin((0L until 10L).map { hr(it, RESTING_BPM) } + (10L until 20L).map { hr(it, ELEVATED_BPM) })

        assertEquals(1 + 10, out.size)
        assertEquals(RESTING_BPM, out.first().value, DELTA)
        assertEquals(ELEVATED_BPM, out.last().value, DELTA)
    }

    @Test
    fun fullStreamHoldsUntilStableNormalThenThinsAgain() {
        val elevated = listOf(hr(0, ELEVATED_BPM))
        val recovering = (1L until STABLE_NORMAL.seconds).map { hr(it, RESTING_BPM) }
        val stable = (STABLE_NORMAL.seconds until STABLE_NORMAL.seconds + PERIOD.seconds * 2).map { hr(it, RESTING_BPM) }

        val out = thin(elevated + recovering + stable + hr(STABLE_NORMAL.seconds + PERIOD.seconds * 2, RESTING_BPM))

        assertEquals(elevated.size + recovering.size + 2, out.size)
    }

    @Test
    fun workoutLiveSyncOrAlertPassEveryReadingAwakeOrAsleep() {
        fullStream = true
        asleep = true

        assertEquals(40, thin((0L until 40L).map { hr(it, RESTING_BPM) }).size)
    }

    @Test
    fun sleepThinsEvenAboveZone2() {
        asleep = true

        val out = thin((0L until 30L).map { hr(it, ELEVATED_BPM) } + hr(30, ELEVATED_BPM))

        assertEquals(1, out.size)
        assertEquals(ELEVATED_BPM, out.single().value, DELTA)
    }

    @Test
    fun phaseChangeClosesOpenPeriodAndOtherTypesPass() {
        val steps = SensorSample(SensorType.STEPS, 5.0, START.plusSeconds(1))
        val samples = listOf(hr(0, RESTING_BPM), steps, hr(2, 0.0), hr(3, ELEVATED_BPM))

        val out = thin(samples)

        assertEquals(listOf(SensorType.STEPS, SensorType.HEART_RATE, SensorType.HEART_RATE), out.map { it.type })
        assertEquals(listOf(RESTING_BPM, ELEVATED_BPM), out.filter { it.type == SensorType.HEART_RATE }.map { it.value })
    }

    private companion object {
        val START: Instant = Instant.parse("2026-11-08T10:00:00Z")
        val PERIOD: Duration = Duration.ofSeconds(30)
        val STABLE_NORMAL: Duration = Duration.ofMinutes(3)
        const val CEILING = 130.0
        const val RESTING_BPM = 70.0
        const val ELEVATED_BPM = 140.0
        const val DELTA = 1e-9
    }
}
