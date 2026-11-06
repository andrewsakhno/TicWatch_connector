package org.sand.ticwatch.watch.autoworkout

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sand.ticwatch.watch.sensors.WindowScheduler
import java.time.Duration
import java.time.Instant

/**
 * Starts a walk workout once the wearer has walked briskly for [minWalk] - right away in
 * [AutoWorkoutMode.AUTO], after a tap on the prompt in [AutoWorkoutMode.ASK] - and ends it after
 * [stopAfter] without movement. Each brisk stretch is acted on once: a workout ended by hand isn't
 * restarted until the wearer has stopped walking. Never touches a workout another app runs.
 * While a stretch or a workout is open it re-checks every [checkInterval] on [scheduler], since a
 * stop produces no step events to react to.
 */
class AutoWorkoutController(
    private val steps: StepCountSource,
    private val detector: BriskWalkDetector,
    private val session: WorkoutSession,
    private val settings: AutoWorkoutSettings,
    private val prompt: AutoWorkoutPrompt,
    private val ledger: AutoWorkoutStartLedger,
    private val scheduler: WindowScheduler,
    private val minWalk: Duration,
    private val stopAfter: Duration,
    private val checkInterval: Duration,
    private val clock: () -> Instant = Instant::now,
) {

    private val mutex = Mutex()
    private val ticking = MutableStateFlow(false)
    private var runningSince: Instant? = null
    private var handledStretch: Instant? = null
    private var asking = false

    suspend fun run() = coroutineScope {
        mutex.withLock { adoptRunningWorkout() }
        launch {
            ticking.collectLatest { needed ->
                while (needed) {
                    scheduler.awaitNext(checkInterval)
                    mutex.withLock { evaluate(clock(), verifyOwner = true) }
                }
            }
        }
        steps.steps().collect { count ->
            mutex.withLock {
                detector.onSteps(count)
                evaluate(count.at, verifyOwner = false)
            }
        }
    }

    /** The prompt's "Start" action and the phone's start command. */
    suspend fun onStartRequested(): WorkoutCommandOutcome = mutex.withLock {
        val outcome = if (runningSince == null) start(detector.briskSince ?: clock()) else WorkoutCommandOutcome.ALREADY_RUNNING
        updateTicking()
        outcome
    }

    /** The ongoing notification's "End" action and the phone's end command. */
    suspend fun onEndRequested(): WorkoutCommandOutcome = mutex.withLock {
        val outcome = if (runningSince != null) endRunning() else endWithoutTrackedWorkout()
        updateTicking()
        outcome
    }

    private suspend fun endRunning(): WorkoutCommandOutcome =
        if (finish(endSession = true)) WorkoutCommandOutcome.ENDED else WorkoutCommandOutcome.FAILED

    private suspend fun endWithoutTrackedWorkout(): WorkoutCommandOutcome {
        prompt.clear()
        return when (safely("check owner") { session.owner() }) {
            // Still this app's after a service restart that couldn't check at the time.
            WorkoutOwner.THIS_APP -> endRunning()
            WorkoutOwner.OTHER_APP -> WorkoutCommandOutcome.OTHER_APP_RUNNING
            WorkoutOwner.NONE -> WorkoutCommandOutcome.NOT_RUNNING
            null -> WorkoutCommandOutcome.FAILED
        }
    }

    private suspend fun evaluate(now: Instant, verifyOwner: Boolean) {
        detector.expire(now)
        val since = runningSince
        if (since != null) {
            // An unanswered check (null) isn't an ended workout: the next tick asks again.
            val owner = if (verifyOwner) safely("check owner") { session.owner() } else WorkoutOwner.THIS_APP
            if (owner != null && owner != WorkoutOwner.THIS_APP) {
                Log.i(TAG, "Walk workout ended elsewhere")
                finish(endSession = false)
            } else if (Duration.between(maxOf(detector.lastMovingAt ?: since, since), now) >= stopAfter) {
                Log.i(TAG, "No walking for $stopAfter; ending the walk workout")
                finish(endSession = true)
            }
        } else {
            onStretch(detector.briskSince)
        }
        updateTicking()
    }

    private suspend fun onStretch(stretchStart: Instant?) {
        if (stretchStart == null) {
            if (asking) prompt.clear()
            asking = false
            return
        }
        if (stretchStart == handledStretch || detector.briskDuration() < minWalk) return
        handledStretch = stretchStart
        when (settings.mode()) {
            AutoWorkoutMode.OFF -> Unit
            AutoWorkoutMode.ASK -> {
                prompt.askToStart()
                asking = true
            }
            AutoWorkoutMode.AUTO -> start(stretchStart)
        }
    }

    private suspend fun start(walkStart: Instant): WorkoutCommandOutcome {
        asking = false
        prompt.clear()
        when (safely("check owner") { session.owner() }) {
            WorkoutOwner.NONE -> Unit
            WorkoutOwner.THIS_APP -> {
                adoptRunningWorkout()
                return WorkoutCommandOutcome.ALREADY_RUNNING
            }
            WorkoutOwner.OTHER_APP -> {
                Log.i(TAG, "Another app's workout is running; not starting a walk")
                return WorkoutCommandOutcome.OTHER_APP_RUNNING
            }
            null -> {
                Log.i(TAG, "Workout owner unknown; not starting a walk")
                return WorkoutCommandOutcome.FAILED
            }
        }
        ledger.record(walkStart)
        if (safely("start walk") { session.startWalk() } != true) return WorkoutCommandOutcome.FAILED
        Log.i(TAG, "Walk workout started (walking since $walkStart)")
        runningSince = clock()
        handledStretch = detector.briskSince
        prompt.showRunning()
        return WorkoutCommandOutcome.STARTED
    }

    /** Picks up this app's workout after a service restart. */
    private suspend fun adoptRunningWorkout() {
        if (safely("check owner") { session.owner() } != WorkoutOwner.THIS_APP) return
        session.attach()
        runningSince = clock()
        prompt.showRunning()
        updateTicking()
    }

    /** Returns false when Health Services refused to end the session. */
    private suspend fun finish(endSession: Boolean): Boolean {
        val ended = !endSession || safely("end walk") { session.end() } != null
        runningSince = null
        // A workout ended while still walking isn't restarted by the same stretch.
        handledStretch = detector.briskSince
        prompt.clear()
        return ended
    }

    private fun updateTicking() {
        ticking.value = runningSince != null || detector.briskSince != null
    }

    private suspend fun <T> safely(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "Health Services failed to $what", error)
        null
    }

    private companion object {
        const val TAG = "AutoWorkoutController"
    }
}
