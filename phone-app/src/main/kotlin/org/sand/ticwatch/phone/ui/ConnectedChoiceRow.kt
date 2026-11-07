package org.sand.ticwatch.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

/**
 * Material 3 Expressive connected button group for picking one of a few options: the buttons share the row width,
 * the outer corners stay round and the chosen one morphs into a pill. [onSelect] receives the tapped option, also when
 * it is already [selected], so a caller that allows "none" can clear it there.
 */
@Composable
fun <T> ConnectedChoiceRow(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                enabled = enabled,
                shapes = connectedShapes(index, options.lastIndex),
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Text(text = label(option), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun connectedShapes(index: Int, lastIndex: Int): ToggleButtonShapes = when (index) {
    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}
