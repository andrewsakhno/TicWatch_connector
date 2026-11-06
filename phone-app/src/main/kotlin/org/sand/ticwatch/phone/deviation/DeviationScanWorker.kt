package org.sand.ticwatch.phone.deviation

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Runs one [DeviationEpisodeScanner] pass, shortly after stored watch pushes, so receiving data never waits for it.
 * Not retried by WorkManager: a failed pass leaves the cursor in place and the next push's run covers the same rows.
 */
class DeviationScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    // WorkManager instantiates workers reflectively, so DeviationModule is this worker's composition root.
    override suspend fun doWork(): Result = try {
        DeviationModule.scanner(applicationContext).scan()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Deviation scan failed", e)
        Result.failure()
    }

    companion object {
        private const val TAG = "DeviationEpisodes"
        private const val WORK_NAME = "deviation_scan"

        /** Lets a burst of pushes settle; shorter than the cloud round's minute, so that round carries the results. */
        private const val SETTLE_DELAY_SECONDS = 30L

        private val lowPriority = Constraints.Builder().setRequiresBatteryNotLow(true).build()

        /** After new data: one pass shortly; further requests meanwhile join it. */
        fun requestSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<DeviationScanWorker>()
                .setInitialDelay(SETTLE_DELAY_SECONDS, TimeUnit.SECONDS)
                .setConstraints(lowPriority)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
