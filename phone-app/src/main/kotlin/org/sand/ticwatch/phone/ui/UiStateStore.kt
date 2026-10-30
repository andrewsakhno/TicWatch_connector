package org.sand.ticwatch.phone.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Observable state holder of one screen: the single source of truth the UI renders by collecting [state]. Every
 * data-source call runs on [ioDispatcher] and only the result is reduced into [state] back on [scope] (the main
 * thread), so the UI thread never blocks; [scope] is the screen's, so pending work dies with it.
 */
abstract class UiStateStore<S>(
    initial: S,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<S> = mutableState.asStateFlow()

    protected val current: S get() = mutableState.value

    protected fun setState(reduce: (S) -> S) = mutableState.update(reduce)

    /** Runs [work] off the main thread and hands its outcome to [onResult] on [scope]. */
    protected fun <T> launchIo(work: suspend () -> T, onResult: (Result<T>) -> Unit): Job = scope.launch {
        val result = runCatching { withContext(ioDispatcher) { work() } }
            .onFailure { if (it is CancellationException) throw it }
        onResult(result)
    }

    /** Listens to [source] (collected upstream on [ioDispatcher]) and hands every value to [onValue] on [scope]. */
    protected fun <T> observe(source: Flow<T>, onValue: (T) -> Unit): Job = scope.launch {
        source.flowOn(ioDispatcher).collect { onValue(it) }
    }
}

/**
 * Read-only screen data: [load]ed off the main thread for the current value of [changes] and again on every change
 * (a [DataChangeSignal] is a [StateFlow], so collecting it also triggers the first load). [state] stays null until
 * the first load succeeds; a failed reload keeps the previous value.
 */
class ReloadingStore<T>(
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    changes: Flow<*>,
    private val load: suspend () -> T,
) : UiStateStore<T?>(null, scope, ioDispatcher) {
    private var loadJob: Job? = null

    init {
        observe(changes) { reload() }
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = launchIo(load) { result ->
            result.fold(onSuccess = { value -> setState { value } }, onFailure = { Log.w(TAG, "Screen data load failed", it) })
        }
    }

    private companion object {
        const val TAG = "ReloadingStore"
    }
}

/** No change signal: the data is loaded once when the store is created. */
val LOAD_ONCE: Flow<Unit> = flowOf(Unit)

/** Remembers a store bound to this composition's scope, with blocking work on [Dispatchers.IO]. */
@Composable
fun <T> rememberStore(vararg keys: Any?, create: (CoroutineScope, CoroutineDispatcher) -> T): T {
    val scope = rememberCoroutineScope()
    return remember(*keys) { create(scope, Dispatchers.IO) }
}
