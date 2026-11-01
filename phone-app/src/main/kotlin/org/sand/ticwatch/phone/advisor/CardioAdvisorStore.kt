package org.sand.ticwatch.phone.advisor

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import org.sand.ticwatch.phone.ui.UiStateStore

/** Advisor tab state (MVI model). */
data class AdvisorUiState(
    val loading: Boolean = true,
    val readiness: CardioReadiness? = null,
    val error: String? = null,
    val factorsExpanded: Boolean = false,
)

/** User intents of the Advisor tab. */
sealed interface AdvisorIntent {
    data object Refresh : AdvisorIntent
    data object ToggleFactors : AdvisorIntent
}

/**
 * MVI store of the Advisor tab: [dispatch] intents, observe [state]. Loads on [ioDispatcher] inside [scope], so work
 * is cancelled with the owning screen; re-loads by itself whenever [changes] emits (also once at start).
 */
class CardioAdvisorStore(
    private val source: CardioReadinessSource,
    changes: Flow<*>,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<AdvisorUiState>(AdvisorUiState(), scope, ioDispatcher) {
    private var loadJob: Job? = null

    init {
        observe(changes) { refresh() }
    }

    fun dispatch(intent: AdvisorIntent) {
        when (intent) {
            AdvisorIntent.Refresh -> refresh()
            AdvisorIntent.ToggleFactors -> setState { it.copy(factorsExpanded = !it.factorsExpanded) }
        }
    }

    private fun refresh() {
        loadJob?.cancel()
        setState { it.copy(loading = true, error = null) }
        loadJob = launchLocalFirstRead(source::load) { result ->
            setState { current ->
                result.fold(
                    onSuccess = { current.copy(loading = false, readiness = it) },
                    onFailure = { current.copy(loading = false, error = it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }
}
