package org.sand.ticwatch.phone.medication

import androidx.annotation.StringRes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.text.UiText
import org.sand.ticwatch.phone.text.uiText

/**
 * Pharmacodynamic phase of the once-daily morning antihypertensive intake by time since the last logged intake;
 * [startsAfter] is where each timed phase begins (null for [NOT_LOGGED]). The intake log is anonymous (see
 * Medication.kt), so the phases describe the morning dose as a whole - typical order of magnitude for once-daily
 * beta blocker + ACE inhibitor/diuretic combinations, not a drug-specific model.
 */
enum class MedicationProtectionPhase(@StringRes val label: Int, val startsAfter: Duration?, val description: UiText) {
    ONSET(R.string.phase_onset, Duration.ZERO, uiText(R.string.phase_onset_desc)),
    PEAK(R.string.phase_peak, Duration.ofHours(1), uiText(R.string.phase_peak_desc)),
    PLATEAU(R.string.phase_plateau, Duration.ofHours(5), uiText(R.string.phase_plateau_desc)),
    WANING(R.string.phase_waning, Duration.ofHours(18), uiText(R.string.phase_waning_desc)),
    OVERDUE(R.string.phase_overdue, Duration.ofHours(24), uiText(R.string.phase_overdue_desc)),
    NOT_LOGGED(R.string.phase_not_logged, null, uiText(R.string.phase_not_logged_desc, MedicationProtectionTracker.INTAKE_LOOKBACK.toDays()));

    companion object {
        /** Timed phase [sinceIntake] after the intake; an intake in the future counts as just taken. */
        fun of(sinceIntake: Duration): MedicationProtectionPhase {
            if (sinceIntake.isNegative) return ONSET
            return entries.last { phase -> phase.startsAfter?.let { sinceIntake >= it } ?: false }
        }
    }
}

/** Current phase plus the intake it is counted from; [sinceIntake] and [lastIntakeAt] are null for NOT_LOGGED. */
data class MedicationProtection(val phase: MedicationProtectionPhase, val lastIntakeAt: Instant?, val sinceIntake: Duration?) {

    /** Time left until the next phase begins, null when the phase is open-ended. */
    val nextPhaseIn: Duration?
        get() {
            val elapsed = sinceIntake ?: return null
            val next = MedicationProtectionPhase.entries.getOrNull(phase.ordinal + 1)?.startsAfter ?: return null
            return next.minus(elapsed).takeIf { !it.isNegative }
        }
}

/** Maps the last intake time to a [MedicationProtection]; pure, so the readiness engine and forecaster share it. */
class MedicationProtectionTracker {

    fun protection(lastIntakeAt: Instant?, at: Instant): MedicationProtection {
        if (lastIntakeAt == null) return MedicationProtection(MedicationProtectionPhase.NOT_LOGGED, null, null)
        val since = Duration.between(lastIntakeAt, at)
        return MedicationProtection(MedicationProtectionPhase.of(since), lastIntakeAt, since)
    }

    companion object {
        /** How far back the last intake is searched; older intakes count as NOT_LOGGED rather than OVERDUE. */
        val INTAKE_LOOKBACK: Duration = Duration.ofDays(7)
    }
}

/** The current [MedicationProtection] from the intake log. */
class MedicationProtectionReader(
    private val intakes: MedicationIntakeLog,
    private val tracker: MedicationProtectionTracker,
    private val clock: Clock,
) {
    suspend fun current(): MedicationProtection {
        val now = clock.instant()
        val last = intakes.intakes(now.minus(MedicationProtectionTracker.INTAKE_LOOKBACK), now.plusMillis(1)).lastOrNull()
        return tracker.protection(last?.takenAt, now)
    }
}
