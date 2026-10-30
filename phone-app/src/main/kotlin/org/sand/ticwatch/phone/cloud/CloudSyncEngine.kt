package org.sand.ticwatch.phone.cloud

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * One sync round: find the server ([CloudServerLocator]), drain every [CloudUploadChannel], publish the
 * outcome to [status]. Rounds in one process never overlap; a failed batch stays pending for the next.
 */
class CloudSyncEngine(
    private val settings: CloudSettingsStore,
    private val memory: CloudConnectionMemory,
    private val locator: CloudServerLocator,
    private val apis: CloudApiProvider,
    private val channels: List<CloudUploadChannel>,
    private val status: CloudSyncStatusStore,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** Returns true when everything pending reached the server. */
    suspend fun sync(): Boolean = mutex.withLock {
        if (!settings.get().enabled) {
            val pending = pendingCount()
            status.update { it.copy(link = CloudLink.DISABLED, syncing = false, pending = pending) }
            return false
        }
        status.update { it.copy(syncing = true) }
        try {
            val host = locator.locate()
            if (host == null) {
                val pending = pendingCount()
                status.update { it.copy(link = CloudLink.NO_SERVER, syncing = false, pending = pending) }
                return false
            }
            status.update { it.copy(link = CloudLink.CONNECTED, host = host) }
            val api = apis.api(host)
            var uploaded = 0
            for (channel in channels) {
                while (true) {
                    val batch = channel.uploadBatch(api)
                    if (batch == 0) break
                    uploaded += batch
                }
            }
            Log.i(TAG, "Uploaded $uploaded rows to $host")
            val pending = pendingCount()
            status.update {
                it.copy(link = CloudLink.CONNECTED, syncing = false, pending = pending, lastSyncAt = clock.instant())
            }
            true
        } catch (e: CancellationException) {
            status.update { it.copy(syncing = false) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cloud sync failed", e)
            // The host may have moved: the next round rescans.
            memory.lastHost = null
            val pending = runCatching { pendingCount() }.getOrDefault(status.status.value.pending)
            status.update { it.copy(link = CloudLink.NO_SERVER, syncing = false, pending = pending) }
            false
        }
    }

    /** Re-reads the outbox sizes (after new data was stored) without contacting the server. */
    suspend fun refreshPending() {
        val pending = pendingCount()
        status.update { it.copy(pending = pending) }
    }

    private suspend fun pendingCount(): Long = channels.sumOf { it.pendingCount() }

    private companion object {
        const val TAG = "CloudSyncEngine"
    }
}
