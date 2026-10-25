package org.sand.ticwatch.phone.datalayer

import java.io.File

/** Package name of the watch-app companion this phone deploys/updates via [WatchAppManager]. */
const val WATCH_APP_PACKAGE_NAME = "org.sand.ticwatch.watch"

/** A watch node paired with this phone and reachable over the Wear OS Data Layer. */
data class WatchNode(val id: String, val displayName: String, val isNearby: Boolean)

/**
 * Deploys and removes watch-app builds on paired Wear OS devices, the way `adb install`/
 * `adb uninstall` would, but over the Data Layer instead of USB. Kept as an interface
 * (DIP) so MainActivity/ViewModels depend on this contract rather than the concrete
 * Wearable API client, and so the transport can be swapped or faked in tests.
 */
interface WatchAppManager {
    suspend fun listPairedWatches(): List<WatchNode>
    suspend fun installApp(watchNodeId: String, apk: File, packageName: String): Result<Unit>
    suspend fun uninstallApp(watchNodeId: String, packageName: String): Result<Unit>
}
