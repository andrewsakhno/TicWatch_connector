package org.sand.ticwatch.phone.advisor

import androidx.annotation.StringRes
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.sand.ticwatch.phone.R

private const val PERCENT = 100

/** What opened a post-stress window. */
enum class PostStressTrigger(@StringRes val label: Int) {
    /** A resting tachycardia deviation episode ended. */
    RESTING_TACHYCARDIA(R.string.post_stress_trigger_tachycardia),

    /** Heart rate fell back sharply after a raised stretch at rest, no activity in between. */
    HEART_RATE_DROP(R.string.post_stress_trigger_drop),
}

/**
 * Nervous tone: [POST_STRESS] and [RECOVERING] only inside a post-stress window, [STRAINED] is the same low balance
 * without a stress event behind it. [maxPercent] is the exclusive upper limit of the band.
 */
enum class AutonomicTone(@StringRes val label: Int, val maxPercent: Int) {
    POST_STRESS(R.string.autonomic_tone_post_stress, 40),
    RECOVERING(R.string.autonomic_tone_recovering, 70),
    STRAINED(R.string.autonomic_tone_strained, 40),
    CALM(R.string.autonomic_tone_calm, PERCENT + 1);

    companion object {
        fun of(percent: Int, inWindow: Boolean): AutonomicTone = when {
            inWindow && percent < POST_STRESS.maxPercent -> POST_STRESS
            inWindow && percent < RECOVERING.maxPercent -> RECOVERING
            !inWindow && percent < STRAINED.maxPercent -> STRAINED
            else -> CALM
        }
    }
}

/** What the balance is made of; [weight] is the component's share when both have data. */
enum class AutonomicComponent(@StringRes val label: Int, val weight: Double) {
    VAGAL_WITHDRAWAL(R.string.autonomic_component_vagal, 0.6),
    HEART_RATE_EXCESS(R.string.autonomic_component_heart_rate, 0.4),
}

/** The open post-stress window: [trigger] ended at [triggerEndedAt], the wearer stays vulnerable until [endsAt]. */
data class PostStressWindow(val trigger: PostStressTrigger, val triggerEndedAt: Instant, val endsAt: Instant)

/**
 * Autonomic balance 0..100 (100 = full calm) with each [AutonomicComponent] as strain 0..1. [vulnerability] >= 1
 * stretches the window after poor sleep or high fatigue; [window] is null outside one.
 */
data class AutonomicBalance(
    val percent: Int,
    val components: Map<AutonomicComponent, Double>,
    val vulnerability: Double,
    val window: PostStressWindow?,
) {
    val tone: AutonomicTone get() = AutonomicTone.of(percent, window != null)

    /** Sympathetic tone while outwardly at rest: the case the breathing advice is for. */
    val postStressActive: Boolean get() = tone == AutonomicTone.POST_STRESS
}

/** Signals at [at]; each null without recent data. */
data class AutonomicBalanceInputs(
    val at: Instant,
    /** The latest end of a stress event and what it was, null without one in the lookback. */
    val lastStressEnd: Pair<PostStressTrigger, Instant>?,
    val rmssdMs: Double?,
    /** Personal RMSSD of this local hour of day. */
    val rmssdBaselineMs: Double?,
    val heartRateBpm: Double?,
    val restingHeartRateBpm: Double?,
    val sleepScore: Int?,
    val fatiguePercent: Int?,
)

/**
 * Post-stress autonomic lag, heuristic and uncalibrated:
 * - Vagal withdrawal: RMSSD below the hour's baseline, 0 at the baseline, full at [VAGAL_OFF_RATIO] of it (parasympathetic
 *   braking off).
 * - Heart rate excess: above the resting heart rate, full at [FULL_HEART_RATE_EXCESS_BPM].
 * Weighted mean of the components with data, null with none. A stress event opens a window of [BASE_WINDOW] x the
 * vulnerability: up to [MAX_EXTRA_VULNERABILITY] more, half from the sleep score below
 * [SleepThresholds.GOOD_SLEEP_SCORE] (full at [FULL_DEFICIT_SLEEP_SCORE]) and half from the fatigue index above
 * [FATIGUE_VULNERABLE_FROM] (full at [PERCENT]) - depleted frontal inhibition lets the sympathetic tail run longer.
 */
class AutonomicBalanceCalculator {

