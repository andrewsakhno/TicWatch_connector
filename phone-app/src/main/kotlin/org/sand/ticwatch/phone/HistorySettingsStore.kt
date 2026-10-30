package org.sand.ticwatch.phone

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.sand.ticwatch.phone.ecg.EcgRecordingPurger
import org.sand.ticwatch.phone.ecg.EcgRetention
import org.sand.ticwatch.phone.history.HistoryRetention
import org.sand.ticwatch.phone.history.SensorHistoryMaintenance
import org.sand.ticwatch.phone.history.SensorHistoryPurger
import org.sand.ticwatch.phone.history.SettingStore
import org.sand.ticwatch.phone.history.SleepSessionRetention
import org.sand.ticwatch.phone.ui.UiStateStore

/** Transfer-history settings as shown; null values are still loading. */
data class HistorySettingsUiState(
    val retention: HistoryRetention? = null,
    val sleepRetention: SleepSessionRetention? = null,
    val ecgRetention: EcgRetention? = null,
    val sizeBytes: Long? = null,
    val busy: Boolean = false,
)

/** State holder of the transfer-history settings; the size follows every stored push ([changes]). */
class HistorySettingsStore(
    private val retentionStore: SettingStore<HistoryRetention>,
    private val sleepRetentionStore: SettingStore<SleepSessionRetention>,
    private val ecgRetentionStore: SettingStore<EcgRetention>,
    private val ecgPurger: EcgRecordingPurger,
    private val maintenance: SensorHistoryMaintenance,
    private val purger: SensorHistoryPurger,
    changes: Flow<*>,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<HistorySettingsUiState>(HistorySettingsUiState(), scope, ioDispatcher) {

    init {
        launchIo({ Triple(retentionStore.get(), sleepRetentionStore.get(), ecgRetentionStore.get()) }) { result ->
            result.onSuccess { (retention, sleep, ecg) ->
                setState { it.copy(retention = retention, sleepRetention = sleep, ecgRetention = ecg) }
            }
        }
        observe(changes) { refreshSize() }
    }

    fun setRetention(option: HistoryRetention) {
        setState { it.copy(retention = option) }
        runBusy {
            retentionStore.set(option)
            purger.purge()
        }
    }

    fun setSleepRetention(option: SleepSessionRetention) {
        setState { it.copy(sleepRetention = option) }
        runBusy {
            sleepRetentionStore.set(option)
            purger.purge()
        }
    }

    fun setEcgRetention(option: EcgRetention) {
        setState { it.copy(ecgRetention = option) }
        runBusy {
            ecgRetentionStore.set(option)
            ecgPurger.purge()
        }
    }

    fun clear() = runBusy { maintenance.clear() }

    private fun runBusy(action: suspend () -> Unit) {
        setState { it.copy(busy = true) }
        launchIo(action) {
            setState { it.copy(busy = false) }
            refreshSize()
        }
    }

    private fun refreshSize() {
        launchIo(maintenance::storageSizeBytes) { result -> result.onSuccess { size -> setState { it.copy(sizeBytes = size) } } }
    }
}
