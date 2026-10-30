package org.sand.ticwatch.watch.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Sets this app's language; the platform persists it and re-creates visible activities. */
fun interface AppLocaleApplier {
    fun apply(languageTag: String)
}

/** [AppLocaleApplier] over the per-app language API (API 33+, the watch's minSdk). */
class LocaleManagerAppLocaleApplier(context: Context) : AppLocaleApplier {

    private val localeManager = context.getSystemService(LocaleManager::class.java)

    override fun apply(languageTag: String) {
        val wanted = if (languageTag == LANGUAGE_SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(languageTag)
        if (localeManager.applicationLocales != wanted) localeManager.applicationLocales = wanted
    }
}

/** Follows the language picked on the phone, which arrives with the synced [WatchSettings]. */
class WatchLanguageFollower(private val languages: Flow<String>, private val applier: AppLocaleApplier) {

    fun bind(scope: CoroutineScope) {
        scope.launch { languages.distinctUntilChanged().collect(applier::apply) }
    }
}
