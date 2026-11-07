package org.sand.ticwatch.watch

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.readiness.CardioReadinessSnapshot
import org.sand.ticwatch.watch.readiness.LeveledValue
import org.sand.ticwatch.watch.readiness.VitalLevel
import org.sand.ticwatch.watch.readiness.readinessValueColor
import org.sand.ticwatch.watch.watchface.ReadingText
import org.sand.ticwatch.watch.watchface.WatchFacePalette

private val NO_READINESS: StateFlow<CardioReadinessSnapshot?> = MutableStateFlow(null)
private const val MAX_SCORE = 100f
private const val SWEEP_DEGREES = 270f
private const val START_DEGREES = 135f
private const val GAUGE_ANIMATION_MS = 900
private const val ADVICE_MAX_LINES = 2
private const val RING_LABEL_MAX_LINES = 1
private const val PULSE_MS = 800
private const val PULSE_MIN_ALPHA = 0.35f
private val GAUGE_SIZE = 112.dp
private val GAUGE_STROKE = 6.dp
private val GAUGE_RING_GAP = 2.dp
private val VITALS_GAP = 12.dp

/**
 * Dashboard's first page: the phone Advisor's gauge (cardio readiness outside, load readiness in the middle, nervous tone inside,
 * pulsing amber in a post-stress window), the advice - a breathing pause while post-stress - then the latest pressure and pulse.
 */
@Composable
internal fun CardioReadinessPage(readiness: StateFlow<CardioReadinessSnapshot?>?) {
    val snapshot by (readiness ?: NO_READINESS).collectAsState()
    val current = snapshot
    val stale = current?.isStale(System.currentTimeMillis()) ?: true
    PageColumn {
        val cardio = GaugeRing(current?.scorePercent, valueColor(current?.score, stale))
        val load = GaugeRing(current?.loadReadinessPercent, valueColor(current?.loadReadiness, stale))
        val postStress = current?.postStress == true && !stale
        val autonomic = GaugeRing(current?.autonomicPercent, autonomicColor(current?.autonomic, postStress, stale), pulsing = postStress)
        RingGauge(listOf(cardio, load, autonomic))
        RingLabel(stringResource(R.string.gauge_cardio), current?.stateLabel, cardio.color)
        RingLabel(stringResource(R.string.gauge_load), current?.fatigueLabel, load.color)
        RingLabel(stringResource(R.string.gauge_autonomic), current?.autonomicLabel, autonomic.color)
        (if (postStress) stringResource(R.string.advice_breathing_pause) else current?.advice)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyExtraSmall,
                textAlign = TextAlign.Center,
                maxLines = ADVICE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(VITALS_GAP)) {
            VitalText(current?.pressure, stale)
            VitalText(current?.heartRate, stale)
        }
    }
}

/** A 0..100 ring of the gauge; a null [percent] leaves it empty, [pulsing] makes it breathe to draw attention. */
private data class GaugeRing(val percent: Int?, val color: Color, val pulsing: Boolean = false)

/** Same open rings as the phone's gauge, outermost first: they start lower left and run clockwise over the top, the values in the middle. */
@Composable
private fun RingGauge(rings: List<GaugeRing>) {
    val progress = rings.map { animatedProgress(it.percent) }
    val pulse = pulseAlpha()
    val track = MaterialTheme.colorScheme.surfaceContainer
    Box(modifier = Modifier.size(GAUGE_SIZE), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(GAUGE_SIZE)) {
            val strokeWidth = GAUGE_STROKE.toPx()
            val step = strokeWidth + GAUGE_RING_GAP.toPx()
            rings.forEachIndexed { index, ring ->
                val color = if (ring.pulsing) ring.color.copy(alpha = pulse) else ring.color
                drawRing(color, track, progress[index], strokeWidth / 2 + index * step, strokeWidth)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            rings.forEachIndexed { index, ring ->
                RingPercent(ring, if (index == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun pulseAlpha(): Float {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = PULSE_MIN_ALPHA,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    return alpha
}

@Composable
private fun animatedProgress(percent: Int?): Float {
    val progress by animateFloatAsState(
        targetValue = (percent ?: 0) / MAX_SCORE,
        animationSpec = tween(GAUGE_ANIMATION_MS),
        label = "readiness",
    )
    return progress
}

private fun DrawScope.drawRing(color: Color, track: Color, progress: Float, inset: Float, strokeWidth: Float) {
    val topLeft = Offset(inset, inset)
    val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    drawArc(track, START_DEGREES, SWEEP_DEGREES, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    drawArc(color, START_DEGREES, SWEEP_DEGREES * progress, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
}

@Composable
private fun RingPercent(ring: GaugeRing, style: TextStyle) {
    Text(
        ring.percent?.let { stringResource(R.string.readiness_percent, it) } ?: ReadingText.NO_VALUE,
        style = style,
        color = ring.color,
    )
}

@Composable
private fun RingLabel(title: String, label: String?, color: Color) {
    Text(
        stringResource(R.string.gauge_with_label, title, label ?: ReadingText.NO_VALUE),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = RING_LABEL_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun VitalText(value: LeveledValue?, stale: Boolean) {
    Text(value?.text ?: ReadingText.NO_VALUE, style = MaterialTheme.typography.bodyMedium, color = valueColor(value, stale))
}

/** The phone's level colour, amber whatever the level while a post-stress window is open. */
private fun autonomicColor(value: LeveledValue?, postStress: Boolean, stale: Boolean): Color =
    if (postStress) Color(VitalLevel.ELEVATED.argb) else valueColor(value, stale)

private fun valueColor(value: LeveledValue?, stale: Boolean): Color =
    Color(readinessValueColor(value, stale, WatchFacePalette.INTERACTIVE.label))
