package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.medication.CardioAdvisor
import org.sand.ticwatch.phone.spaceweather.GeomagneticAlert
import org.sand.ticwatch.phone.spaceweather.GeomagneticAlertPolicy
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherStore
import java.time.Instant

/** Opened from a geomagnetic alert notification: the disturbance still ahead (if any) and the personalised advice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardioAdviceBottomSheet(
    store: SpaceWeatherStore,
    policy: GeomagneticAlertPolicy,
    advisor: CardioAdvisor,
    onDismiss: () -> Unit,
) {
    var alert by remember { mutableStateOf<GeomagneticAlert?>(null) }
    LaunchedEffect(Unit) {
        alert = withContext(Dispatchers.IO) { store.load()?.let { policy.alertFor(it, Instant.now()) } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(SHEET_PADDING.dp), verticalArrangement = Arrangement.spacedBy(ITEM_SPACING.dp)) {
            Text(text = "Geomagnetic activity and your heart", style = MaterialTheme.typography.titleLarge)
            Text(
                text = alert?.let { "Peak Kp ${"%.1f".format(it.maxKp)} (${it.level.label}) around ${formatTime(it.peakAt)}" }
                    ?: "No disturbance ahead in the latest forecast.",
                style = MaterialTheme.typography.bodyMedium,
            )
            advisor.advice().forEach { Text(text = "• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private const val SHEET_PADDING = 24
private const val ITEM_SPACING = 12
