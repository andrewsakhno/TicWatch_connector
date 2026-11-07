package org.sand.ticwatch.watch.tile

import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import org.sand.ticwatch.watch.R

/**
 * The domed-glass backdrop shared by every tile, matching the app's
 * [DomedDialSurface][org.sand.ticwatch.watch.ui.DomedDialSurface]. Protolayout has no radial gradients, so the dome
 * is a pre-drawn vector stretched under the content.
 */
object DomedTileBackdrop {

    private const val RESOURCE_ID = "dome_background"

    /** A full-screen box with the dome under [content]; tapping anywhere fires [onClick]. */
    fun around(content: LayoutElement, onClick: ModifiersBuilders.Clickable): LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(onClick).build())
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(RESOURCE_ID)
                    .setWidth(expand())
                    .setHeight(expand())
                    .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FILL_BOUNDS)
                    .build(),
            )
            .addContent(content)
            .build()

    /** Registers the dome image with a tile's resources. */
    fun addTo(resources: ResourceBuilders.Resources.Builder): ResourceBuilders.Resources.Builder =
        resources.addIdToImageMapping(
            RESOURCE_ID,
            ResourceBuilders.ImageResource.Builder()
                .setAndroidResourceByResId(
                    ResourceBuilders.AndroidImageResourceByResId.Builder()
                        .setResourceId(R.drawable.tile_dome_background)
                        .build(),
                )
                .build(),
        )
}
