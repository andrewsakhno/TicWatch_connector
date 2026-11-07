package org.sand.ticwatch.phone.advisor

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sand.ticwatch.phone.health.BloodPressureCalibration
import org.sand.ticwatch.phone.health.CalibrationRestViolation
import org.sand.ticwatch.phone.health.WristSignals
import org.sand.ticwatch.phone.medication.MedicationProtection
import org.sand.ticwatch.phone.medication.MedicationProtectionPhase
import org.sand.ticwatch.phone.text.ResourceText

class CalibrationAdvisorTest {

    private val now: Instant = Instant.parse("2026-11-07T10:00:00Z")
    private val advisor = CalibrationAdvisor()

    private val healthyCalibration = BloodPressureCalibration(118.0, 76.0, 62.0, 45.0, 33.0, now.minus(Duration.ofDays(2)))
    private val freshWrist = WristSignals(64.0, now.minusSeconds(60), 42.0, 33.2)
    private val trainedPulseWave = PulseWaveTraining(listOf(112.0, 118.0, 125.0, 131.0, 142.0), PAIRS_REQUIRED, 6.0)
    private val trainedOffset = OffsetTraining(5, OFFSET_WINDOW, 4.0, 5.0, 3.0)

    private fun inputs(
        calibration: BloodPressureCalibration? = healthyCalibration,
        stepsPerMinute: Double = 0.0,
        wrist: WristSignals? = freshWrist,
        violation: CalibrationRestViolation? = null,
        pulseWave: PulseWaveTraining = trainedPulseWave,
        offset: OffsetTraining = trainedOffset,
        autonomic: AutonomicBalance? = null,
        medication: MedicationProtection = MedicationProtection(MedicationProtectionPhase.PLATEAU, now.minus(Duration.ofHours(6)), Duration.ofHours(6)),
    ) = CalibrationAdvisorInputs(
        at = now,
        calibration = calibration,
        restingHeartRateBpm = 60.0,
        stepsPerMinute = stepsPerMinute,
        wrist = wrist,
        restViolation = violation,
        pulseWave = pulseWave,
        offset = offset,
        autonomic = autonomic,
        medication = medication,
        forecast = emptyList(),
    )

    @Test
    fun `hypertensive baseline is flagged with its resting floor`() {
        val trapped = BloodPressureCalibration(149.0, 99.0, 96.0, 40.0, 33.0, now.minus(Duration.ofDays(3)))

        val advice = advisor.advise(inputs(calibration = trapped))

        val finding = advice.baseline.findings.first { it.issue == BaselineIssue.HYPERTENSIVE_TRAP }
        val floor = advice.baseline.floor!!
        // min(149, 135) - 15 systolic, 99 - 15 diastolic, as BloodPressureEstimator clamps at rest.
        assertEquals(120.0, floor.systolic, DELTA)
        assertEquals(84.0, floor.diastolic, DELTA)
        assertEquals(listOf(149, 99, 120, 84), (finding.detail as ResourceText).args)
        assertTrue(advice.baseline.findings.any { it.issue == BaselineIssue.ELEVATED_HEART_RATE })
        assertEquals(ReadinessState.LIMITED, advice.urgency)
        assertEquals(PlanKind.RECALIBRATE, advice.plan.first().kind)
        assertEquals(now, advice.plan.first().at)
    }

    @Test
    fun `missing pulse-wave pair asks for one more cuff and strip`() {
        val advice = advisor.advise(inputs(pulseWave = PulseWaveTraining(listOf(120.0, 126.0, 133.0), PAIRS_REQUIRED, null)))

        assertEquals(1, advice.progress.pulseWave.remaining)
        val item = advice.plan.single { it.kind == PlanKind.MORE_PAIRS }
        assertEquals(listOf(1, PAIRS_REQUIRED), (item.detail as ResourceText).args)
        assertEquals(ReadinessState.MODERATE, advice.urgency)
    }

    @Test
    fun `narrow calm-only pairs ask for a pair in the unprotected state`() {
        val calmOnly = PulseWaveTraining(listOf(118.0, 120.0, 122.0, 124.0), PAIRS_REQUIRED, 3.0)
        val medication = MedicationProtection(MedicationProtectionPhase.PEAK, now.minus(Duration.ofHours(2)), Duration.ofHours(2))

        val advice = advisor.advise(inputs(pulseWave = calmOnly, medication = medication))

        assertEquals(PulseWaveCoverage.ONLY_CALM, advice.progress.coverage)
        val item = advice.plan.single { it.kind == PlanKind.BROADEN_RANGE }
        assertEquals(now.minus(Duration.ofHours(2)).plus(MedicationProtectionPhase.WANING.startsAfter), item.at)
    }

    @Test
    fun `offset progress counts before-cuff comparisons and explains the order`() {
        val advice = advisor.advise(inputs(offset = OffsetTraining(3, OFFSET_WINDOW, 0.0, 9.0, 4.0)))

        assertEquals(3, advice.progress.offset.counted)
        val item = advice.plan.single { it.kind == PlanKind.OFFSET_COMPARISONS }
        assertEquals(listOf(2), (item.detail as ResourceText).args)
    }

    @Test
    fun `walking blocks a calibration right now`() {
        val advice = advisor.advise(inputs(stepsPerMinute = 85.0))

        assertEquals(ImmediateState.NOT_AT_REST_STEPS, advice.immediate.state)
        assertEquals(advice.immediate.findings.first().detail, advice.headline)
    }

    @Test
    fun `unsteady pulse blocks a calibration right now`() {
        val advice = advisor.advise(inputs(violation = CalibrationRestViolation.Unsteady(78.0, 8.5)))

        assertEquals(ImmediateState.UNSTEADY_OR_HIGH_PULSE, advice.immediate.state)
    }

    @Test
    fun `post-stress window blocks a resting baseline`() {
        val window = PostStressWindow(PostStressTrigger.entries.first(), now.minus(Duration.ofMinutes(20)), now.plus(Duration.ofMinutes(70)))
        val strained = AutonomicBalance(percent = 20, components = emptyMap(), vulnerability = 1.0, window = window)

        val advice = advisor.advise(inputs(autonomic = strained))

        assertTrue(advice.immediate.has(ImmediateState.POST_STRESS_ACTIVE))
    }

    @Test
    fun `missing RMSSD and skin temperature ask for a watch sync first`() {
        val advice = advisor.advise(inputs(wrist = freshWrist.copy(rmssdMs = null, skinTemperatureCelsius = null)))

        assertEquals(ImmediateState.MISSING_WRIST_SIGNALS, advice.immediate.state)
        assertFalse(advice.immediate.readyNow)
    }

    @Test
    fun `calm, rested and fully signalled moment is ready now`() {
        val advice = advisor.advise(inputs())

        assertTrue(advice.immediate.readyNow)
        assertEquals(ImmediateState.READY_NOW, advice.immediate.state)
        assertTrue(advice.baseline.healthy)
        assertEquals(ReadinessState.OPTIMAL, advice.urgency)
        assertTrue(advice.plan.isEmpty())
    }

    private companion object {
        const val PAIRS_REQUIRED = 4
        const val OFFSET_WINDOW = 5
        const val DELTA = 1e-9
    }
}
