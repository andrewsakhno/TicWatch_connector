package org.sand.ticwatch.watch

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.sand.ticwatch.watch.autoworkout.AutoWorkoutMode
import org.sand.ticwatch.watch.autoworkout.AutoWorkoutSettings
import org.sand.ticwatch.watch.phonedata.PhonePayloadStores
import org.sand.ticwatch.watch.sensors.SensorCollectionService
import org.sand.ticwatch.watch.settings.WatchSettings
import org.sand.ticwatch.watch.settings.WatchSettingsStore
import org.sand.ticwatch.watch.settings.WatchSettingsSync
import org.sand.ticwatch.watch.ui.UiStateStore

/** What the dashboard pages render; the live flows inside ([service], [phonePayloads]) are collected per page. */
data class WatchDashboardUiState(
    val service: SensorCollectionService.LocalBinder? = null,
    val phonePayloads: PhonePayloadStores? = null,
    val autoWorkoutMode: AutoWorkoutMode? = null,
    val settings: WatchSettings? = null,
)

/**
 * State holder of the watch dashboard: the bound collection service, the phone's payload stores and the settings.
 * Store lookups (their first access reads SharedPreferences from disk) and writes run off the main thread; the
 * settings follow their store's flow, so a change from the phone shows up too.
 */
class WatchDashboardStore(
    private val autoWorkoutSettings: AutoWorkoutSettings,
    phonePayloads: () -> PhonePayloadStores,
    settingsStore: () -> WatchSettingsStore,
    private val settingsSync: Lazy<WatchSettingsSync>,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<WatchDashboardUiState>(WatchDashboardUiState(), scope, ioDispatcher) {

    init {
        launchIo({ Triple(autoWorkoutSettings.mode(), phonePayloads(), settingsStore()) }) { result ->
            result.onSuccess { (mode, payloads, settings) ->
                setState { it.copy(autoWorkoutMode = mode, phonePayloads = payloads) }
                observe(settings.settings) { value -> setState { it.copy(settings = value) } }
            }
        }
    }

    fun bind(service: SensorCollectionService.LocalBinder?) = setState { it.copy(service = service) }

    /** Stored and sent to the phone; the state follows through the store's flow. */
    fun changeSettings(transform: (WatchSettings) -> WatchSettings) {
        launchIo({ settingsSync.value.change(transform) }) {}
    }

    fun cycleAutoWorkoutMode() {
        val next = current.autoWorkoutMode?.next() ?: return
        setState { it.copy(autoWorkoutMode = next) }
        launchIo({ autoWorkoutSettings.setMode(next) }) {}
    }
}
