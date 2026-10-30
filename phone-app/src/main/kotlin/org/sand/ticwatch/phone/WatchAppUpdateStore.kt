package org.sand.ticwatch.phone

import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.sand.ticwatch.phone.datalayer.WATCH_APP_PACKAGE_NAME
import org.sand.ticwatch.phone.datalayer.WatchAppManager
import org.sand.ticwatch.phone.datalayer.WatchNode
import org.sand.ticwatch.phone.ui.UiStateStore
import java.io.File

/** Copies a picked APK into app storage so it can be sent. */
fun interface ApkStager {
    suspend fun stage(uri: Uri): File
}

/** The "Update app on watch" section: paired watches, the user's picks and the running update's outcome. */
data class WatchAppUpdateUiState(
    val watches: List<WatchNode> = emptyList(),
    val selectedWatchId: String? = null,
    val selectedApkUri: Uri? = null,
    val updating: Boolean = false,
    val outcome: WatchAppUpdateOutcome? = null,
) {
    val canUpdate: Boolean get() = selectedApkUri != null && selectedWatchId != null && !updating
}

/** How the last update ended. */
sealed interface WatchAppUpdateOutcome {
    data object Updated : WatchAppUpdateOutcome
    data class Failed(val reason: String?) : WatchAppUpdateOutcome
}

/** State holder of the watch-app update section; lists watches and sends the APK off the main thread. */
class WatchAppUpdateStore(
    private val manager: WatchAppManager,
    private val stager: ApkStager,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<WatchAppUpdateUiState>(WatchAppUpdateUiState(), scope, ioDispatcher) {

    init {
        launchIo(manager::listPairedWatches) { result -> result.onSuccess { list -> setState { it.copy(watches = list) } } }
    }

    fun selectWatch(id: String) = setState { it.copy(selectedWatchId = id) }

    fun selectApk(uri: Uri?) = setState { it.copy(selectedApkUri = uri, outcome = null) }

    fun update() {
        val apkUri = current.selectedApkUri ?: return
        val watchId = current.selectedWatchId ?: return
        setState { it.copy(updating = true, outcome = null) }
        launchIo({ manager.installApp(watchId, stager.stage(apkUri), WATCH_APP_PACKAGE_NAME).getOrThrow() }) { result ->
            val outcome = if (result.isSuccess) WatchAppUpdateOutcome.Updated else WatchAppUpdateOutcome.Failed(result.exceptionOrNull()?.message)
            setState { it.copy(updating = false, outcome = outcome) }
        }
    }
}
