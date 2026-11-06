package org.sand.ticwatch.phone.deviation

import androidx.annotation.StringRes
import org.sand.ticwatch.phone.R
import java.time.Duration
import java.time.Instant

/** Which side of the range the heart rate left; [code] is the persisted value (explicit, never `ordinal`). */
enum class DeviationDirection(val code: Int, @StringRes val label: Int) {
    HIGH(0, R.string.deviation_direction_high),
    LOW(1, R.string.deviation_direction_low);

    /** The further-out of two readings: what an episode reports as its peak. */
    fun peakOf(first: Double, second: Double): Double = if (this == HIGH) maxOf(first, second) else minOf(first, second)

    /** How far [bpm] lies beyond [thresholdBpm] on this side, 0 inside. */
    fun excessOf(bpm: Double, thresholdBpm: Double): Double =
        (if (this == HIGH) bpm - thresholdBpm else thresholdBpm - bpm).coerceAtLeast(0.0)

    companion object {
        fun of(code: Int): DeviationDirection = entries.firstOrNull { it.code == code } ?: HIGH
    }
}

/** What the wearer was doing during an episode; [code] is the persisted value. */
enum class RestState(val code: Int, @StringRes val label: Int) {
    SLEEP(0, R.string.deviation_state_sleep),
    AWAKE_REST(1, R.string.deviation_state_rest);

    companion object {
        fun of(code: Int): RestState = entries.firstOrNull { it.code == code } ?: AWAKE_REST
    }
}

/** Whether an episode's readings may feed the personal corridors. */
enum class CalibrationUse(@StringRes val label: Int) {
    WAITING_FOR_ANSWER(R.string.deviation_calibration_waiting),
    INCLUDED(R.string.deviation_calibration_included),
    EXCLUDED(R.string.deviation_calibration_excluded),
}

/**
 * The answers offered for an episode; [code] is the persisted value. Only "nothing special" lets an episode into
 * calibration: that is how a real shift of the personal norm gets recorded.
 */
enum class DeviationReason(val code: Int, @StringRes val label: Int, val feedsCalibration: Boolean = false) {
    HEAVY_LOAD(1, R.string.deviation_reason_heavy_load),
    HURRYING(2, R.string.deviation_reason_hurrying),
    STAIRS(3, R.string.deviation_reason_stairs),
    STOOD_UP_OR_MOVED(4, R.string.deviation_reason_stood_up_or_moved),
    UNPLEASANT_TALK(10, R.string.deviation_reason_unpleasant_talk),
    EXCITEMENT(11, R.string.deviation_reason_excitement),
    COFFEE(20, R.string.deviation_reason_coffee),
    ALCOHOL(21, R.string.deviation_reason_alcohol),
    MEDICATION(22, R.string.deviation_reason_medication),
    MEAL(23, R.string.deviation_reason_meal),
    ILLNESS(30, R.string.deviation_reason_illness),
    POOR_SLEEP(31, R.string.deviation_reason_poor_sleep),
    FEELING_UNWELL(32, R.string.deviation_reason_feeling_unwell),
    HEAT(40, R.string.deviation_reason_heat),
    SHOWER_OR_SAUNA(41, R.string.deviation_reason_shower_or_sauna),
    NOTHING_SPECIAL(50, R.string.deviation_reason_nothing_special, feedsCalibration = true),
    OTHER(60, R.string.deviation_reason_other),
    JUST_PUT_WATCH_ON(61, R.string.deviation_reason_just_put_watch_on);

    companion object {
        fun of(code: Int): DeviationReason? = entries.firstOrNull { it.code == code }
    }
}

/** How strongly the wearer felt an episode, an optional part of the answer; [code] is the persisted value. */
enum class DeviationIntensity(val code: Int, @StringRes val label: Int) {
    MILD(1, R.string.deviation_intensity_mild),
    MODERATE(2, R.string.deviation_intensity_moderate),
    STRONG(3, R.string.deviation_intensity_strong);

    companion object {
        fun of(code: Int?): DeviationIntensity? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A stretch outside the corridor at rest, found in the watch's readings; not stored yet. [thresholdBpm] is the corridor
 * edge it crossed, [baselineBpm] the personal norm behind it (null while the fixed fallback applies),
 * [excessBpmMinutes] how far and how long it stayed outside, [wornSince] when the readings before it resumed.
 */
data class DeviationCandidate(
    val direction: DeviationDirection,
    val state: RestState,
    val start: Instant,
    val end: Instant,
    val peakBpm: Double,
    val thresholdBpm: Double,
    val baselineBpm: Double? = null,
    val excessBpmMinutes: Double = 0.0,
    val wornSince: Instant? = null,
) {
    val minor: Boolean get() = DeviationSeverity.isMinor(Duration.between(start, end), direction.excessOf(peakBpm, thresholdBpm))
}

/** Short shallow stretches are kept for the analysis but not asked about: most of them are posture changes. */
object DeviationSeverity {
    /** Preliminary, like every value of the detector. */
    private val MIN_ASKED_DURATION: Duration = Duration.ofMinutes(5)
    private const val MIN_ASKED_EXCESS_BPM = 15.0

    fun isMinor(duration: Duration, peakExcessBpm: Double): Boolean =
        duration < MIN_ASKED_DURATION && peakExcessBpm < MIN_ASKED_EXCESS_BPM
}

/**
 * What surrounded an episode when it was found, stored with it for the analysis on the server; each part is null
 * without data. Everything the server can work out from the uploaded samples itself (RMSSD during and after, ambient
 * temperature, ...) is left to it.
 */
data class DeviationContext(
    val stepsBefore: Double? = null,
    val rmssdBefore: Double? = null,
    val sleepScore: Double? = null,
    val fatiguePercent: Double? = null,
    val lastMedicationAt: Instant? = null,
)

/** A stored episode and the user's answer; no answer ([answeredAt] null) keeps it out of calibration, however old it gets. */
data class DeviationEpisode(
    val id: Long,
    val direction: DeviationDirection,
    val state: RestState,
    val start: Instant,
    val end: Instant,
    val peakBpm: Double,
    val reasons: Set<DeviationReason> = emptySet(),
    val intensity: DeviationIntensity? = null,
    val answeredAt: Instant? = null,
) {
    val waitingForAnswer: Boolean get() = answeredAt == null

    /** A reason that explains the deviation away outweighs "nothing special" picked along with it. */
    val calibrationUse: CalibrationUse
        get() = when {
            waitingForAnswer -> CalibrationUse.WAITING_FOR_ANSWER
            reasons.all { it.feedsCalibration } -> CalibrationUse.INCLUDED
            else -> CalibrationUse.EXCLUDED
        }
}

/** Reasons in the order the screen offers them: most used first, the rest in catalogue order. */
fun reasonsByUse(episodes: List<DeviationEpisode>): List<DeviationReason> {
    val uses = episodes.flatMap { it.reasons }.groupingBy { it }.eachCount()
    return DeviationReason.entries.sortedByDescending { uses[it] ?: 0 }
}

/** The reasons worth a one-tap button: those actually used before, most used first. */
fun quickReasons(episodes: List<DeviationEpisode>, limit: Int): List<DeviationReason> {
    val used = episodes.flatMapTo(mutableSetOf()) { it.reasons }
    return reasonsByUse(episodes).filter { it in used }.take(limit)
}
