package org.sand.ticwatch.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

/**
 * The watch's Wear Material 3 Expressive theme for every Compose screen: the system's dynamic palette where the watch
 * offers one (Wear OS 6+), the Material baseline otherwise.
 */
@Composable
fun TicWatchTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = remember(context) { dynamicColorScheme(context) ?: ColorScheme() }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
