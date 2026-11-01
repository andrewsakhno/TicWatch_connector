package org.sand.ticwatch.phone.datalayer

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sand.ticwatch.phone.transport.LinkHold
import kotlin.time.Duration

/**
 * Answers a burst of watch pushes once: the first [publish] waits [window] for the rest of the watch's flush, then
 * every one of [publishers] runs, all over one [linkHold] socket instead of a connection (and a watch wake-up) each.
 */
class CoalescingWatchStatePublisher(
    private val publishers: List<WatchStatePublisher>,
    private val linkHold: LinkHold,
    private val scope: CoroutineScope,
    private val window: Duration,
) : WatchStatePublisher {

    private val scheduled = AtomicBoolean(false)
    private val running = Mutex()

    override fun publish() {
        if (!scheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(window)
            scheduled.set(false)
            running.withLock {
                linkHold.hold()
                try {
                    publishers.forEach { it.publishAndWait() }
                } finally {
                    linkHold.release()
                }
            }
        }
    }
}
