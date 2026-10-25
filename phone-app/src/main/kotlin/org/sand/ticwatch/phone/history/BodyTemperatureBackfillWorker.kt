package org.sand.ticwatch.phone.history

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.sand.ticwatch.phone.AppContainer

/**
 * One-time [BodyTemperatureBackfill] run: enqueued on every app start but skipped once
 * [BACKFILL_VERSION] has completed (bump it when the estimator changes enough to warrant
 * filling gaps again). A forced run ignores that marker.
 */
class BodyTemperatureBackfillWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val state = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val forced = inputData.getBoolean(KEY_FORCE, false)
        if (!forced && state.getInt(KEY_COMPLETED_VERSION, NOT_COMPLETED) >= BACKFILL_VERSION) return Result.success()
        // WorkManager instantiates workers reflectively, so this is the worker's composition root.
        return runCatching { AppContainer(applicationContext).bodyTemperatureBackfill.run() }.fold(
            onSuccess = { written ->
                Log.i(TAG, "Backfilled $written body temperature estimate(s)")
                state.edit().putInt(KEY_COMPLETED_VERSION, BACKFILL_VERSION).apply()
                Result.success()
            },
            onFailure = { error ->
                Log.w(TAG, "Body temperature backfill failed", error)
                Result.retry()
            },
        )
    }

    companion object {
        private const val TAG = "BodyTempBackfill"
        private const val UNIQUE_WORK_NAME = "body_temperature_backfill"
        private const val PREFERENCES_NAME = "body_temperature_backfill"
        private const val KEY_COMPLETED_VERSION = "completed_version"
        private const val KEY_FORCE = "force"
        private const val NOT_COMPLETED = 0
        private const val BACKFILL_VERSION = 1

        /** Runs the backfill unless this version already completed; [force] re-runs it regardless. */
        fun enqueue(context: Context, force: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BodyTemperatureBackfillWorker>()
                    .setInputData(workDataOf(KEY_FORCE to force))
                    .build(),
            )
        }
    }
}
