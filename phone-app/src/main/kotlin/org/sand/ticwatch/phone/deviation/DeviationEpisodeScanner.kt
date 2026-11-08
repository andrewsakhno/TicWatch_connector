package org.sand.ticwatch.phone.deviation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.WorkoutActivitySource
import org.sand.ticwatch.phone.history.NewHistoryRows
import org.sand.ticwatch.phone.history.StoredRowsRange
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The newest history row a finished scan has seen. */
interface DeviationScanCursor {
    suspend fun lastScannedId(): Long?
    suspend fun save(id: Long)
}

class SharedPreferencesDeviationScanCursor(context: Context) : DeviationScanCursor {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override suspend fun lastScannedId(): Long? = withContext(Dispatchers.IO) {
        if (prefs.contains(KEY_LAST_ID)) prefs.getLong(KEY_LAST_ID, NewHistoryRows.BEFORE_FIRST_ROW) else null
    }

    override suspend fun save(id: Long) = withContext(Dispatchers.IO) {
        prefs.edit().putLong(KEY_LAST_ID, id).apply()
    }

    private companion object {
        const val PREFS_NAME = "deviation_scan"
        const val KEY_LAST_ID = "last_scanned_id"
    }
}

/**
 * Looks for deviation episodes in the time range the rows stored since the last scan cover, so a batch arriving late
 * is scanned like any other. It only observes: nothing goes back to the watch or into the samples. The cursor moves
 * only after a finished scan, and re-scanning a range changes nothing, so an interrupted run just repeats.
 */
class DeviationEpisodeScanner(
    private val newRows: NewHistoryRows,
    private val reader: SampleWindowReader,
    private val detector: RestingDeviationDetector,
    private val activity: WorkoutActivitySource,
    private val corridors: HeartRateCorridorSource,
    private val context: DeviationContextSource,
    private val sink: DeviationEpisodeSink,
    private val asker: DeviationQuestionAsker,
    private val cursor: DeviationScanCursor,
    private val clock: Clock,
) {
    suspend fun scan() {
        scanNewRows()
        // Also with nothing new: episodes held back by the quiet hours wait for the morning's first run.
        asker.askIfDue()
    }

    private suspend fun scanNewRows() {
        val lastScanned = cursor.lastScannedId()
        val stored = newRows.storedAfter(SCANNED_TYPES, lastScanned ?: NewHistoryRows.BEFORE_FIRST_ROW) ?: return
        val now = clock.instant()
        val oldest = now.minus(if (lastScanned == null) FIRST_SCAN_BACK else LONGEST_SCAN_BACK)
        val from = maxOf(stored.from.minus(SCAN_BEFORE_NEW_ROWS), oldest)
        val to = stored.to.plusMillis(1)
        val intervals = read(CONTEXT_INTERVAL_TYPES, from.minus(LONGEST_INTERVAL), to)
        val workouts = intervals.filter { it.type in WORKOUT_TYPES }
        discardFinishedWorkouts(workouts, stored, now)
        val candidates = detector.detect(
            // From a wear gap earlier, so a put-on just before the range is still recognised.
            heartRate = read(setOf(SensorType.HEART_RATE), from.minus(RestingDeviationDetector.WEAR_GAP), to).filter { it.isWatchHeartRate() },
            // Stored workouts outlive the raw rows; the raw ones cover a workout whose row is in this very range.
            activity = activity.activity(from, to).withSamples(workouts),
            sleep = intervals.filter { it.type in SLEEP_TYPES },
            corridors = corridors.corridors(now),
        )
        if (candidates.isNotEmpty()) sink.record(candidates.map { DetectedDeviation(it, context.contextAt(it.start)) }, now)
        cursor.save(stored.lastId)
    }

    /** A workout's sample only arrives once it ended, so the rows that just came in are the ones to act on. */
    private suspend fun discardFinishedWorkouts(workouts: List<SensorSample>, stored: StoredRowsRange, now: Instant) {
        val arrived = workouts.filter { !it.recordedAt.isBefore(stored.from) }
        if (arrived.isEmpty()) return
        arrived.forEach { workout ->
            val end = (workout.endedAt ?: workout.recordedAt).plus(RestingDeviationDetector.WORKOUT_RECOVERY)
            sink.discardUnanswered(workout.recordedAt, end, now)
        }
        asker.dismissIfNoneWaiting()
    }

    private suspend fun read(types: Set<SensorType>, from: Instant, to: Instant): List<SensorSample> =
        reader.samples(types, from, to, emptyList())

    /** A cuff's pulse is one reading taken on purpose, not part of a stretch. */
    private fun SensorSample.isWatchHeartRate() = type == SensorType.HEART_RATE && source == SampleSource.WATCH

    private companion object {
        val WORKOUT_TYPES = setOf(SensorType.EXERCISE, SensorType.AUTO_EXERCISE)
        val SLEEP_TYPES = setOf(SensorType.SLEEP, SensorType.SLEEP_STAGE)
        val CONTEXT_INTERVAL_TYPES = WORKOUT_TYPES + SLEEP_TYPES
        val SCANNED_TYPES = WORKOUT_TYPES + SensorType.HEART_RATE

        /** Enough to see a stretch that began before these rows; a longer one is pieced together when stored. */
        val SCAN_BEFORE_NEW_ROWS: Duration = Duration.ofMinutes(30)

        /** Workouts and nights are found by their start, which can lie this far before the readings. */
        val LONGEST_INTERVAL: Duration = Duration.ofHours(16)

        /** Without a cursor (first run) every stored row looks new; older ones were never meant to be asked about. */
        val FIRST_SCAN_BACK: Duration = Duration.ofHours(24)

        /** Keeps the readings held in memory bounded when a stray old row stretches the range. */
        val LONGEST_SCAN_BACK: Duration = Duration.ofDays(7)
    }
}
