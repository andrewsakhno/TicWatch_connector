package org.sand.ticwatch.watch.tile

import android.text.format.DateFormat
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import org.sand.ticwatch.watch.MainActivity
import org.sand.ticwatch.watch.watchface.BoundLatestReadings
import org.sand.ticwatch.watch.watchface.ReadingText
import org.sand.ticwatch.watch.watchface.WatchFacePalette
import java.util.Date

/**
 * Tile with the live heart rate and ambient temperature of [SensorCollectionService][
 * org.sand.ticwatch.watch.sensors.SensorCollectionService], read over its local binder like the
 * watch face. Tapping it opens [MainActivity].
 */
class TicWatchTileService : SuspendingTileService() {

    private val readings by lazy { BoundLatestReadings(this) }
    private val layout by lazy { ReadingsTileLayout(this) }

    override fun onCreate() {
        super.onCreate()
        readings.bind()
    }

    override fun onDestroy() {
        readings.unbind()
        super.onDestroy()
    }

    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        // The first request after a cold start races the bind; a short wait beats a tile of dashes.
        readings.awaitConnected(BIND_TIMEOUT_MS)
        val palette = WatchFacePalette.INTERACTIVE
        val content = layout.build(
            deviceParameters = requestParams.deviceConfiguration,
            updatedAt = DateFormat.getTimeFormat(this).format(Date()),
            readings = listOf(
                TileReading(
                    ReadingsTileLayout.ICON_HEART,
                    ReadingText.heartRate(readings),
                    ReadingText.HEART_RATE_UNIT,
                    palette.heart,
                ),
                TileReading(
                    ReadingsTileLayout.ICON_THERMOMETER,
                    ReadingText.ambientTemperature(readings),
                    ReadingText.TEMPERATURE_UNIT,
                    palette.thermometer,
                ),
            ),
            onClick = openApp(),
        )
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(content))
            .build()
    }

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources =
        layout.resources(RESOURCES_VERSION)

    /** Swiping to the tile shows fresh readings instead of waiting out the freshness interval. */
    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        getUpdater(this).requestUpdate(TicWatchTileService::class.java)
    }

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
        const val BIND_TIMEOUT_MS = 1_500L
        const val FRESHNESS_MS = 5 * 60 * 1_000L
    }
}
