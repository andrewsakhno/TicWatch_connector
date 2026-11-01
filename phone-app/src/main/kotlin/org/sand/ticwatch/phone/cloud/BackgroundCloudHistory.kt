package org.sand.ticwatch.phone.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.RollupPoint
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SampleCursor
import org.sand.ticwatch.phone.history.StoredSample
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context mark of a screen's read: [CloudBackedSensorHistoryQueries] answers it at once from local data plus
 * what [BackgroundCloudHistory] already holds, instead of waiting for the server. Everything unmarked (derivers, which
 * store what they compute; MCP tools and the report, which get one answer) keeps waiting for the complete data.
 */
class LocalFirstCloudReads : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalFirstCloudReads>
}

/**
 * Cloud history for [LocalFirstCloudReads]: reads never suspend. A miss answers null and starts the fetch in the
 * background; when fetches bring rows, [onArrived] fires and the screens read again, now hitting the cache.
 *
 * Windows are widened to whole local days, because callers' ranges follow the clock: exact keys would miss on every
 * reload, and each miss would fire [onArrived] again, without end. Data this old doesn't change, so an entry stays
 * valid until the window moves on to the next day.
 */
class BackgroundCloudHistory(
    private val remote: CloudHistoryReader,
    scope: CoroutineScope,
    private val zone: ZoneId,
    onArrived: () -> Unit,
) {
    // One gate for both caches: the server is a netbook's Atom.
    private val gate = Semaphore(MAX_PARALLEL_FETCHES)
    private val samples = BackgroundFetchCache<SamplesKey, StoredSample>(scope, gate, MAX_CACHED_ITEMS, onArrived)
    private val rollups = BackgroundFetchCache<RollupsKey, RollupPoint>(scope, gate, MAX_CACHED_ITEMS, onArrived)

    /** Rows of [types] in [from, to), ascending; null until they have arrived. */
    fun samples(types: Set<SensorType>, from: Instant, to: Instant): List<StoredSample>? {
        val key = SamplesKey(types, dayStart(from), nextDayStart(to))
        return samples.get(key) { fetchSamples(key) }
            ?.filter { !it.sample.recordedAt.isBefore(from) && it.sample.recordedAt.isBefore(to) }
    }

    /** Buckets starting in [from, to); null until they have arrived. */
    fun rollups(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant): List<RollupPoint>? {
        val key = RollupsKey(type, resolution, dayStart(from), nextDayStart(to))
        return rollups.get(key) { remote.rollups(type, resolution, key.from, key.to) }
            ?.filter { !it.bucketStart.isBefore(from) && it.bucketStart.isBefore(to) }
    }

    /** All pages of [key] up to [MAX_ROWS_PER_WINDOW]; null when any page failed, so a partial window is never cached. */
    private suspend fun fetchSamples(key: SamplesKey): List<StoredSample>? = buildList {
        var after: SampleCursor? = null
        while (size < MAX_ROWS_PER_WINDOW) {
            val page = remote.samples(key.types, key.from, key.to, after, PAGE_SIZE) ?: return null
            addAll(page)
            if (page.size < PAGE_SIZE) break
            after = page.last().cursor
        }
    }

    private fun dayStart(at: Instant): Instant = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    private fun nextDayStart(at: Instant): Instant = at.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()

    private data class SamplesKey(val types: Set<SensorType>, val from: Instant, val to: Instant)

    private data class RollupsKey(val type: SensorType, val resolution: RollupResolution, val from: Instant, val to: Instant)

    private companion object {
        const val MAX_PARALLEL_FETCHES = 2
        const val PAGE_SIZE = 2_000
        const val MAX_ROWS_PER_WINDOW = 30_000
        const val MAX_CACHED_ITEMS = 100_000
    }
}

/**
 * Bounded (least recently used) cache of list-valued fetches. [get] answers at once; a miss starts the fetch on [scope],
 * which outlives the asking coroutine, so a screen reload cancelling its read doesn't throw the work away. A fetch
 * answering null (server unreachable) caches nothing and fires nothing; the next [get] tries again. [onArrived] fires
 * once per burst, when the last running fetch ended and at least one brought items, so a screen that asked for
 * many ranges recalculates once.
 */
private class BackgroundFetchCache<K : Any, T>(
    private val scope: CoroutineScope,
    private val gate: Semaphore,
    private val maxItems: Int,
    private val onArrived: () -> Unit,
) {
    private val entries = LinkedHashMap<K, List<T>>(INITIAL_CAPACITY, LOAD_FACTOR, ACCESS_ORDER)
    private val inFlight = HashSet<K>()
    private var itemCount = 0
    private var arrivedInBurst = false

    fun get(key: K, fetch: suspend () -> List<T>?): List<T>? {
        synchronized(entries) {
            entries[key]?.let { return it }
            if (!inFlight.add(key)) return null
        }
        scope.launch {
            var items: List<T>? = null
            try {
                items = gate.withPermit { fetch() }
            } finally {
                settle(key, items)
            }
        }
        return null
    }

    private fun settle(key: K, items: List<T>?) {
        val burstEnded = synchronized(entries) {
            inFlight.remove(key)
            if (items != null) store(key, items)
            arrivedInBurst = arrivedInBurst || !items.isNullOrEmpty()
            (inFlight.isEmpty() && arrivedInBurst).also { ended -> if (ended) arrivedInBurst = false }
        }
        if (burstEnded) onArrived()
    }

    /** The new entry is the most recently used, so eviction reaches it last. */
    private fun store(key: K, items: List<T>) {
        entries[key] = items
        itemCount += items.size
        val eldestFirst = entries.entries.iterator()
        while (itemCount > maxItems && eldestFirst.hasNext()) {
            val eldest = eldestFirst.next()
            if (eldest.key == key) break
            itemCount -= eldest.value.size
            eldestFirst.remove()
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
        const val ACCESS_ORDER = true
    }
}
