package org.sand.ticwatch.phone.medication

/** Personalised advice for a geomagnetically disturbed day: general cardio points plus one per drug class in the therapy. */
class CardioAdvisor(private val regimen: MedicationRegimen) {

    fun advice(): List<String> {
        val medications = MedicationDose.entries.flatMap(regimen::medications)
        val perClass = DrugClass.entries.mapNotNull { drugClass ->
            val taking = medications.filter { drugClass in it.drugClasses }
            if (taking.isEmpty()) null else classAdvice(drugClass, taking)
        }
        return listOf(CUFF_CHECK) + perClass + listOf(LIMIT_EXERCISE)
    }

    private fun classAdvice(drugClass: DrugClass, taking: List<Medication>): String? {
        val names = taking.joinToString(" and ") { it.name }
        return when (drugClass) {
            DrugClass.THIAZIDE_LIKE_DIURETIC -> {
                val ingredients = taking.flatMap { it.ingredients }.filter { it.drugClass == drugClass }.joinToString { it.name }
                "Keep your fluid intake up: $names contains the diuretic $ingredients - avoid dehydration."
            }
            DrugClass.BETA_BLOCKER -> "Take your morning $names on time - it keeps the heart rate down."
            // Covered by the cuff check: no separate advice for ACE inhibitors.
            DrugClass.ACE_INHIBITOR -> null
        }
    }

    private companion object {
        const val CUFF_CHECK = "Check your blood pressure with the cuff in the morning and in the evening."
        const val LIMIT_EXERCISE = "Avoid intense aerobic training on days of peak Kp."
    }
}
