package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import org.sand.ticwatch.phone.medication.CardioAdvisor
import org.sand.ticwatch.phone.spaceweather.GeomagneticAlertPolicy
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherStore
import org.sand.ticwatch.phone.ui.LOAD_ONCE
import org.sand.ticwatch.phone.ui.ReloadingStore
import org.sand.ticwatch.phone.ui.rememberStore

/** Opened from a geomagnetic alert notification: the disturbance still ahead (if any) and the personalised advice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardioAdviceBottomSheet(
    store: SpaceWeatherStore,
    policy: GeomagneticAlertPolicy,
    advisor: CardioAdvisor,
    onDismiss: () -> Unit,
) {
    val alertStore = rememberStore(store) { scope, io ->
        ReloadingStore(scope, io, LOAD_ONCE) { store.load()?.let { policy.alertFor(it, Instant.now()) } }
    }
    val alert by alertStore.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(SHEET_PADDING.dp), verticalArrangement = Arrangement.spacedBy(ITEM_SPACING.dp)) {
            Text(text = stringResource(R.string.geomagnetic_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = alert?.let { stringResource(R.string.geomagnetic_peak, it.maxKp, stringResource(it.level.label), formatTime(it.peakAt)) }
                    ?: stringResource(R.string.geomagnetic_none),
                style = MaterialTheme.typography.bodyMedium,
            )
            advisor.advice().forEach { Text(text = stringResource(R.string.bullet, stringResource(it)), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private const val SHEET_PADDING = 24
private const val ITEM_SPACING = 12
