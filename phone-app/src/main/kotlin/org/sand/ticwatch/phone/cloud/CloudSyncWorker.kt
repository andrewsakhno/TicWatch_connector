package org.sand.ticwatch.phone.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.sand.ticwatch.phone.datalayer.WatchStatePublisher
import java.util.concurrent.TimeUnit

/**
 * Runs one [CloudSyncEngine] round. Scheduled hourly on an unmetered network (the server is on the home
 * LAN), a minute after each stored watch push (batching bursts), and at once on the title bar's tap.
 * Never retried by WorkManager: whatever is left stays pending for the next round.
 */
class CloudSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    // WorkManager instantiates workers reflectively, so CloudModule is this worker's composition root.
    override suspend fun doWork(): Result {
        CloudModule.engine(applicationContext).sync()
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "cloud_sync_periodic"
        private const val SOON_WORK_NAME = "cloud_sync_soon"
        private const val NOW_WORK_NAME = "cloud_sync_now"
        private const val PERIOD_HOURS = 1L
        private const val SOON_DELAY_SECONDS = 60L

        private val lanConstraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        /** Idempotent: keeps an already-scheduled job instead of resetting its period. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(lanConstraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** After new data: one round a minute from now; further requests meanwhile join it. */
        fun requestSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setInitialDelay(SOON_DELAY_SECONDS, TimeUnit.SECONDS)
                .setConstraints(lanConstraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** User-requested round, no network constraint (a server reachable over mobile data counts too). */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<CloudSyncWorker>().build(),
            )
        }
    }
}

/** Starts or stops cloud rounds; the seam the UI and the push pipeline talk to. */
interface CloudSyncScheduler {
    fun requestSoon()
    fun runNow()
}

class WorkManagerCloudSyncScheduler(private val context: Context) : CloudSyncScheduler {
    override fun requestSoon() = CloudSyncWorker.requestSoon(context)
    override fun runNow() = CloudSyncWorker.runNow(context)
}

/** After every stored watch push / BP change / stored strip: flag the icon "not synced" and queue a round. */
class CloudSyncTrigger(
    private val status: CloudSyncStatusStore,
    private val scheduler: CloudSyncScheduler,
) : WatchStatePublisher {
    override fun publish() {
        status.update { if (it.link == CloudLink.DISABLED) it else it.copy(pending = maxOf(it.pending, 1)) }
        scheduler.requestSoon()
    }
}
