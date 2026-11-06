package org.sand.ticwatch.watch.autoworkout

/** Hands the phone's workout commands to whoever runs the workout. */
interface WorkoutCommandRequester {
    fun requestStart(requestId: String?)

    fun requestEnd(requestId: String?)
}
