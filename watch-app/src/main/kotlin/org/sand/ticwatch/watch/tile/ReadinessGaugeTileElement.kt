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
 * The dashboard's two-ring gauge as a tile element: cardio readiness on the outer ring, load readiness on the inner
 * one, both values in the middle. Same open arc as `CardioReadinessPage`, which a tile can only draw with arc lines.
 */
class ReadinessGaugeTileElement(context: Context) {

    private val palette = WatchFacePalette.INTERACTIVE
    private val valueText = ReadinessValueText(context)

    fun build(readiness: TileReadiness): LayoutElement {
        val (snapshot, stale) = readiness
        val values = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(valueText.build(snapshot?.score, stale, Typography.TYPOGRAPHY_TITLE3))
            .addContent(valueText.build(snapshot?.loadReadiness, stale, Typography.TYPOGRAPHY_TITLE3))
            .build()
        return ring(GAUGE_SIZE_DP, snapshot?.scorePercent, snapshot?.score, stale)
            .addContent(
                ring(GAUGE_SIZE_DP - 2 * (STROKE_DP + RING_GAP_DP), snapshot?.loadReadinessPercent, snapshot?.loadReadiness, stale)
                    .addContent(values)
                    .build(),
            )
            .build()
    }

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

        private const val STROKE_DP = 6f
        private const val RING_GAP_DP = 2f
        private const val MAX_PERCENT = 100f
        private const val SWEEP_DEGREES = 270f

        /** Lower left; a tile's angles count clockwise from 12 o'clock. */
        private const val START_DEGREES = 225f
    }
}
