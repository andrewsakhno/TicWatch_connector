package org.sand.ticwatch.phone.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.sand.ticwatch.phone.health.SleepStageType
import java.time.Duration

class SleepScoreCalculatorTest {

    private val calculator = SleepScoreCalculator()

    @Test
    fun `ideal night scores 100`() {
        val score = calculator.score(
            SleepScoreInputs(
                inBed = Duration.ofHours(8),
                stageDurations = mapOf(
                    SleepStageType.AWAKE to Duration.ofMinutes(20),
                    SleepStageType.DEEP to Duration.ofMinutes(90),
                    SleepStageType.REM to Duration.ofMinutes(96),
                    SleepStageType.LIGHT to Duration.ofMinutes(274),
                ),
                awakenings = 1,
                sleepHeartRateBpm = 50.0,
                daytimeHeartRateBpm = 62.0,
                desaturationIndexPerHour = 2.0,
            ),
        )

        assertEquals(100, score.score)
        assertEquals(SleepScoreComponent.entries.toSet(), score.components.keys)
    }

    @Test
    fun `short unstaged night is scored on duration alone`() {
        val score = calculator.score(inputs(inBed = Duration.ofHours(5)))

        assertEquals(setOf(SleepScoreComponent.DURATION), score.components.keys)
        assertEquals(17, score.score)
    }

    @Test
    fun `missing components are left out of the weights`() {
        val score = calculator.score(inputs(inBed = Duration.ofHours(8), odi = 15.0))

        // (0.30 * 1 + 0.15 * 0) / 0.45
        assertEquals(67, score.score)
        assertFalse(SleepScoreComponent.ARCHITECTURE in score.components)
    }

    private fun inputs(inBed: Duration, odi: Double? = null) = SleepScoreInputs(
        inBed = inBed,
        stageDurations = emptyMap(),
        awakenings = 0,
        sleepHeartRateBpm = null,
        daytimeHeartRateBpm = null,
        desaturationIndexPerHour = odi,
    )
}
