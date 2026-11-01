package org.sand.ticwatch.watch.tile

import android.content.Context
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material.Typography

/**
 * Builds the readiness tile: three bare numbers in a triangle - the readiness score on top, blood pressure and pulse
 * below - each coloured by its level; all grey when stale, dashes where the phone had no value.
 */
class CardioReadinessTileLayout(context: Context) {

    private val valueText = ReadinessValueText(context)

    fun build(readiness: TileReadiness, onClick: ModifiersBuilders.Clickable): LayoutElement {
        val (snapshot, stale) = readiness
        val bottomRow = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(valueText.build(snapshot?.pressure, stale, Typography.TYPOGRAPHY_DISPLAY3))
            .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(BOTTOM_GAP_DP)).build())
            .addContent(valueText.build(snapshot?.heartRate, stale, Typography.TYPOGRAPHY_DISPLAY3))
            .build()
        val triangle = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(valueText.build(snapshot?.score, stale, Typography.TYPOGRAPHY_DISPLAY1))
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

    private companion object {
        const val ROW_GAP_DP = 10f
        const val BOTTOM_GAP_DP = 20f
    }
}
