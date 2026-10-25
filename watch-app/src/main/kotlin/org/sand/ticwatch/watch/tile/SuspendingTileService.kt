package org.sand.ticwatch.watch.tile

import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * [TileService] whose requests are suspend functions instead of [ListenableFuture]s. Requests
 * run on the main thread (service callbacks land there too) and are cancelled with the service.
 */
abstract class SuspendingTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    protected abstract suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile

    protected abstract suspend fun resourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ResourceBuilders.Resources

    final override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = scope.future { tileRequest(requestParams) }

    final override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future { resourcesRequest(requestParams) }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
