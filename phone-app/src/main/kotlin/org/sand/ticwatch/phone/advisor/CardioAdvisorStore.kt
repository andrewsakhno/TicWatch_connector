package org.sand.ticwatch.phone.advisor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * is cancelled with the owning screen.
 */
class CardioAdvisorStore(
    private val source: CardioReadinessSource,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(AdvisorUiState())
    val state: StateFlow<AdvisorUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    fun dispatch(intent: AdvisorIntent) {
        when (intent) {
            AdvisorIntent.Refresh -> refresh()
            AdvisorIntent.ToggleFactors -> mutableState.update { it.copy(factorsExpanded = !it.factorsExpanded) }
        }
    }

    private fun refresh() {
        loadJob?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        loadJob = scope.launch {
            val result = runCatching { withContext(ioDispatcher) { source.load() } }
                .onFailure { if (it is CancellationException) throw it }
            mutableState.update { current ->
                result.fold(
                    onSuccess = { current.copy(loading = false, readiness = it) },
                    onFailure = { current.copy(loading = false, error = it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }
}
