package org.sand.ticwatch.phone.medication

/*
 * The app records only the fact and time of a medication intake. Which drugs, doses and schedule the user takes is
 * decided with the physician and managed outside this app (planned: an external model), so no drug names or doses
 * are stored or shown here.
 */

/**
 * Kind of logged intake; [code] is the stored history value (explicit, never `ordinal`). A single anonymous marker:
 * the entry means only "medication was taken at this time".
 */
enum class MedicationDose(val code: Int) {
    INTAKE(1);

    companion object {
        fun of(code: Int): MedicationDose? = entries.firstOrNull { it.code == code }
    }
}
