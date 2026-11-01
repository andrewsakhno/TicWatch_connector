package org.sand.ticwatch.watch

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.readiness.CardioReadinessSnapshot
import org.sand.ticwatch.watch.readiness.LeveledValue
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
private val GAUGE_SIZE = 100.dp
private val GAUGE_STROKE = 7.dp
private val GAUGE_RING_GAP = 3.dp
private val VITALS_GAP = 12.dp

/** Dashboard's first page: the phone Advisor's gauge (cardio readiness outside, load readiness inside), the advice, then the latest pressure and pulse. */
@Composable
internal fun CardioReadinessPage(readiness: StateFlow<CardioReadinessSnapshot?>?) {
    val snapshot by (readiness ?: NO_READINESS).collectAsState()
    val current = snapshot
    val stale = current?.isStale(System.currentTimeMillis()) ?: true
    PageColumn {
        val cardio = GaugeRing(current?.scorePercent, valueColor(current?.score, stale))
        val load = GaugeRing(current?.loadReadinessPercent, valueColor(current?.loadReadiness, stale))
        DualRingGauge(cardio, load)
        RingLabel(stringResource(R.string.gauge_cardio), current?.stateLabel, cardio.color)
        RingLabel(stringResource(R.string.gauge_load), current?.fatigueLabel, load.color)
        current?.advice?.let {
            Text(
                it,
                style = MaterialTheme.typography.caption3,
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

/** A 0..100 ring of the gauge; a null [percent] leaves it empty. */
private data class GaugeRing(val percent: Int?, val color: Color)

/** Same open rings as the phone's gauge: they start lower left and run clockwise over the top, [outer]'s value on top in the middle. */
@Composable
private fun DualRingGauge(outer: GaugeRing, inner: GaugeRing) {
    val outerProgress = animatedProgress(outer.percent)
    val innerProgress = animatedProgress(inner.percent)
    val track = MaterialTheme.colors.surface
    Box(modifier = Modifier.size(GAUGE_SIZE), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(GAUGE_SIZE)) {
            val strokeWidth = GAUGE_STROKE.toPx()
            val outerInset = strokeWidth / 2
            drawRing(outer.color, track, outerProgress, outerInset, strokeWidth)
            drawRing(inner.color, track, innerProgress, outerInset + strokeWidth + GAUGE_RING_GAP.toPx(), strokeWidth)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RingPercent(outer)
            RingPercent(inner)
        }
    }
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
private fun RingPercent(ring: GaugeRing) {
    Text(
        ring.percent?.let { stringResource(R.string.readiness_percent, it) } ?: ReadingText.NO_VALUE,
        style = MaterialTheme.typography.title3,
        color = ring.color,
    )
}

@Composable
private fun RingLabel(title: String, label: String?, color: Color) {
    Text(
        stringResource(R.string.gauge_with_label, title, label ?: ReadingText.NO_VALUE),
        style = MaterialTheme.typography.caption2,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = RING_LABEL_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun VitalText(value: LeveledValue?, stale: Boolean) {
    Text(value?.text ?: ReadingText.NO_VALUE, style = MaterialTheme.typography.caption1, color = valueColor(value, stale))
}

private fun valueColor(value: LeveledValue?, stale: Boolean): Color =
    Color(readinessValueColor(value, stale, WatchFacePalette.INTERACTIVE.label))
