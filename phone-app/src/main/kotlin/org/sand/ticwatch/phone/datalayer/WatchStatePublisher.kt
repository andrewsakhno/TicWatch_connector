package org.sand.ticwatch.phone.datalayer

/** Pushes one kind of phone-held state to the watch; the router calls every one after a stored sensor push. */
fun interface WatchStatePublisher {
    fun publish()
}
