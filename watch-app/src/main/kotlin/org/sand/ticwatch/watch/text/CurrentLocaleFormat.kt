package org.sand.ticwatch.watch.text

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * This formatter in the locale the app is shown in right now. A formatter kept in a top-level `val` keeps the locale it
 * was created with, which goes stale when the phone switches the app language (`WatchLanguageFollower`), so day and
 * month names stay in the old language. `withLocale` returns the same instance while the locale is unchanged.
 */
fun DateTimeFormatter.inCurrentLocale(): DateTimeFormatter = withLocale(Locale.getDefault())
