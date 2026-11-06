package org.sand.ticwatch.watch.ecg

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

enum class EcgCapturePhase { AWAITING_CONFIRMATION, FINDING_PULSE, RECORDING, FINISHED }

/** What the on-watch recording screen shows. */
data class EcgCaptureProgress(
    val phase: EcgCapturePhase,
    val recordingMs: Long,
    /** Wall clock of the strip start, once the pulse locked on. */
    val recordingStartedAtMs: Long? = null,
    val heartRateBpm: Int? = null,
    /** Final status line, once [EcgCapturePhase.FINISHED]. */
    @StringRes val outcome: Int? = null,
)

/** Receives the recording's progress for the screen. */
fun interface EcgCaptureStateSink {
    fun publish(progress: EcgCaptureProgress)
}

/** The wearer's answer to the prompt shown in [EcgCapturePhase.AWAITING_CONFIRMATION]. */
fun interface EcgPromptAnswers {
    /** True when the wearer accepted within [timeoutMs]; otherwise the prompt is closed as declined. */
    suspend fun awaitAnswer(timeoutMs: Long): Boolean
}

/** Process-wide: the controller runs in the service, the screen in its own activity. */
object EcgCaptureState : EcgCaptureStateSink, EcgPromptAnswers {
    private val state = MutableStateFlow<EcgCaptureProgress?>(null)
    val current: StateFlow<EcgCaptureProgress?> = state.asStateFlow()

    override fun publish(progress: EcgCaptureProgress) {
        state.value = progress
    }

    /** Ignored unless a prompt is open. */
    fun answerPrompt(accepted: Boolean) = state.update { progress ->
        when {
            progress?.phase != EcgCapturePhase.AWAITING_CONFIRMATION -> progress
            accepted -> progress.copy(phase = EcgCapturePhase.FINDING_PULSE)
            else -> null
        }
    }

    override suspend fun awaitAnswer(timeoutMs: Long): Boolean {
        withTimeoutOrNull(timeoutMs) { state.first { it?.phase != EcgCapturePhase.AWAITING_CONFIRMATION } }
        answerPrompt(accepted = false)
        return state.value != null
    }
}

/** Lights the screen and shows the prompt, or the recording animation while the watch records. */
interface EcgCaptureScreen {
    fun show()

    fun showPrompt()

    fun hide()
}
