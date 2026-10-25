package org.sand.ticwatch.watch.install

import java.io.File

/**
 * Installs/removes APKs on this watch on behalf of phone-app's deploy request. Kept
 * behind an interface (DIP) so WatchDataLayerListenerService depends on this contract,
 * not on PackageInstaller directly.
 */
interface ApkInstaller {
    suspend fun install(apk: File): Result<Unit>
    suspend fun uninstall(packageName: String): Result<Unit>
}
