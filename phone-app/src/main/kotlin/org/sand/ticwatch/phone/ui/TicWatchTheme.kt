package org.sand.ticwatch.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LIGHT_COLORS: ColorScheme = lightColorScheme()
private val DARK_COLORS: ColorScheme = darkColorScheme()

/**
 * The app's Material theme: follows the system's dark theme setting, like the `DayNight` window theme underneath,
 * so switching the phone to dark recolors every screen without an in-app setting.
 */
@Composable
fun TicWatchTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DARK_COLORS else LIGHT_COLORS, content = content)
}
