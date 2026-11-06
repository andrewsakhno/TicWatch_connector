package org.sand.ticwatch.watch.tile

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.material.Typography
import org.sand.ticwatch.watch.readiness.LeveledValue
import org.sand.ticwatch.watch.readiness.readinessValueColor
import org.sand.ticwatch.watch.watchface.WatchFacePalette

/**
 * The dashboard's three-ring gauge as a tile element: cardio readiness on the outer ring, load readiness in the middle,
 * nervous tone inside (amber while post-stress, not pulsing - a tile can't animate it), the values in the middle. Same open arc as `CardioReadinessPage`, which a tile can only draw with arc lines.
 */
class ReadinessGaugeTileElement(context: Context) {

    private val palette = WatchFacePalette.INTERACTIVE
    private val valueText = ReadinessValueText(context)

    fun build(readiness: TileReadiness): LayoutElement {
        val (snapshot, stale) = readiness
        val rings = listOf(
            TileRing(snapshot?.scorePercent, snapshot?.score),
            TileRing(snapshot?.loadReadinessPercent, snapshot?.loadReadiness),
            TileRing(snapshot?.autonomicPercent, snapshot?.autonomic),
        )
        val values = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        rings.forEachIndexed { index, ring ->
            val typography = if (index == 0) Typography.TYPOGRAPHY_TITLE3 else Typography.TYPOGRAPHY_CAPTION1
            values.addContent(valueText.build(ring.value, stale, typography))
        }
        // Nested boxes, innermost first, so each ring sits on the edge of its own box around the next one.
        var content: LayoutElement = values.build()
        rings.indices.reversed().forEach { index ->
            val ring = rings[index]
            content = ring(GAUGE_SIZE_DP - 2 * index * (STROKE_DP + RING_GAP_DP), ring.percent, ring.value, stale)
                .addContent(content)
                .build()
        }
        return content
    }

    /** One 0..100 ring: its arc length and the value whose level colours it. */
    private data class TileRing(val percent: Int?, val value: LeveledValue?)

    /** A box of [sizeDp] with its ring on the box's edge; a null or zero [percent] leaves only the track. */
    private fun ring(sizeDp: Float, percent: Int?, value: LeveledValue?, stale: Boolean): LayoutElementBuilders.Box.Builder {
        val box = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(sizeDp))
            .setHeight(dp(sizeDp))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(arc(SWEEP_DEGREES, palette.divider))
        if (percent != null && percent > 0) {
            box.addContent(arc(SWEEP_DEGREES * percent / MAX_PERCENT, readinessValueColor(value, stale, palette.label)))
        }
        return box
    }

    private fun arc(lengthDegrees: Float, color: Int): LayoutElement = LayoutElementBuilders.Arc.Builder()
        .setAnchorAngle(degrees(START_DEGREES))
        .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
        .addContent(
            LayoutElementBuilders.ArcLine.Builder()
                .setLength(degrees(lengthDegrees))
                .setThickness(dp(STROKE_DP))
                .setColor(argb(color))
                .build(),
        )
        .build()

    companion object {
        /** Outer width of the gauge, for laying it out next to other content. */
        const val GAUGE_SIZE_DP = 92f

        private const val STROKE_DP = 5f
        private const val RING_GAP_DP = 2f
        private const val MAX_PERCENT = 100f
        private const val SWEEP_DEGREES = 270f

        /** Lower left; a tile's angles count clockwise from 12 o'clock. */
        private const val START_DEGREES = 225f
    }
}
