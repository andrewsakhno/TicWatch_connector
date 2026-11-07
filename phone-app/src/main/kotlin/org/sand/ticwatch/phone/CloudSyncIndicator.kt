package org.sand.ticwatch.phone

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow
import org.sand.ticwatch.phone.cloud.CloudSyncIndicatorKind
import org.sand.ticwatch.phone.cloud.CloudSyncScheduler
import org.sand.ticwatch.phone.cloud.CloudSyncStatus
import org.sand.ticwatch.phone.cloud.CloudSyncStatusStore
import org.sand.ticwatch.phone.ui.UiStateStore
import org.sand.ticwatch.phone.ui.rememberStore

/** Follows the process-wide cloud status; a tap asks for a sync round now. */
class CloudSyncIndicatorStore(
    store: () -> CloudSyncStatusStore,
    private val scheduler: CloudSyncScheduler,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<CloudSyncStatus>(CloudSyncStatus(), scope, ioDispatcher) {
    init {
        // The store's first access reads its preferences file, so it is resolved upstream on the IO dispatcher.
        observe(flow { store().status.collect { emit(it) } }) { status -> setState { status } }
    }

    fun syncNow() = scheduler.runNow()
}

/** Icon per [CloudSyncIndicatorKind] and the description read out for it. */
private enum class CloudIcon(@DrawableRes val icon: Int, @StringRes val description: Int) {
    OFF(R.drawable.ic_cloud_off, R.string.cloud_status_off),
    NOT_SYNCED(R.drawable.ic_cloud_upload, R.string.cloud_status_not_synced),
    SYNCING(R.drawable.ic_cloud_upload, R.string.cloud_status_syncing),
    SYNCED(R.drawable.ic_cloud_done, R.string.cloud_status_synced),
    ;

    companion object {
        fun of(kind: CloudSyncIndicatorKind): CloudIcon = when (kind) {
            CloudSyncIndicatorKind.OFF -> OFF
            CloudSyncIndicatorKind.NOT_SYNCED -> NOT_SYNCED
            CloudSyncIndicatorKind.SYNCING -> SYNCING
            CloudSyncIndicatorKind.SYNCED -> SYNCED
        }
    }
}

/** Title-bar cloud next to the watch battery: off / not synced / syncing (pulsing) / all synced. */
@Composable
fun CloudSyncIndicator(appContainer: AppContainer) {
    val store = rememberStore(appContainer) { scope, io ->
        CloudSyncIndicatorStore({ appContainer.cloudSyncStatusStore }, appContainer.cloudSyncScheduler, scope, io)
    }
    val state by store.state.collectAsState()
    val kind = state.indicator
    val icon = CloudIcon.of(kind)
    // Runs only while syncing and is read in the layer phase: a per-frame recomposition of the top bar janks every tab.
    val pulse = if (kind == CloudSyncIndicatorKind.SYNCING) cloudPulse() else null
    IconButton(onClick = store::syncNow) {
        Icon(
            painter = painterResource(icon.icon),
            contentDescription = stringResource(icon.description),
            tint = tintOf(kind),
            modifier = Modifier.size(22.dp).graphicsLayer { alpha = pulse?.value ?: 1f },
        )
    }
}

@Composable
private fun cloudPulse(): State<Float> = rememberInfiniteTransition(label = "cloud").animateFloat(
    initialValue = 1f,
    targetValue = PULSE_MIN_ALPHA,
    animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
    label = "cloudPulse",
)

@Composable
private fun tintOf(kind: CloudSyncIndicatorKind): Color = when (kind) {
    CloudSyncIndicatorKind.OFF -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = OFF_ALPHA)
    CloudSyncIndicatorKind.NOT_SYNCED -> MaterialTheme.colorScheme.tertiary
    CloudSyncIndicatorKind.SYNCING -> MaterialTheme.colorScheme.primary
    CloudSyncIndicatorKind.SYNCED -> MaterialTheme.colorScheme.onSurface
}

private const val PULSE_MIN_ALPHA = 0.3f
private const val PULSE_MILLIS = 700
private const val OFF_ALPHA = 0.6f
