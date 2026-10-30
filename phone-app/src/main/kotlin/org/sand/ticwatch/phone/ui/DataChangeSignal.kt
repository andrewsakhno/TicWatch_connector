package org.sand.ticwatch.phone.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * "The stored data changed" notification: writers call [notifyChanged], screens observe [changes] and re-read their
 * source of truth. A version counter rather than a payload, so a slow collector only sees the latest change.
 */
class DataChangeSignal {
    private val version = MutableStateFlow(0L)
    val changes: StateFlow<Long> = version.asStateFlow()

    fun notifyChanged() = version.update { it + 1 }
}

/** Emits once at start and then on a change of any of [signals]. */
fun anyChangeOf(vararg signals: DataChangeSignal): Flow<Unit> = combine(signals.map { it.changes }) { }
