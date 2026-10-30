package org.sand.ticwatch.watch.ecg

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EcgCapturePhase { FINDING_PULSE, RECORDING, FINISHED }

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

/** Process-wide: the controller runs in the service, the screen in its own activity. */
object EcgCaptureState : EcgCaptureStateSink {
    private val state = MutableStateFlow<EcgCaptureProgress?>(null)
    val current: StateFlow<EcgCaptureProgress?> = state.asStateFlow()

    override fun publish(progress: EcgCaptureProgress) {
        state.value = progress
    }
}

/** Lights the screen and shows the recording animation while the watch records. */
interface EcgCaptureScreen {
    fun show()

    fun hide()
}