    fun calculate(inputs: AutonomicBalanceInputs): AutonomicBalance? {
        val components = AutonomicComponent.entries
            .mapNotNull { component -> strain(component, inputs)?.let { component to it.coerceIn(0.0, 1.0) } }
            .toMap()
        if (components.isEmpty()) return null
        val knownWeight = components.keys.sumOf { it.weight }
        val strain = components.entries.sumOf { (component, value) -> component.weight * value } / knownWeight
        val vulnerability = vulnerability(inputs.sleepScore, inputs.fatiguePercent)
        val window = inputs.lastStressEnd?.let { (trigger, endedAt) ->
            val endsAt = endedAt.plus(windowLength(vulnerability))
            PostStressWindow(trigger, endedAt, endsAt).takeIf { inputs.at.isBefore(endsAt) }
        }
        return AutonomicBalance(((1 - strain) * PERCENT).roundToInt(), components, vulnerability, window)
    }

    private fun strain(component: AutonomicComponent, inputs: AutonomicBalanceInputs): Double? = when (component) {
        AutonomicComponent.VAGAL_WITHDRAWAL -> {
            val rmssd = inputs.rmssdMs
            val baseline = inputs.rmssdBaselineMs?.takeIf { it > 0 }
            if (rmssd == null || baseline == null) null else (1 - rmssd / baseline) / (1 - VAGAL_OFF_RATIO)
        }
        AutonomicComponent.HEART_RATE_EXCESS -> {
            val heartRate = inputs.heartRateBpm
            val resting = inputs.restingHeartRateBpm
            if (heartRate == null || resting == null) null else (heartRate - resting) / FULL_HEART_RATE_EXCESS_BPM
        }
    }

    private fun vulnerability(sleepScore: Int?, fatiguePercent: Int?): Double {
        val sleepDeficit = sleepScore?.let {
            (SleepThresholds.GOOD_SLEEP_SCORE - it).toDouble() / (SleepThresholds.GOOD_SLEEP_SCORE - FULL_DEFICIT_SLEEP_SCORE)
        } ?: 0.0
        val fatigueExcess = fatiguePercent?.let { (it - FATIGUE_VULNERABLE_FROM).toDouble() / (PERCENT - FATIGUE_VULNERABLE_FROM) } ?: 0.0
        return 1 + MAX_EXTRA_VULNERABILITY * (sleepDeficit.coerceIn(0.0, 1.0) + fatigueExcess.coerceIn(0.0, 1.0)) / 2
    }

    private fun windowLength(vulnerability: Double): Duration = Duration.ofSeconds((BASE_WINDOW.seconds * vulnerability).roundToLong())

    companion object {
        val BASE_WINDOW: Duration = Duration.ofMinutes(90)
        const val MAX_EXTRA_VULNERABILITY = 1.0

        /** The longest window: what a reader must look back over for stress events. */
        val MAX_WINDOW: Duration = Duration.ofSeconds((BASE_WINDOW.seconds * (1 + MAX_EXTRA_VULNERABILITY)).toLong())

        private const val VAGAL_OFF_RATIO = 0.6
        private const val FULL_HEART_RATE_EXCESS_BPM = 20.0
        private const val FULL_DEFICIT_SLEEP_SCORE = 40
        private const val FATIGUE_VULNERABLE_FROM = 60
    }
}

/** One 5-minute bucket of the heart rate trend with the steps taken in it. */
data class RestingHeartRateBucket(val start: Instant, val heartRateBpm: Double, val steps: Double)

/**
 * Finds the latest sharp fall of heart rate at rest: a bucket at least [RISE_BPM] over [baselineBpm] followed by one
 * back within [SETTLED_BPM] of it, with no bucket over [MAX_RESTING_STEPS] steps from the rise to the fall (a walk
 * ending isn't a stress tail). Returns where the settled bucket starts.
 */
class HeartRateDropDetector {

    fun lastDropEnd(buckets: List<RestingHeartRateBucket>, baselineBpm: Double): Instant? {
        var raised = false
        var lastEnd: Instant? = null
        buckets.sortedBy { it.start }.forEach { bucket ->
            when {
                bucket.steps > MAX_RESTING_STEPS -> raised = false
                bucket.heartRateBpm >= baselineBpm + RISE_BPM -> raised = true
                raised && bucket.heartRateBpm <= baselineBpm + SETTLED_BPM -> {
                    lastEnd = bucket.start
                    raised = false
                }
            }
        }
        return lastEnd
    }

    private companion object {
        const val RISE_BPM = 20.0
        const val SETTLED_BPM = 8.0

        /** 40 steps/min over a 5-minute bucket, as the fatigue index's resting buckets. */
        const val MAX_RESTING_STEPS = 200.0
    }
}

/** The balance at the readiness snapshot's instant, reusing its sleep score and fatigue index. */
fun interface AutonomicBalanceSource {
    suspend fun current(readiness: CardioReadinessInputs): AutonomicBalance?
}
