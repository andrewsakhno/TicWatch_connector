package org.sand.ticwatch.phone.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Progress sink for one transfer started by [SyncStatusReporter.begin]. */
interface TransferProgress {
    fun update(bytesTransferred: Long)
    fun end()
}

/**
 * Sink for transfer progress. Kept separate from [SyncStatusRepository] (ISP) so
 * transports and message handlers - which only ever report progress - depend on this
 * narrow contract instead of the read side (`transfers`) they never use.
 */
interface SyncStatusReporter {
    fun begin(path: String, direction: SyncDirection, totalBytes: Long? = null): TransferProgress
}

/**
 * Process-wide pub/sub for in-flight phone<->watch transfers. Every component that
 * moves bytes over the link - `TransportFactory`'s transports (sending) and
 * [org.sand.ticwatch.phone.datalayer.PhoneMessageRouter] (receiving) - reports through
 * this single [SyncStatusReporter]. Those components each build their own
 * `AppContainer`, so a plain object is the only thing all of them actually share
 * without introducing a DI framework.
 *
 * Transfers are tracked individually: sensor pushes arrive every few seconds while a
 * multi-minute APK upload runs, and a single shared status let each push's end()
 * wipe the APK's progress.
 */
object SyncStatusRepository : SyncStatusReporter {
    private val nextId = AtomicLong()
    private val _transfers = MutableStateFlow<List<SyncTransfer>>(emptyList())
    val transfers: StateFlow<List<SyncTransfer>> = _transfers.asStateFlow()

    override fun begin(path: String, direction: SyncDirection, totalBytes: Long?): TransferProgress {
        val id = nextId.incrementAndGet()
        val transfer = SyncTransfer(id, path, direction, describeSyncPath(path, direction), bytesTransferred = 0L, totalBytes)
        _transfers.update { it + transfer }
        return object : TransferProgress {
            override fun update(bytesTransferred: Long) = _transfers.update { list ->
                list.map { if (it.id == id) it.copy(bytesTransferred = bytesTransferred) else it }
            }

            override fun end() = _transfers.update { list -> list.filterNot { it.id == id } }
        }
    }
}
