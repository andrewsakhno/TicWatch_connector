package org.sand.ticwatch.phone

import android.util.Log
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.sand.ticwatch.phone.ecg.CardiogramAnalyzer
import org.sand.ticwatch.phone.ecg.EcgAnalysis
import org.sand.ticwatch.phone.ecg.EcgBeat
import org.sand.ticwatch.phone.ecg.EcgRecording
import org.sand.ticwatch.phone.ecg.EcgRecordingLog
import org.sand.ticwatch.phone.ecg.EcgRequester
import org.sand.ticwatch.phone.ecg.EcgSessionLog
import org.sand.ticwatch.phone.ecg.EcgSessionPhase
import org.sand.ticwatch.phone.ecg.EcgTrends
import org.sand.ticwatch.phone.ecg.EcgWaveSample
import org.sand.ticwatch.phone.ecg.LiveEcgSession
import org.sand.ticwatch.phone.ecg.PulseBloodPressure
import org.sand.ticwatch.phone.ecg.PulseBloodPressureEstimator
import org.sand.ticwatch.phone.ecg.PulseFeatures
import org.sand.ticwatch.phone.text.UiText
import org.sand.ticwatch.phone.text.uiText
import org.sand.ticwatch.phone.ui.UiStateStore

/** A stored strip with what the analyzer sees in it. */
data class AnalyzedEcg(val recording: EcgRecording, val analysis: EcgAnalysis, val bloodPressure: PulseBloodPressure? = null)

/** Everything the ECG card and its recordings list show. */
data class EcgUiState(
    val live: LiveEcgSession? = null,
    /** Analysis of the strip [live] just finished. */
    val liveAnalysis: EcgAnalysis? = null,
    val liveBloodPressure: PulseBloodPressure? = null,
    /** Stored strips, newest first; null until loaded. */
    val recordings: List<AnalyzedEcg>? = null,
    /** Per-strip metrics across [recordings], for the list's trend charts. */
    val trends: EcgTrends? = null,
    val message: UiText? = null,
)

/**
 * State holder of the ECG card: follows the process-wide live session (beats drawn as they arrive), analyzes a
 * finished strip at once and re-reads the stored strips whenever one was saved or deleted.
 */
class EcgStore(
    private val log: EcgRecordingLog,
    private val analyzer: CardiogramAnalyzer,
    private val bloodPressure: PulseBloodPressureEstimator,
    private val sessions: EcgSessionLog,
    private val requester: EcgRequester,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<EcgUiState>(EcgUiState(), scope, ioDispatcher) {
    private var analyzedRequestId: String? = null

    init {
        observe(sessions.live, ::onSession)
        reload()
    }

    fun record() {
        setState { it.copy(liveAnalysis = null, liveBloodPressure = null, message = null) }
        sessions.start(requester, RECORDING_TIMEOUT)
    }

    fun delete(recording: EcgRecording) {
        launchIo({ log.delete(recording.id) }) { result ->
            result.onFailure { error -> setState { it.copy(message = uiText(R.string.delete_failed, error.message.orEmpty())) } }
            reload()
        }
    }

    private fun onSession(session: LiveEcgSession?) {
        setState { it.copy(live = session) }
        if (session?.phase != EcgSessionPhase.FINISHED || session.requestId == analyzedRequestId) return
        analyzedRequestId = session.requestId
        launchIo({ analyze(session.beats, session.wave) }) { result ->
            result.onSuccess { (analysis, pressure) -> setState { it.copy(liveAnalysis = analysis, liveBloodPressure = pressure) } }
        }
        reload()
    }

    private fun reload() {
        launchIo({
            val recordings = log.newestFirst().map { recording ->
                analyze(recording.beats, recording.wave).let { (analysis, pressure) -> AnalyzedEcg(recording, analysis, pressure) }
            }
            recordings to EcgTrends.of(recordings.map { it.recording.startedAt to it.analysis })
        }) { result ->
            result.fold(
                onSuccess = { (recordings, trends) -> setState { it.copy(recordings = recordings, trends = trends) } },
                onFailure = { Log.w(TAG, "Loading cardiograms failed", it) },
            )
        }
    }

    private suspend fun analyze(beats: List<EcgBeat>, wave: List<EcgWaveSample>): Pair<EcgAnalysis, PulseBloodPressure?> {
        val analysis = analyzer.analyze(beats, wave)
        return analysis to PulseFeatures.of(analysis)?.let { bloodPressure.estimate(it) }
    }

    companion object {
        private const val TAG = "EcgStore"

        /** Watch warm-up (up to 20 s) + 30 s strip + delivery. */
        val RECORDING_TIMEOUT = 75.seconds
    }
}
