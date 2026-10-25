package org.sand.ticwatch.phone.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sink for transfer progress. Kept separate from [SyncStatusRepository] (ISP) so
 * transports and message handlers - which only ever report progress - depend on this
 * narrow contract instead of the read side (`status`) they never use.
 */
interface SyncStatusReporter {
    fun begin(direction: SyncDirection, description: String, totalBytes: Long? = null)
    fun updateProgress(bytesTransferred: Long)
    fun end()
}

/**
 * Process-wide pub/sub for in-flight phone<->watch transfers. Every component that
 * moves bytes over the link - `TransportFactory`'s transports (sending) and
 * [org.sand.ticwatch.phone.datalayer.PhoneMessageRouter] (receiving) - reports through
 * this single [SyncStatusReporter] so the Dashboard can show one live indicator no
 * matter which Service, Activity, or Worker is currently talking to the watch. Those
 * components each build their own `AppContainer`, so a plain object is the only thing
 * all of them actually share without introducing a DI framework.
 */
object SyncStatusRepository : SyncStatusReporter {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    override fun begin(direction: SyncDirection, description: String, totalBytes: Long?) {
        _status.value = SyncStatus.InProgress(direction, description, bytesTransferred = 0L, totalBytes = totalBytes)
    }

    override fun updateProgress(bytesTransferred: Long) {
        (_status.value as? SyncStatus.InProgress)?.let {
            _status.value = it.copy(bytesTransferred = bytesTransferred)
        }
    }

    override fun end() {
        _status.value = SyncStatus.Idle
    }
}
