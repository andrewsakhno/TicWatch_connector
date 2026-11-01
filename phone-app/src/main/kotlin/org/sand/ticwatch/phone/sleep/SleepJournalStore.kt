package org.sand.ticwatch.phone.sleep

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import org.sand.ticwatch.phone.ui.UiStateStore
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** [entries] are newest first and cover everything since [loadedFrom]. */
data class SleepJournalUiState(
    val entries: List<SleepJournalEntry> = emptyList(),
    val loadedFrom: Instant? = null,
    val hasOlder: Boolean = true,
    val loading: Boolean = false,
)

/**
 * Pages the journal backwards in spans of [pageSpan], skipping empty ones. A change signal re-reads only the most
 * recent span, so an update doesn't reset a list scrolled far back.
 */
class SleepJournalStore(
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    changes: Flow<*>,
    private val journal: SleepJournal,
    private val clock: Clock = Clock.systemUTC(),
    private val pageSpan: Duration = Duration.ofDays(14),
) : UiStateStore<SleepJournalUiState>(SleepJournalUiState(), scope, ioDispatcher) {
    private var refreshJob: Job? = null

    init {
        observe(changes) { current.loadedFrom?.let(::refreshRecent) ?: loadOlder() }
    }

    fun loadOlder() {
        val state = current
        if (state.loading || !state.hasOlder) return
        setState { it.copy(loading = true) }
        launchIo({ olderPage(state.loadedFrom ?: clock.instant()) }) { result ->
            result.onFailure { Log.w(TAG, "Sleep journal page failed", it) }
            setState { latest ->
                result.fold(
                    onSuccess = { page ->
                        latest.copy(entries = latest.entries + page.entries, loadedFrom = page.from, hasOlder = page.hasOlder, loading = false)
                    },
                    onFailure = { latest.copy(loading = false) },
                )
            }
        }
    }

    private fun refreshRecent(loadedFrom: Instant) {
        refreshJob?.cancel()
        val now = clock.instant()
        val boundary = maxOf(loadedFrom, now.minus(pageSpan))
        refreshJob = launchIo({ journal.entries(boundary, now) }) { result ->
            result.fold(
                onSuccess = { fresh -> setState { it.copy(entries = fresh + it.entries.filter { entry -> entry.session.start < boundary }) } },
                onFailure = { Log.w(TAG, "Sleep journal refresh failed", it) },
            )
        }
    }

    private suspend fun olderPage(before: Instant): Page {
        val oldest = journal.oldestStart(before) ?: return Page(emptyList(), before, hasOlder = false)
        var to = before
        while (true) {
            val from = to.minus(pageSpan)
            val entries = journal.entries(from, to)
            val hasOlder = from.isAfter(oldest)
            if (entries.isNotEmpty() || !hasOlder) return Page(entries, from, hasOlder)
            to = from
        }
    }

    private class Page(val entries: List<SleepJournalEntry>, val from: Instant, val hasOlder: Boolean)

    private companion object {
        const val TAG = "SleepJournalStore"
    }
}
