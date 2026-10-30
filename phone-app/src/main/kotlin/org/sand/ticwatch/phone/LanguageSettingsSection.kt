package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.language.AppLanguage

/** App language picker; the choice re-creates the screen in the new language and is passed on to the watch. */
@Composable
fun LanguageSettingsSection(appContainer: AppContainer) {
    val coroutineScope = rememberCoroutineScope()
    val languageSettings = appContainer.appLanguageSettings
    val current = remember { languageSettings.current() }

    Column {
        Text(text = stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.settings_language_hint))
        SettingDropdown(
            selected = current,
            options = AppLanguage.entries,
            labelOf = { languageLabel(it) },
            enabled = true,
            onSelect = { language ->
                if (language != current) coroutineScope.launch { languageSettings.select(language) }
            },
        )
    }
}

/** Each language in its own name, so it can be found whatever language is active. */
@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.ENGLISH -> LANGUAGE_NAME_ENGLISH
    AppLanguage.RUSSIAN -> LANGUAGE_NAME_RUSSIAN
    AppLanguage.POLISH -> LANGUAGE_NAME_POLISH
}

private const val LANGUAGE_NAME_ENGLISH = "English"
private const val LANGUAGE_NAME_RUSSIAN = "Русский"
private const val LANGUAGE_NAME_POLISH = "Polski"
