package org.sand.ticwatch.phone.deviation

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.sand.ticwatch.phone.ui.UiStateStore
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class DeviationUiState(
    val waiting: List<DeviationEpisode> = emptyList(),
    val reasons: List<DeviationReason> = DeviationReason.entries,
) {
    val waitingCount: Int get() = waiting.size
}

/** The Deviations screen and the drawer's count. Room pushes every change, whichever composition root wrote it. */
class DeviationEpisodesStore(
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    log: DeviationEpisodeLog,
    private val answers: DeviationAnswers,
) : UiStateStore<DeviationUiState>(DeviationUiState(), scope, ioDispatcher) {

    init {
        // Answered episodes leave the list but still rank the reasons.
        observe(log.episodes()) { episodes ->
            setState { it.copy(waiting = episodes.filter(DeviationEpisode::waitingForAnswer), reasons = reasonsByUse(episodes)) }
        }
    }

    fun answer(id: Long, reasons: Set<DeviationReason>, intensity: DeviationIntensity?) {
        launchIo({ answers.answer(id, reasons, intensity) }) { result -> result.onFailure { Log.w(TAG, "Storing the answer failed", it) } }
    }

    fun discard(id: Long) {
        launchIo({ answers.discard(id) }) { result -> result.onFailure { Log.w(TAG, "Discarding the episode failed", it) } }
    }

    private companion object {
        const val TAG = "DeviationStore"
    }
}

/** An episode's date and time span in the current locale, shared by the screen and the notification. */
class DeviationPeriodFormat(private val zone: ZoneId) {
    private val startFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(zone)
    private val endFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone)

    fun period(episode: DeviationEpisode): String = "${startFormat.format(episode.start)} - ${endFormat.format(episode.end)}"
}
