package org.sand.ticwatch.phone.transport

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** How a send competes for the phone->watch link. */
enum class LinkPriority {
    /** Commands, sensor pings, debug probes: short, someone is waiting for the reply. */
    INTERACTIVE,

    /** APK-sized payloads: may be paused at a chunk boundary to let [INTERACTIVE] sends through. */
    BULK;

    companion object {
        fun of(length: Long): LinkPriority =
            if (length > TransportProtocol.RESUMABLE_THRESHOLD) BULK else INTERACTIVE
    }
}

/**
 * Pause hook a [LinkPriority.BULK] send finds in its coroutine context. Transports check
 * [isRequested] at safe points (between acknowledged chunks, before reconnecting) and
 * call [yieldLink] with no socket held; it returns once the waiting sends are done.
 */
interface LinkPreemption : CoroutineContext.Element {
    val isRequested: Boolean

    suspend fun yieldLink()

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<LinkPreemption>
}

/** The current bulk send's pause hook, or null outside a [LinkPriority.BULK] send. */
suspend fun currentLinkPreemption(): LinkPreemption? = coroutineContext[LinkPreemption]

/**
 * Process-wide owner of the phone->watch link. Interactive sends run one at a time; a bulk
 * send holds the link only between its pause points, so a command issued mid-APK-transfer
 * goes out within one chunk round trip instead of racing the transfer for the radio.
 */
class LinkArbiter {
    private val link = Mutex()
    private val bulkQueue = Mutex()
    private val interactiveInFlight = MutableStateFlow(0)

    suspend fun <T> withLink(priority: LinkPriority, block: suspend () -> T): T = when (priority) {
        LinkPriority.INTERACTIVE -> interactive(block)
        LinkPriority.BULK -> bulk(block)
    }

    private suspend fun <T> interactive(block: suspend () -> T): T {
        interactiveInFlight.update { it + 1 }
        try {
            return link.withLock { block() }
        } finally {
            interactiveInFlight.update { it - 1 }
        }
    }

    private suspend fun <T> bulk(block: suspend () -> T): T = bulkQueue.withLock {
        link.withLock { withContext(Preemption()) { block() } }
    }

    private inner class Preemption : LinkPreemption {
        override val isRequested: Boolean get() = interactiveInFlight.value > 0

        override suspend fun yieldLink() {
            link.unlock()
            try {
                interactiveInFlight.first { it == 0 }
            } finally {
                // Re-take the link even when cancelled: the enclosing withLock unlocks it.
                withContext(NonCancellable) { link.lock() }
            }
        }
    }
}

/** Routes every send through [arbiter] by [LinkPriority.of] its length (OCP: transports stay unaware). */
class ArbitratedDeviceLinkTransport(
    private val delegate: DeviceLinkTransport,
    private val arbiter: LinkArbiter,
) : DeviceLinkTransport {

    override suspend fun send(path: String, length: Long, source: PayloadSource): Result<Unit> =
        arbiter.withLink(LinkPriority.of(length)) { delegate.send(path, length, source) }
}
