package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Year
import org.sand.ticwatch.phone.health.BiologicalSex
import org.sand.ticwatch.phone.ui.rememberStore

/**
 * Profile data for the profile-dependent estimates: the birth year drives the age-predicted max
 * heart rate behind the VO2 max estimate; birth year, sex, height and weight together the daily
 * basal metabolic rate. Each field is saved as soon as a plausible value is typed; clearing it
 * clears it.
 */
@Composable
fun ProfileSettingsSection(appContainer: AppContainer) {
    val store = rememberStore(appContainer) { scope, io ->
        ProfileSettingsStore(appContainer.userProfileStore, appContainer.patientNameStore, ::formatMeasurement, scope, io)
    }
    val state by store.state.collectAsState()
    val sex = state.sex

    Column(verticalArrangement = Arrangement.spacedBy(FIELD_SPACING.dp)) {
        Text(text = stringResource(R.string.profile_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.text(ProfileTextField.NAME),
            onValueChange = { name -> store.setName(name.take(MAX_NAME_CHARS)) },
            label = { Text(text = stringResource(R.string.profile_name)) },
            supportingText = { Text(text = stringResource(R.string.profile_name_hint)) },
            singleLine = true,
        )
        ProfileNumberField(
            text = state.text(ProfileTextField.BIRTH_YEAR),
            label = stringResource(R.string.profile_birth_year),
            supportingText = stringResource(R.string.profile_birth_year_hint),
            keyboardType = KeyboardType.Number,
            sanitize = { it.filter(Char::isDigit).take(YEAR_DIGITS) },
            parse = ::plausibleBirthYear,
            onTextChange = { store.edit(ProfileTextField.BIRTH_YEAR, it) },
            onValueChange = { year -> store.save { setBirthYear(year) } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(FIELD_SPACING.dp)) {
            BiologicalSex.entries.forEach { option ->
                FilterChip(
                    selected = sex == option,
                    onClick = {
                        store.setSex(option.takeUnless { sex == option })
                    },
                    label = { Text(text = stringResource(SEX_LABELS.getValue(option))) },
                )
            }
        }
        ProfileNumberField(
            text = state.text(ProfileTextField.HEIGHT),
            label = stringResource(R.string.profile_height),
            supportingText = stringResource(R.string.profile_bmr_hint),
            keyboardType = KeyboardType.Decimal,
            sanitize = ::sanitizeDecimal,
            parse = { plausibleMeasurement(it, HEIGHT_CM_RANGE) },
            onTextChange = { store.edit(ProfileTextField.HEIGHT, it) },
            onValueChange = { height -> store.save { setHeightCm(height) } },
        )
        ProfileNumberField(
            text = state.text(ProfileTextField.WEIGHT),
            label = stringResource(R.string.profile_weight),
            supportingText = stringResource(R.string.profile_bmr_hint),
            keyboardType = KeyboardType.Decimal,
            sanitize = ::sanitizeDecimal,
            parse = { plausibleMeasurement(it, WEIGHT_KG_RANGE) },
            onTextChange = { store.edit(ProfileTextField.WEIGHT, it) },
            onValueChange = { weight -> store.save { setWeightKg(weight) } },
        )
    }
}

/** A numeric profile field: saves ([onValueChange]) a parsed value, or null once the field is cleared. */
@Composable
private fun <T : Any> ProfileNumberField(
    text: String,
    label: String,
    supportingText: String,
    keyboardType: KeyboardType,
    sanitize: (String) -> String,
    parse: (String) -> T?,
    onTextChange: (String) -> Unit,
    onValueChange: (T?) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val sanitized = sanitize(raw)
            onTextChange(sanitized)
            val value = parse(sanitized)
            if (sanitized.isEmpty() || value != null) onValueChange(value)
        },
        label = { Text(text = label) },
        isError = text.isNotEmpty() && parse(text) == null,
        supportingText = { Text(text = supportingText) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

private fun plausibleBirthYear(text: String): Int? = text.toIntOrNull()?.takeIf { it in MIN_BIRTH_YEAR..Year.now().value }

private fun plausibleMeasurement(text: String, range: ClosedFloatingPointRange<Double>): Double? =
    text.toDoubleOrNull()?.takeIf { it in range }

/** Digits and at most one decimal separator (a typed comma becomes a dot). */
private fun sanitizeDecimal(text: String): String {
    val normalized = text.replace(',', DECIMAL_SEPARATOR).filter { it.isDigit() || it == DECIMAL_SEPARATOR }
    val separator = normalized.indexOf(DECIMAL_SEPARATOR)
    val sanitized = if (separator < 0) normalized else
        normalized.substring(0, separator + 1) + normalized.substring(separator + 1).replace(DECIMAL_SEPARATOR.toString(), "")
    return sanitized.take(MAX_MEASUREMENT_CHARS)
}

/** Whole values without a trailing ".0". */
private fun formatMeasurement(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private val SEX_LABELS = mapOf(BiologicalSex.MALE to R.string.sex_male, BiologicalSex.FEMALE to R.string.sex_female)
private val HEIGHT_CM_RANGE = 50.0..272.0
private val WEIGHT_KG_RANGE = 20.0..400.0
private const val DECIMAL_SEPARATOR = '.'
private const val MAX_MEASUREMENT_CHARS = 6
private const val MIN_BIRTH_YEAR = 1900
private const val YEAR_DIGITS = 4
private const val FIELD_SPACING = 8
private const val MAX_NAME_CHARS = 80
