package org.sand.ticwatch.watch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.wear.compose.material3.MaterialTheme

/**
 * Dark "domed glass" backdrop in the style of recent Pixel Watch screens: a faint theme-tinted glow lit from above,
 * a soft specular highlight near the top and a rim that falls off to black over the content, so the flat screen
 * reads as a convex dial.
 */
@Composable
fun DomedDialSurface(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val glow = lerp(Color.Black, MaterialTheme.colorScheme.primaryContainer, DomedDial.GLOW_TINT)
    Box(modifier = modifier.fillMaxSize().domedDial(glow), contentAlignment = contentAlignment, content = content)
}

/** Draws the dome's glow and highlight behind the content and its darkened rim over it. */
fun Modifier.domedDial(glow: Color): Modifier = this
    .drawBehind {
        drawRect(Color.Black)
        drawGlow(glow)
        drawHighlight()
    }
    .drawWithContent {
        drawContent()
        drawRim()
    }

private fun DrawScope.drawGlow(glow: Color) {
    val radius = size.minDimension * DomedDial.GLOW_REACH
    val center = Offset(center.x, center.y - size.height * DomedDial.GLOW_OFFSET)
    drawRect(Brush.radialGradient(listOf(glow, Color.Black), center, radius))
}

private fun DrawScope.drawHighlight() {
    val highlightCenter = Offset(center.x, size.height * DomedDial.HIGHLIGHT_Y)
    val radius = size.minDimension * DomedDial.HIGHLIGHT_REACH
    // Squash the round gradient into a wide, flat reflection like light on curved glass.
    scale(scaleX = 1f, scaleY = DomedDial.HIGHLIGHT_FLATTEN, pivot = highlightCenter) {
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = DomedDial.HIGHLIGHT_ALPHA), Color.Transparent),
                highlightCenter,
                radius,
            ),
            radius,
            highlightCenter,
        )
    }
}

private fun DrawScope.drawRim() {
    val radius = size.minDimension / 2
    drawRect(
        Brush.radialGradient(
            DomedDial.RIM_START to Color.Transparent,
            1f to Color.Black.copy(alpha = DomedDial.RIM_ALPHA),
            center = center,
            radius = radius,
        ),
    )
}

/** Shape of the dome, shared with the tiles' pre-rendered backdrop so both look alike. */
object DomedDial {
    /** How much of the theme's primary container colours the glow. */
    const val GLOW_TINT = 0.32f

    /** Glow radius as a share of the screen's smaller side. */
    const val GLOW_REACH = 0.75f

    /** Upward shift of the glow centre as a share of the screen height (light from above). */
    const val GLOW_OFFSET = 0.12f

    const val HIGHLIGHT_Y = 0.16f
    const val HIGHLIGHT_REACH = 0.42f
    const val HIGHLIGHT_FLATTEN = 0.4f
    const val HIGHLIGHT_ALPHA = 0.09f

    /** Share of the radius where the rim starts to darken. */
    const val RIM_START = 0.8f
    const val RIM_ALPHA = 0.65f
}
