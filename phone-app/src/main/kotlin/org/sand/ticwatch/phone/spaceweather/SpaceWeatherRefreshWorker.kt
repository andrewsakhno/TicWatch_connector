package org.sand.ticwatch.phone.spaceweather

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Periodic WorkManager job (every [REFRESH_INTERVAL_HOURS] h, network required): [SpaceWeatherRefresher.refresh]. */
class SpaceWeatherRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        // WorkManager instantiates workers reflectively, so this is the worker's composition root.
        SpaceWeatherModule.refresher(applicationContext).refresh()
    }.fold(
        onSuccess = { weather ->
            Log.i(TAG, "Space weather refreshed: Kp ${weather.current?.kp}, ${weather.days.size} day(s)")
            Result.success()
        },
        onFailure = { error ->
            Log.w(TAG, "Space weather refresh failed", error)
            Result.retry()
        },
    )

    companion object {
        private const val TAG = "SpaceWeatherRefresh"
        private const val UNIQUE_WORK_NAME = "space_weather_refresh"
        private const val MANUAL_WORK_NAME = "space_weather_manual_refresh"
        private const val REFRESH_INTERVAL_HOURS = 3L

        private val CONSTRAINTS = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Idempotent: keeps an already-scheduled job instead of resetting its period. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SpaceWeatherRefreshWorker>(REFRESH_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(CONSTRAINTS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** One-off refresh outside the schedule (a forced periodic job is skipped until its period is due). */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SpaceWeatherRefreshWorker>().setConstraints(CONSTRAINTS).build(),
            )
        }
    }
}
