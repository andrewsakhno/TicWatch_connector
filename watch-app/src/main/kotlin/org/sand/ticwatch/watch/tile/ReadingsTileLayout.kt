package org.sand.ticwatch.watch.tile

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import org.sand.ticwatch.watch.R
import org.sand.ticwatch.watch.watchface.WatchFacePalette

/** One reading of the tile: its glyph, value text, unit and accent colour. */
data class TileReading(val iconId: String, val value: String, val unit: String, val accent: Int)

/**
 * Builds the tile: refresh time on top, the readiness gauge in the middle with one reading on each side (accent glyph
 * above, unit below), and an "Open" chip. A tile can't scroll, so all of it fits one screen. Colours follow the watch
 * face's interactive palette.
 */
class ReadingsTileLayout(private val context: Context) {

    private val palette = WatchFacePalette.INTERACTIVE
    private val gauge = ReadinessGaugeTileElement(context)

    fun build(
        deviceParameters: DeviceParameters,
        updatedAt: String,
        readiness: TileReadiness,
        readings: List<TileReading>,
        onClick: ModifiersBuilders.Clickable,
    ): LayoutElement {
        val layout = PrimaryLayout.Builder(deviceParameters)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(text(updatedAt, Typography.TYPOGRAPHY_CAPTION1, palette.label))
            .setContent(readingsAroundGauge(readings, readiness))
            .setPrimaryChipContent(
                CompactChip.Builder(context, context.getString(R.string.tile_open), onClick, deviceParameters)
                    .setChipColors(ChipColors.primaryChipColors(CHIP_COLORS))
                    .build(),
            )
            .build()

        // The whole tile opens the app, not only the chip.
        return DomedTileBackdrop.around(layout, onClick)
    }

    fun resources(version: String): ResourceBuilders.Resources = DomedTileBackdrop.addTo(ResourceBuilders.Resources.Builder())
        .setVersion(version)
        .addIdToImageMapping(ICON_HEART, image(R.drawable.ic_tile_heart))
        .addIdToImageMapping(ICON_THERMOMETER, image(R.drawable.ic_tile_thermometer))
        .build()

    /** The first half of [readings] left of the gauge, the rest right of it. */
    private fun readingsAroundGauge(readings: List<TileReading>, readiness: TileReadiness): LayoutElement {
        val row = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        val leftCount = readings.size / 2
        readings.take(leftCount).forEach { row.addContent(readingColumn(it)) }
        row.addContent(gauge.build(readiness))
        readings.drop(leftCount).forEach { row.addContent(readingColumn(it)) }
        return row.build()
    }

    private fun readingColumn(reading: TileReading): LayoutElement = LayoutElementBuilders.Column.Builder()
        .setWidth(dp(READING_WIDTH_DP))
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .addContent(
            LayoutElementBuilders.Image.Builder()
                .setResourceId(reading.iconId)
                .setWidth(dp(ICON_SIZE_DP))
                .setHeight(dp(ICON_SIZE_DP))
                .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(reading.accent)).build())
                .build(),
        )
        .addContent(spacer(ICON_GAP_DP))
        .addContent(text(reading.value, Typography.TYPOGRAPHY_TITLE1, palette.time))
        .addContent(text(reading.unit, Typography.TYPOGRAPHY_CAPTION2, reading.accent))
        .build()

    private fun spacer(heightDp: Float): LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(heightDp)).build()

    private fun text(value: String, typography: Int, color: Int): LayoutElement = Text.Builder(context, value)
        .setTypography(typography)
        .setColor(argb(color))
        .build()

    private fun image(drawableId: Int): ResourceBuilders.ImageResource = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(
            ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(drawableId).build(),
        )
        .build()

    companion object {
        const val ICON_HEART = "heart"
        const val ICON_THERMOMETER = "thermometer"

        /** Narrow enough for two readings plus [ReadinessGaugeTileElement.GAUGE_SIZE_DP] on the smallest round screen. */
        private const val READING_WIDTH_DP = 54f
        private const val ICON_SIZE_DP = 22f
        private const val ICON_GAP_DP = 2f

        private val CHIP_COLORS = Colors(
            WatchFacePalette.INTERACTIVE.date,
            WatchFacePalette.INTERACTIVE.backgroundEdge,
            WatchFacePalette.INTERACTIVE.backgroundCenter,
            WatchFacePalette.INTERACTIVE.time,
        )
    }
}
