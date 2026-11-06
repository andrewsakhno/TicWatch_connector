package org.sand.ticwatch.phone

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.sand.ticwatch.phone.datalayer.WatchWorkoutCommands
import org.sand.ticwatch.phone.datalayer.WatchWorkoutOutcome
import org.sand.ticwatch.phone.route.WatchWorkoutActivity
import org.sand.ticwatch.phone.route.WatchWorkoutActivityStore
import org.sand.ticwatch.phone.text.UiText
import org.sand.ticwatch.phone.text.uiText
import org.sand.ticwatch.phone.ui.UiStateStore

data class WatchWorkoutUiState(
    val sending: Boolean = false,
    val status: UiText? = null,
    val error: UiText? = null,
    /** Last reported watch workout state (`/workout/state` or a command's answer). */
    val activity: WatchWorkoutActivity? = null,
)

/** State of the workout card: whether a workout runs on the watch, one command at a time, then what the watch answered. */
class WatchWorkoutStore(
    private val commands: WatchWorkoutCommands,
    private val activityStore: WatchWorkoutActivityStore,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<WatchWorkoutUiState>(WatchWorkoutUiState(), scope, ioDispatcher) {

    init {
        observe(activityStore.activity) { activity -> setState { it.copy(activity = activity) } }
    }

    fun start() = send { commands.startWatchWorkout() }

    fun end() = send { commands.endWatchWorkout() }

    private fun send(command: suspend () -> Result<WatchWorkoutOutcome>) {
        if (current.sending) return
        setState { it.copy(sending = true, status = null, error = null) }
        launchIo({ command().getOrThrow().also { outcome -> outcome.runningAfter?.let(activityStore::markActive) } }) { result ->
            setState { previous ->
                result.fold(
                    onSuccess = { outcome ->
                        if (outcome.refused) previous.copy(sending = false, error = uiText(outcome.label))
                        else previous.copy(sending = false, status = uiText(outcome.label))
                    },
                    onFailure = { previous.copy(sending = false, error = uiText(R.string.watch_unreachable, it.message.orEmpty())) },
                )
            }
        }
    }
}
