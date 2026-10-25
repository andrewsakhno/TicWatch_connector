package org.sand.ticwatch.phone.history

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Daily WorkManager job: runs [SensorHistoryPurger], then [FailedDeliveryRetrier]. Rows still
 * failing stay FAILED for the next run. WorkManager persists the schedule across reboots.
 */
class SensorHistoryPurgeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        // WorkManager instantiates workers reflectively, so this is the worker's composition root.
        // Purge first so expired rows are not re-sent.
        val deleted = SensorHistoryModule.purger(applicationContext).purge()
        Log.i(TAG, "Purged $deleted expired history row(s) and rollup bucket(s)")
        val resent = SensorHistoryModule.retrier(applicationContext).retry()
        Log.i(TAG, "Re-sent $resent failed history row(s) to Health Connect")
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { error ->
            Log.w(TAG, "History purge failed", error)
            Result.retry()
        },
    )

    companion object {
        private const val TAG = "SensorHistoryPurge"
        private const val UNIQUE_WORK_NAME = "sensor_history_daily_purge"
        private const val MANUAL_WORK_NAME = "sensor_history_manual_run"
        private const val PURGE_INTERVAL_DAYS = 1L

        /** Idempotent: keeps an already-scheduled job instead of resetting its period. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SensorHistoryPurgeWorker>(PURGE_INTERVAL_DAYS, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** One-off run outside the daily schedule (a forced periodic job is skipped until its period is due). */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SensorHistoryPurgeWorker>().build(),
            )
        }
    }
}
