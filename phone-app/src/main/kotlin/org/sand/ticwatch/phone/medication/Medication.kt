package org.sand.ticwatch.phone.medication

/** Pharmacological class of an active ingredient; drives the class-specific advice ([CardioAdvisor]). */
enum class DrugClass(val label: String) {
    BETA_BLOCKER("beta blocker"),
    ACE_INHIBITOR("ACE inhibitor"),
    THIAZIDE_LIKE_DIURETIC("thiazide-like diuretic"),
}

/** Part of a tablet taken per dose. */
enum class TabletFraction(val value: Double, val label: String) {
    QUARTER(0.25, "1/4"),
    HALF(0.5, "1/2"),
    WHOLE(1.0, "1"),
}

/** One active ingredient and its amount in a whole tablet. */
data class ActiveIngredient(val name: String, val tabletMg: Double, val drugClass: DrugClass)

/** A tablet the user takes [fraction] of per dose. */
data class Medication(val name: String, val ingredients: List<ActiveIngredient>, val fraction: TabletFraction) {
    val drugClasses: Set<DrugClass> get() = ingredients.mapTo(linkedSetOf()) { it.drugClass }

    fun doseMg(ingredient: ActiveIngredient): Double = ingredient.tabletMg * fraction.value

    /** "1/4 tablet (nebivolol 1.25 mg)". */
    fun doseDescription(): String =
        "${fraction.label} tablet (" + ingredients.joinToString(" + ") { "${it.name} ${formatMg(doseMg(it))} mg" } + ")"

    private fun formatMg(mg: Double): String = "%.3f".format(mg).trimEnd('0').trimEnd('.', ',')
}

/** Kind of logged intake; [code] is the stored history value (explicit, never `ordinal`). */
enum class MedicationDose(val code: Int, val label: String) {
    MORNING(1, "Morning dose");

    companion object {
        fun of(code: Int): MedicationDose? = entries.firstOrNull { it.code == code }
    }
}

/** The user's therapy: what one logged [MedicationDose] consists of. */
interface MedicationRegimen {
    fun medications(dose: MedicationDose): List<Medication>
}

/** The preset antihypertensive therapy (no editing UI yet): Nedal 1/4 tablet and Noliprel Forte 1/2 tablet each morning. */
class PresetMedicationRegimen : MedicationRegimen {
    override fun medications(dose: MedicationDose): List<Medication> = when (dose) {
        MedicationDose.MORNING -> MORNING_DOSE
    }

    private companion object {
        val NEDAL = Medication(
            name = "Nedal",
            ingredients = listOf(ActiveIngredient("nebivolol", tabletMg = 5.0, DrugClass.BETA_BLOCKER)),
            fraction = TabletFraction.QUARTER,
        )
        val NOLIPREL_FORTE = Medication(
            name = "Noliprel Forte",
            ingredients = listOf(
                ActiveIngredient("perindopril", tabletMg = 5.0, DrugClass.ACE_INHIBITOR),
                ActiveIngredient("indapamide", tabletMg = 1.25, DrugClass.THIAZIDE_LIKE_DIURETIC),
            ),
            fraction = TabletFraction.HALF,
        )
        val MORNING_DOSE = listOf(NEDAL, NOLIPREL_FORTE)
    }
}
