package org.sand.ticwatch.phone

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.sand.ticwatch.phone.health.BiologicalSex
import org.sand.ticwatch.phone.health.PatientNameStore
import org.sand.ticwatch.phone.health.UserProfileStore
import org.sand.ticwatch.phone.ui.UiStateStore

/** The free-text fields of the profile section. */
enum class ProfileTextField { NAME, BIRTH_YEAR, HEIGHT, WEIGHT }

/** The profile section as edited: field texts (also while not yet plausible) and the selected sex. */
data class ProfileUiState(
    val texts: Map<ProfileTextField, String> = emptyMap(),
    val sex: BiologicalSex? = null,
) {
    fun text(field: ProfileTextField): String = texts[field].orEmpty()
}

/** State holder of the profile section: loads the stored profile off the main thread and writes every change through. */
class ProfileSettingsStore(
    private val profile: UserProfileStore,
    private val patientNames: PatientNameStore,
    private val formatMeasurement: (Double) -> String,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<ProfileUiState>(ProfileUiState(), scope, ioDispatcher) {

    init {
        launchIo(::read) { result -> result.onSuccess { stored -> setState { stored } } }
    }

    fun edit(field: ProfileTextField, text: String) = setState { it.copy(texts = it.texts + (field to text)) }

    fun setName(name: String) {
        edit(ProfileTextField.NAME, name)
        persist { patientNames.setPatientName(name) }
    }

    fun setSex(sex: BiologicalSex?) {
        setState { it.copy(sex = sex) }
        persist { profile.setSex(sex) }
    }

    /** Saves a parsed numeric field, e.g. `save { setHeightCm(it) }`. */
    fun save(write: suspend UserProfileStore.() -> Unit) = persist { profile.write() }

    private fun persist(write: suspend () -> Unit) {
        launchIo(write) { result -> result.onFailure { Log.w(TAG, "Saving the profile failed", it) } }
    }

    private suspend fun read() = ProfileUiState(
        texts = mapOf(
            ProfileTextField.NAME to patientNames.patientName().orEmpty(),
            ProfileTextField.BIRTH_YEAR to profile.birthYear()?.toString().orEmpty(),
            ProfileTextField.HEIGHT to profile.heightCm()?.let(formatMeasurement).orEmpty(),
            ProfileTextField.WEIGHT to profile.weightKg()?.let(formatMeasurement).orEmpty(),
        ),
        sex = profile.sex(),
    )

    private companion object {
        const val TAG = "ProfileSettingsStore"
    }
}
