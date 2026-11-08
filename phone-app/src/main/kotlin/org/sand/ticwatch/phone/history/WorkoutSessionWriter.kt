package org.sand.ticwatch.phone.history

import android.database.sqlite.SQLiteDatabase
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.overlapFraction
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.Table
import org.sand.ticwatch.phone.history.SensorHistoryDatabase.WorkoutSessionTable
import java.time.Duration
import java.time.Instant

/**
 * A workout kept past the raw-row purge, with the [steps] taken inside it: the only activity the rest calculations know
 * of, so they never sum raw step rows themselves. [end] and [steps] are null while it is unfinished.
 */
data class WorkoutSession(
    val start: Instant,
    val end: Instant?,
    val exerciseTypeId: Int,
    val automatic: Boolean,
    val steps: Double?,
) {
    /** Mean steps per minute, null until finished or for a workout without length. */
    val cadence: Double?
        get() {
            val counted = steps ?: return null
            val millis = end?.let { Duration.between(start, it).toMillis() }?.takeIf { it > 0 } ?: return null
            return counted / millis * MILLIS_PER_MINUTE
        }

    companion object {
        /** Watch workouts and the walks/runs the phone detected afterwards. */
        val SAMPLE_TYPES: Set<SensorType> = setOf(SensorType.EXERCISE, SensorType.AUTO_EXERCISE)

        private const val MILLIS_PER_MINUTE = 60_000.0
    }
}

fun interface WorkoutSessionQueries {
    /** Workouts overlapping [from, to), ascending by start. */
    suspend fun workoutSessions(from: Instant, to: Instant): List<WorkoutSession>
}

/**
 * Copies workouts into `workout_session`, kept for the sleep retention chosen in Settings instead of the raw-row days.
 * Steps are counted once, when the finished workout arrives; an unfinished one is only stored, and its finished
 * version (same start, the key) replaces it. Nothing else re-counts: step rows arriving later don't touch a workout.
 */
class WorkoutSessionWriter : HistoryProjection {

    override fun accumulate(db: SQLiteDatabase, samples: List<SensorSample>) {
        val workouts = samples.filter { it.type in WorkoutSession.SAMPLE_TYPES }
        if (workouts.isEmpty()) return
            db.compileStatement(
                "INSERT OR REPLACE INTO ${WorkoutSessionTable.NAME} (${WorkoutSessionTable.START}, ${WorkoutSessionTable.END}, " +
                    "${WorkoutSessionTable.EXERCISE_TYPE}, ${WorkoutSessionTable.AUTOMATIC}, ${WorkoutSessionTable.STEPS}) " +
                    "VALUES (?, ?, ?, ?, ?)",
            ).use { insert ->
            workouts.forEach { workout ->
                val end = workout.endedAt
                // A finished workout never goes back to unfinished, e.g. by an out-of-order re-send.
                if (end == null && isFinished(db, workout.recordedAt)) return@forEach
                insert.clearBindings()
                insert.bindLong(1, workout.recordedAt.toEpochMilli())
                if (end == null) insert.bindNull(2) else insert.bindLong(2, end.toEpochMilli())
                insert.bindLong(3, workout.value.toLong())
                insert.bindLong(4, if (workout.type == SensorType.AUTO_EXERCISE) WorkoutSessionTable.AUTOMATIC_WORKOUT else WorkoutSessionTable.MANUAL_WORKOUT)
                if (end == null) insert.bindNull(5) else insert.bindDouble(5, stepsIn(db, workout.recordedAt, end))
                insert.executeInsert()
            }
        }
    }

    private fun isFinished(db: SQLiteDatabase, start: Instant): Boolean = db.query(
        WorkoutSessionTable.NAME, arrayOf(WorkoutSessionTable.START),
        "${WorkoutSessionTable.START} = ? AND ${WorkoutSessionTable.END} IS NOT NULL",
        arrayOf(start.toEpochMilli().toString()), null, null, null,
    ).use { it.moveToFirst() }

    /** Stored step intervals inside [start]..[end], each by its overlapping share; a re-sent interval counts once. */
    private fun stepsIn(db: SQLiteDatabase, start: Instant, end: Instant): Double =
        db.query(
            Table.NAME, SensorHistoryDatabase.SAMPLE_COLUMNS,
            "${Table.TYPE} = ? AND ${Table.RECORDED_AT} >= ? AND ${Table.RECORDED_AT} < ?",
            arrayOf(
                SensorTypeCodes.code(SensorType.STEPS).toString(),
                start.minus(LONGEST_STEP_INTERVAL).toEpochMilli().toString(),
                end.toEpochMilli().toString(),
            ),
            null, null, null,
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.readSample()) } }
            .sumOf { it.value * it.overlapFraction(start, end) }

    private companion object {
        /** Step intervals are stored by their start, which can lie this far before a workout they overlap. */
        val LONGEST_STEP_INTERVAL: Duration = Duration.ofMinutes(20)
    }
}
