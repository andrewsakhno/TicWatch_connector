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

    /** The prompt's "Start" action. */
    suspend fun onStartRequested() = mutex.withLock {
        if (runningSince == null) start(detector.briskSince ?: clock())
        updateTicking()
    }

    /** The ongoing notification's "End" action. */
    suspend fun onEndRequested() = mutex.withLock {
        if (runningSince != null) finish(endSession = true) else prompt.clear()
        updateTicking()
    }

    private suspend fun evaluate(now: Instant, verifyOwner: Boolean) {
        detector.expire(now)
        val since = runningSince
        if (since != null) {
            if (verifyOwner && safely("check owner") { session.owner() } != WorkoutOwner.THIS_APP) {
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

    private suspend fun start(walkStart: Instant) {
        asking = false
        prompt.clear()
        when (safely("check owner") { session.owner() }) {
            WorkoutOwner.NONE -> Unit
            WorkoutOwner.THIS_APP -> return adoptRunningWorkout()
            WorkoutOwner.OTHER_APP, null -> {
                Log.i(TAG, "Another workout is running or unknown; not starting a walk")
                return
            }
        }
        ledger.record(walkStart)
        if (safely("start walk") { session.startWalk() } != true) return
        Log.i(TAG, "Walk workout started (walking since $walkStart)")
        runningSince = clock()
        handledStretch = detector.briskSince
        prompt.showRunning()
    }

    /** Picks up this app's workout after a service restart. */
    private suspend fun adoptRunningWorkout() {
        if (safely("check owner") { session.owner() } != WorkoutOwner.THIS_APP) return
        runningSince = clock()
        prompt.showRunning()
        updateTicking()
    }

    private suspend fun finish(endSession: Boolean) {
        if (endSession) safely("end walk") { session.end() }
        runningSince = null
        // A workout ended while still walking isn't restarted by the same stretch.
        handledStretch = detector.briskSince
        prompt.clear()
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
