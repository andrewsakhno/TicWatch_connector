package org.sand.ticwatch.phone.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.health.HeartRateZone

/** Proportions of a [HeartRateZoneChart]: the hatched bars' [barsHeight] and the color strip's [stripHeight] under them. */
enum class ZoneChartSize(val barsHeight: Dp, val stripHeight: Dp) {
    REGULAR(72.dp, 14.dp),
    COMPACT(40.dp, 8.dp),
}

private val ZONE_COLORS = mapOf(
    HeartRateZone.ZONE_0 to Color(0xFF9E9E9E),
    HeartRateZone.ZONE_1 to Color(0xFF03A9F4),
    HeartRateZone.ZONE_2 to Color(0xFF00C853),
    HeartRateZone.ZONE_3 to Color(0xFFFFB300),
    HeartRateZone.ZONE_4 to Color(0xFFFF6D00),
    HeartRateZone.ZONE_5 to Color(0xFFFF1744),
)
private val HATCH_LINE = 2.dp
private val HATCH_STEP = 4.dp

/**
 * Garmin-style zone view: a strip of the zone colors (grey = below zone 1) with a hatched bar above each zone, as tall as its share
 * of the busiest zone's [minutes], and the minutes under it.
 */
@Composable
fun HeartRateZoneChart(minutes: Map<HeartRateZone, Double>, size: ZoneChartSize, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(size.barsHeight + size.stripHeight)) {
            val zones = HeartRateZone.entries
            val columnWidth = this.size.width / zones.size
            val barsBottom = size.barsHeight.toPx()
            val line = HATCH_LINE.toPx()
            val step = HATCH_STEP.toPx()
            val busiest = minutes.values.maxOrNull() ?: 0.0
            zones.forEachIndexed { index, zone ->
                val color = ZONE_COLORS.getValue(zone)
                val left = index * columnWidth
                drawRect(color, Offset(left, barsBottom), Size(columnWidth, size.stripHeight.toPx()))
                val zoneMinutes = minutes[zone] ?: 0.0
                if (zoneMinutes <= 0) return@forEachIndexed
                val barTop = barsBottom - maxOf(step, (zoneMinutes / busiest * barsBottom).toFloat())
                var y = barsBottom - step
                while (y >= barTop) {
                    drawRect(color, Offset(left, y), Size(columnWidth, line))
                    y -= step
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            HeartRateZone.entries.forEach { zone ->
                Text(
                    text = stringResource(R.string.unit_minutes, minutes[zone] ?: 0.0),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
