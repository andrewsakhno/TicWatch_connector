package org.sand.ticwatch.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import org.sand.ticwatch.phone.text.UiText
import org.sand.ticwatch.phone.text.asString
import org.sand.ticwatch.phone.ui.UiStateStore
import org.sand.ticwatch.phone.ui.rememberStore
import org.sand.ticwatch.phone.watchbattery.WatchBatteryStatus
import org.sand.ticwatch.phone.watchbattery.WatchBatteryStore

/** What the title bar shows; [level] null = the watch never reported. */
data class WatchBatteryIndicatorState(
    val level: WatchBatteryStatus? = null,
    val stale: Boolean = true,
    val age: UiText? = null,
)

/** Follows the stored watch battery and re-evaluates its age each minute, so it greys out once stale. */
class WatchBatteryIndicatorStore(
    store: () -> WatchBatteryStore,
    private val clock: Clock,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<WatchBatteryIndicatorState>(WatchBatteryIndicatorState(), scope, ioDispatcher) {
    init {
        // The store's first access reads its preferences file, so it is resolved upstream on the IO dispatcher.
        val status = flow { store().status.collect { emit(it) } }
        observe(status.combine(minuteTicks()) { level, _ -> level }) { level ->
            val now = clock.instant()
            setState { WatchBatteryIndicatorState(level, level?.isStale(now) ?: true, level?.ageLabel(now)) }
        }
    }

    private fun minuteTicks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK.toMillis())
        }
    }

    private companion object {
        val TICK: Duration = Duration.ofMinutes(1)
    }
}

/** Battery glyph filled to the watch's level plus "64%"; greyed out when the last report is stale. */
@Composable
fun WatchBatteryIndicator(appContainer: AppContainer, clock: Clock) {
    val store = rememberStore(appContainer) { scope, io ->
        WatchBatteryIndicatorStore({ appContainer.watchBatteryStore }, clock, scope, io)
    }
    val state by store.state.collectAsState()
    val level = state.level
    val color = if (state.stale) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = STALE_ALPHA) else levelColor(level)
    val description = level?.let { stringResource(R.string.watch_battery, it.levelLabel(), state.age?.asString().orEmpty()) }
        ?: stringResource(R.string.watch_battery_unknown)

    Row(
        modifier = Modifier.padding(end = 12.dp).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BatteryGlyph(fraction = (level?.percent ?: 0) / PERCENT, color = color)
        Spacer(Modifier.width(4.dp))
        Text(text = level?.levelLabel() ?: MISSING, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun levelColor(level: WatchBatteryStatus?): Color = when {
    level == null -> MaterialTheme.colorScheme.onSurfaceVariant
    level.percent <= LOW_PERCENT -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

/** Horizontal battery outline with a terminal nub, filled from the left by [fraction]. */
@Composable
private fun BatteryGlyph(fraction: Float, color: Color) {
    Canvas(modifier = Modifier.size(width = 22.dp, height = 12.dp)) {
        val stroke = 1.5.dp.toPx()
        val nub = 2.dp.toPx()
        val bodyWidth = size.width - nub
        val corner = CornerRadius(2.dp.toPx())
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(bodyWidth - stroke, size.height - stroke),
            cornerRadius = corner,
            style = Stroke(width = stroke),
        )
        drawRect(color = color, topLeft = Offset(bodyWidth, size.height / 3), size = Size(nub, size.height / 3))
        val inset = stroke * 2
        drawRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size((bodyWidth - inset * 2) * fraction.coerceIn(0f, 1f), size.height - inset * 2),
        )
    }
}

private const val PERCENT = 100f
private const val LOW_PERCENT = 20
private const val STALE_ALPHA = 0.6f
private const val MISSING = "--"
