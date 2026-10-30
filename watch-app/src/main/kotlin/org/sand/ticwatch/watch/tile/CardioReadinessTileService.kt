package org.sand.ticwatch.watch.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sand.ticwatch.watch.MainActivity
import org.sand.ticwatch.watch.phonedata.PhonePayloadStores

/**
 * Tile with the phone's cardio readiness (score, blood pressure, pulse) from the last `/insights/readiness`
 * payload; [PhonePayloadStores] asks for an update whenever a new one arrives. Tapping it opens [MainActivity].
 */
class CardioReadinessTileService : SuspendingTileService() {

    private val layout by lazy { CardioReadinessTileLayout(this) }

    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        // First access of the stores reads their preferences file.
        val snapshot = withContext(Dispatchers.IO) { PhonePayloadStores.get(applicationContext).cardioReadiness.latest.value }
        val stale = snapshot == null || System.currentTimeMillis() - snapshot.atEpochMs > STALE_AFTER_MS
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout.build(snapshot, stale, openApp())))
            .build()
    }

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources =
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()

    private fun openApp(): ModifiersBuilders.Clickable = ModifiersBuilders.Clickable.Builder()
        .setId(CLICK_OPEN_APP)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build(),
                )
                .build(),
        )
        .build()

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val CLICK_OPEN_APP = "open_app"

        /** Re-render now and then so the values grey out once stale even without new payloads. */
        const val FRESHNESS_MS = 30 * 60 * 1_000L

        /** The phone re-sends after every sensor sync (30 min); older than this means the link is down. */
        const val STALE_AFTER_MS = 3 * 60 * 60 * 1_000L
    }
}
