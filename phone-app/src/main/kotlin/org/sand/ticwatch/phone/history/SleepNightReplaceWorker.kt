package org.sand.ticwatch.phone.history

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.sand.ticwatch.phone.AppContainer
import java.io.File
import java.time.Instant

/**
 * Runs [org.sand.ticwatch.phone.health.SleepNightReplacement] in WorkManager rather than in the
 * debug receiver's goAsync window, which the cached-app freezer can cut short mid-write.
 */
class SleepNightReplaceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val payload = inputData.getString(KEY_PAYLOAD_PATH)?.let(::File)?.takeIf(File::isFile) ?: return Result.failure()
        val from = Instant.ofEpochMilli(inputData.getLong(KEY_FROM, MISSING))
        val to = Instant.ofEpochMilli(inputData.getLong(KEY_TO, MISSING))
        if (!to.isAfter(from)) return Result.failure()
        // WorkManager instantiates workers reflectively, so this is the worker's composition root.
        return AppContainer(applicationContext).sleepNightReplacement.run(from, to, payload.readBytes())
            .fold(onSuccess = { Result.success() }, onFailure = { Result.failure() })
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "sleep_night_replace"
        private const val KEY_PAYLOAD_PATH = "payload_path"
        private const val KEY_FROM = "from_epoch_ms"
        private const val KEY_TO = "to_epoch_ms"
        private const val MISSING = 0L

        fun enqueue(context: Context, payloadPath: String, from: Long, to: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SleepNightReplaceWorker>()
                    .setInputData(workDataOf(KEY_PAYLOAD_PATH to payloadPath, KEY_FROM to from, KEY_TO to to))
                    .build(),
            )
        }
    }
}
