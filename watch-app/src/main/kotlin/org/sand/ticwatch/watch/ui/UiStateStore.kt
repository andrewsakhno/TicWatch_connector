package org.sand.ticwatch.watch.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Observable state holder of one screen: the single source of truth the UI renders by collecting [state]. Every
 * data-source call runs on [ioDispatcher] and only the result is reduced into [state] back on [scope] (the main
 * thread), so the UI thread never blocks; [scope] is the screen's, so pending work dies with it. Mirrors the
 * phone-app's UiStateStore (no shared module).
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
