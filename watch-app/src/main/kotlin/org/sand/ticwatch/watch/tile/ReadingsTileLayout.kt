package org.sand.ticwatch.watch.tile

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
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
 * Builds the tile: refresh time on top, the readings side by side with an accent glyph and
 * unit under each, and an "Open" chip. Colours follow the watch face's interactive palette.
 */
class ReadingsTileLayout(private val context: Context) {

    private val palette = WatchFacePalette.INTERACTIVE

    fun build(
        deviceParameters: DeviceParameters,
        updatedAt: String,
        readings: List<TileReading>,
        onClick: ModifiersBuilders.Clickable,
    ): LayoutElement {
        val layout = PrimaryLayout.Builder(deviceParameters)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(text(updatedAt, Typography.TYPOGRAPHY_CAPTION1, palette.label))
            .setContent(readingsRow(readings))
            .setPrimaryChipContent(
                CompactChip.Builder(context, context.getString(R.string.tile_open), onClick, deviceParameters)
                    .setChipColors(ChipColors.primaryChipColors(CHIP_COLORS))
                    .build(),
            )
            .build()

        // The whole tile opens the app, not only the chip.
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(onClick).build())
            .addContent(layout)
            .build()
    }

    fun resources(version: String): ResourceBuilders.Resources = ResourceBuilders.Resources.Builder()
        .setVersion(version)
        .addIdToImageMapping(ICON_HEART, image(R.drawable.ic_tile_heart))
        .addIdToImageMapping(ICON_THERMOMETER, image(R.drawable.ic_tile_thermometer))
        .build()

    private fun readingsRow(readings: List<TileReading>): LayoutElement {
        val row = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        readings.forEachIndexed { index, reading ->
            if (index > 0) row.addContent(divider())
            row.addContent(readingColumn(reading))
        }
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
        .addContent(text(reading.value, Typography.TYPOGRAPHY_DISPLAY3, palette.time))
        .addContent(text(reading.unit, Typography.TYPOGRAPHY_CAPTION2, reading.accent))
        .build()

    private fun divider(): LayoutElement = LayoutElementBuilders.Box.Builder()
        .setWidth(dp(DIVIDER_WIDTH_DP))
        .setHeight(dp(DIVIDER_HEIGHT_DP))
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(palette.divider)).build())
                .build(),
        )
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

        private const val READING_WIDTH_DP = 64f
        private const val ICON_SIZE_DP = 22f
        private const val ICON_GAP_DP = 2f
        private const val DIVIDER_WIDTH_DP = 1f
        private const val DIVIDER_HEIGHT_DP = 52f

        private val CHIP_COLORS = Colors(
            WatchFacePalette.INTERACTIVE.date,
            WatchFacePalette.INTERACTIVE.backgroundEdge,
            WatchFacePalette.INTERACTIVE.backgroundCenter,
            WatchFacePalette.INTERACTIVE.time,
        )
    }
}
