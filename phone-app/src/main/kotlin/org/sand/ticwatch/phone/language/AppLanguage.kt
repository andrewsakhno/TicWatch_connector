package org.sand.ticwatch.phone.language

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.watchsettings.LANGUAGE_SYSTEM
import org.sand.ticwatch.phone.watchsettings.WatchSettingsSync

/** Languages the app ships strings for; [tag] is the BCP 47 tag ([LANGUAGE_SYSTEM] = follow the device). */
enum class AppLanguage(val tag: String) {
    SYSTEM(LANGUAGE_SYSTEM),
    ENGLISH("en"),
    RUSSIAN("ru"),
    POLISH("pl");

    companion object {
        fun of(tag: String): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/** Reads and sets this app's own language. */
interface AppLocaleSelector {
    fun current(): AppLanguage
    fun select(language: AppLanguage)
}

/**
 * [AppLocaleSelector] over AppCompat's per-app locales: the platform's per-app language on API 33+ (applies to the
 * whole process and re-creates activities), stored by AppCompat and applied to its activities below that.
 */
class AppCompatLocaleSelector : AppLocaleSelector {

    override fun current(): AppLanguage = AppLanguage.of(AppCompatDelegate.getApplicationLocales().toLanguageTags())

    override fun select(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }
}

/** Switches the phone app's language and hands it to the watch with the synced watch settings. */
class AppLanguageSettings(
    private val selector: AppLocaleSelector,
    private val watchSettingsSync: WatchSettingsSync,
    private val io: CoroutineDispatcher,
) {

    fun current(): AppLanguage = selector.current()

    /** Call on the main thread: switching re-creates the activities, so the watch copy is stored first. */
    suspend fun select(language: AppLanguage) {
        withContext(io) { watchSettingsSync.change { it.copy(language = language.tag) } }
        selector.select(language)
    }
}
