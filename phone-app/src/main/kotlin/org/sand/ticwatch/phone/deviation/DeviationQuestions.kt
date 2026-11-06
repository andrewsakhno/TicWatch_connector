package org.sand.ticwatch.phone.deviation

import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What a notification asks about: [waiting] are all unanswered episodes, [quickReasons] the one-tap answers. */
data class DeviationQuestion(val waiting: List<DeviationEpisode>, val quickReasons: List<DeviationReason>)

interface DeviationQuestionNotifier {
    /** Returns false when the question could not be shown, so it is asked again later. */
    fun ask(question: DeviationQuestion): Boolean

    fun dismiss()
}

/** When a question may interrupt (all preliminary): not at night, a few a day, and only about something recent. */
class DeviationQuestionPolicy(private val zone: ZoneId) {

    fun mayAsk(now: Instant, askedToday: Int): Boolean =
        askedToday < MAX_QUESTIONS_PER_DAY && now.atZone(zone).hour !in QUIET_HOURS

    fun dayStart(now: Instant): Instant = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    fun freshSince(now: Instant): Instant = now.minus(FRESHNESS)

    private companion object {
        const val MAX_QUESTIONS_PER_DAY = 3

        /** A night's episodes wait for the first sync of the morning. */
        val QUIET_HOURS = setOf(22, 23, 0, 1, 2, 3, 4, 5, 6)

        /** Older episodes stay on the screen, but nobody remembers them well enough to be interrupted. */
        val FRESHNESS: Duration = Duration.ofHours(24)
    }
}

/** Notifies about stored episodes nobody was asked about yet; the policy decides whether now is the time. */
class DeviationQuestionAsker(
    private val log: DeviationEpisodeLog,
    private val queue: DeviationQuestionQueue,
    private val notifier: DeviationQuestionNotifier,
    private val policy: DeviationQuestionPolicy,
    private val clock: Clock,
) {
    suspend fun askIfDue() {
        val now = clock.instant()
        if (!policy.mayAsk(now, queue.askedSince(policy.dayStart(now)))) return
        val unasked = queue.unasked(policy.freshSince(now))
        if (unasked.isEmpty()) return
        val listed = log.episodes().first()
        val question = DeviationQuestion(listed.filter { it.waitingForAnswer }, quickReasons(listed, MAX_QUICK_REASONS))
        if (notifier.ask(question)) queue.markAsked(unasked.map { it.id }, now)
    }

    suspend fun dismissIfNoneWaiting() {
        if (log.episodes().first().none { it.waitingForAnswer }) notifier.dismiss()
    }

    private companion object {
        /** Android shows at most three notification actions. */
        const val MAX_QUICK_REASONS = 3
    }
}

/** The one way an answer gets stored, from the screen or the notification's buttons. */
class DeviationAnswers(
    private val log: DeviationEpisodeLog,
    private val asker: DeviationQuestionAsker,
    private val clock: Clock,
    private val onAnswered: () -> Unit,
) {
    suspend fun answer(id: Long, reasons: Set<DeviationReason>, intensity: DeviationIntensity? = null) {
        log.answer(id, reasons, intensity, clock.instant())
        onAnswered()
        asker.dismissIfNoneWaiting()
    }
}
