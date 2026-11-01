package org.sand.ticwatch.watch.tile

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.material.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sand.ticwatch.watch.phonedata.PhonePayloadStores
import org.sand.ticwatch.watch.readiness.CardioReadinessSnapshot
import org.sand.ticwatch.watch.readiness.LeveledValue
import org.sand.ticwatch.watch.readiness.readinessValueColor
import org.sand.ticwatch.watch.watchface.WatchFacePalette

/** The last readiness payload as the tiles show it: [stale] greys it out, a null [snapshot] shows dashes. */
data class TileReadiness(val snapshot: CardioReadinessSnapshot?, val stale: Boolean)

/** Reads the last `/insights/readiness` payload; first access of the stores reads their preferences file. */
suspend fun loadTileReadiness(context: Context): TileReadiness {
    val snapshot = withContext(Dispatchers.IO) { PhonePayloadStores.get(context).cardioReadiness.latest.value }
    return TileReadiness(snapshot, snapshot?.isStale(System.currentTimeMillis()) ?: true)
}

/** Renders one readiness value as text, coloured by its level; grey when stale, dashes when missing. */
class ReadinessValueText(private val context: Context) {

    fun build(value: LeveledValue?, stale: Boolean, typography: Int): LayoutElement {
        val color = readinessValueColor(value, stale, WatchFacePalette.INTERACTIVE.label)
        return Text.Builder(context, value?.text ?: MISSING)
            .setTypography(typography)
            .setColor(argb(color))
            .build()
    }

    private companion object {
        const val MISSING = "--"
    }
}
