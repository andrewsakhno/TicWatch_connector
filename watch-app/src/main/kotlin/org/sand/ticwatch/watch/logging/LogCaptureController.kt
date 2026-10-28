package org.sand.ticwatch.watch.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Runs [capture] exactly while [enabled] says so (the user's "Log capture" setting). */
class LogCaptureController(
    private val capture: LogCapture,
    private val enabled: Flow<Boolean>,
) {
    fun bind(scope: CoroutineScope) {
        scope.launch {
            enabled.distinctUntilChanged().collect { on -> if (on) capture.start() else capture.stop() }
        }
    }
}
