package org.sand.ticwatch.phone.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.sand.ticwatch.phone.health.HeartRateZone
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DailySummariesTest {

    private val date = LocalDate.of(2026, 10, 27)
    private val hardWeek = (0L until 7L).associate { date.minusDays(it) to 100.0 }

    @Test
    fun `equal chronic and acute load is optimal`() {
        val loads = (0L until 28L).associate { date.minusDays(it) to 100.0 }

        val balance = TrainingLoadBalanceCalculator().balance(date, loads)

        assertEquals(700.0, balance.acuteLoad, DELTA)
        assertEquals(700.0, balance.chronicWeeklyLoad, DELTA)
        assertEquals(LoadBalance.OPTIMAL, balance.balance)
    }

    @Test
    fun `a sudden hard week is overreaching and no history gives no ratio`() {
        val loads = (0L until 7L).associate { date.minusDays(it) to 100.0 }
        val calculator = TrainingLoadBalanceCalculator()

        assertEquals(LoadBalance.VERY_HIGH, calculator.balance(date, loads).balance)
        assertNull(calculator.balance(date, emptyMap()).ratio)
    }

    @Test
    fun `a small first workout without chronic history is not overreaching`() {
        val balance = TrainingLoadBalanceCalculator().balance(date, mapOf(date to 40.0))

        assertEquals(4.0, balance.ratio!!, DELTA)
        assertEquals(LoadBalance.OPTIMAL, balance.balance)
        assertEquals(LoadBalance.OPTIMAL.maxRatio, balance.gaugeRatio!!, DELTA)
    }

    @Test
    fun `acute load at the minimum volume can be overreaching`() {
        val balance = TrainingLoadBalanceCalculator().balance(date, mapOf(date to 150.0))

        assertEquals(LoadBalance.VERY_HIGH, balance.balance)
    }

    @Test
    fun `a big week mostly in zones 1 and 2 is not overreaching`() {
        val zones = mapOf(date to mapOf(HeartRateZone.ZONE_1 to 200.0, HeartRateZone.ZONE_2 to 70.0, HeartRateZone.ZONE_3 to 30.0))

        val balance = TrainingLoadBalanceCalculator().balance(date, hardWeek, zones)

        assertEquals(LoadBalance.OPTIMAL, balance.balance)
    }

    @Test
    fun `a big week reaching zones 3 to 5 is overreaching`() {
        val zones = mapOf(
            date to mapOf(HeartRateZone.ZONE_2 to 100.0, HeartRateZone.ZONE_3 to 60.0, HeartRateZone.ZONE_4 to 30.0, HeartRateZone.ZONE_5 to 10.0),
            date.minusDays(7) to mapOf(HeartRateZone.ZONE_1 to 1_000.0), // before the acute week: not counted
        )

        val balance = TrainingLoadBalanceCalculator().balance(date, hardWeek, zones)

        assertEquals(LoadBalance.VERY_HIGH, balance.balance)
        assertEquals(balance.ratio!!, balance.gaugeRatio!!, DELTA)
    }

    @Test
    fun `stress shares skip excluded spans`() {
        val readings = listOf(80.0, 60.0, 40.0, 10.0, 90.0).mapIndexed { index, rmssd -> TrendPoint(at(index * 30L), rmssd) }
        val sleep = TimeSpan(at(120), at(150))

        val summary = DailyStressCalculator().summarize(readings, listOf(sleep))!!

        assertEquals(4, summary.measurements)
        StressLevel.entries.forEach { assertEquals(0.25, summary.share(it), DELTA) }
    }

    @Test
    fun `morning light counts bright readings up to the measuring period inside 06-12`() {
        val calculator = CircadianLightExposureCalculator(ZoneOffset.UTC)
        val readings = listOf(
            TrendPoint(at(-15), 1_500.0), // 05:45, bright until the 06:10 reading: 10 min inside the morning
            TrendPoint(at(10), 500.0),
            TrendPoint(at(60), 5_000.0), // 07:00, nothing after: capped at 30 min
        )

        val exposure = calculator.exposure(date, readings)

        assertEquals(Duration.ofMinutes(40), exposure.bright)
        assertEquals(true, exposure.goalReached)
    }

    /** [minutes] after 06:00 UTC on [date]. */
    private fun at(minutes: Long): Instant = date.atTime(6, 0).toInstant(ZoneOffset.UTC).plus(Duration.ofMinutes(minutes))

    private companion object {
        const val DELTA = 1e-9
    }
}
