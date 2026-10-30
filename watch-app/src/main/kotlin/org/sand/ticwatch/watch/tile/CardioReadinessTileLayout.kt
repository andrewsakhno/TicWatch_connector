package org.sand.ticwatch.watch.tile

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import org.sand.ticwatch.watch.readiness.CardioReadinessSnapshot
import org.sand.ticwatch.watch.readiness.LeveledValue
import org.sand.ticwatch.watch.watchface.WatchFacePalette

/**
 * Builds the readiness tile: three bare numbers in a triangle - the readiness score on top, blood pressure and pulse
 * below - each coloured by its level; all grey when [stale], dashes where the phone had no value.
 */
class CardioReadinessTileLayout(private val context: Context) {

    private val palette = WatchFacePalette.INTERACTIVE

    fun build(snapshot: CardioReadinessSnapshot?, stale: Boolean, onClick: ModifiersBuilders.Clickable): LayoutElement {
        val bottomRow = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(value(snapshot?.pressure, stale, Typography.TYPOGRAPHY_DISPLAY3))
            .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(BOTTOM_GAP_DP)).build())
            .addContent(value(snapshot?.heartRate, stale, Typography.TYPOGRAPHY_DISPLAY3))
            .build()
        val triangle = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(value(snapshot?.score, stale, Typography.TYPOGRAPHY_DISPLAY1))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(ROW_GAP_DP)).build())
            .addContent(bottomRow)
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(onClick).build())
            .addContent(triangle)
            .build()
    }

    private fun value(value: LeveledValue?, stale: Boolean, typography: Int): LayoutElement {
        val color = if (value == null || stale) palette.label else value.level.argb
        return Text.Builder(context, value?.text ?: MISSING)
            .setTypography(typography)
            .setColor(argb(color))
            .build()
    }

    private companion object {
        const val MISSING = "--"
        const val ROW_GAP_DP = 10f
        const val BOTTOM_GAP_DP = 20f
    }
}
