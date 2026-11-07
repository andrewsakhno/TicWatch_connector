package org.sand.ticwatch.phone.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LIGHT_COLORS: ColorScheme = expressiveLightColorScheme()
private val DARK_COLORS: ColorScheme = darkColorScheme()

/** Wallpaper-derived (Material You) colors arrived in Android 12. */
private const val DYNAMIC_COLOR_MIN_SDK = Build.VERSION_CODES.S

/**
 * The app's Material 3 Expressive theme: follows the system's dark theme setting, like the `DayNight` window theme
 * underneath, takes its palette from the wallpaper where the platform offers one, and uses the expressive (springy)
 * motion scheme for every component.
 */
@Composable
fun TicWatchTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = colorScheme(darkTheme),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

@Composable
private fun colorScheme(darkTheme: Boolean): ColorScheme {
    if (Build.VERSION.SDK_INT < DYNAMIC_COLOR_MIN_SDK) return if (darkTheme) DARK_COLORS else LIGHT_COLORS
    val context = LocalContext.current
    return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}
