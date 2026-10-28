package org.sand.ticwatch.phone.medication

import java.time.Clock
import java.time.Instant

/** One logged intake of [dose] at [takenAt]. */
data class MedicationIntake(val dose: MedicationDose, val takenAt: Instant)

/** Logged medication intakes. */
interface MedicationIntakeLog {
    suspend fun record(dose: MedicationDose): Result<MedicationIntake>

    /** Intakes in [from, to), oldest first. */
    suspend fun intakes(from: Instant, to: Instant): List<MedicationIntake>
}

/** [MedicationIntakeLog] over the `medication_intake` table of [CardioDatabase]; an unknown dose code is skipped. */
class RoomMedicationIntakeLog(private val dao: MedicationIntakeDao, private val clock: Clock) : MedicationIntakeLog {

    override suspend fun record(dose: MedicationDose): Result<MedicationIntake> = runCatching {
        val intake = MedicationIntake(dose, clock.instant())
        dao.insert(MedicationIntakeEntity(doseCode = dose.code, takenAtMs = intake.takenAt.toEpochMilli()))
        intake
    }

    override suspend fun intakes(from: Instant, to: Instant): List<MedicationIntake> =
        dao.between(from.toEpochMilli(), to.toEpochMilli()).mapNotNull { row ->
            MedicationDose.of(row.doseCode)?.let { MedicationIntake(it, Instant.ofEpochMilli(row.takenAtMs)) }
        }
}
