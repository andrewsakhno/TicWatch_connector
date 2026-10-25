package org.sand.ticwatch.phone.history

import android.util.Log
import org.sand.ticwatch.phone.health.HealthRecordWriter
import org.sand.ticwatch.phone.health.SensorSample
import java.time.Clock

/**
 * Decorator adding the acknowledgement pattern to any [HealthRecordWriter]: every sample is
 * first persisted to [historyLog] as pending, then handed to [delivery], which marks the rows
 * delivered or failed. A history error is logged and never blocks the Health Connect write.
 */
class AcknowledgingHealthRecordWriter(
    delegate: HealthRecordWriter,
    private val historyLog: SensorHistoryLog,
    private val delivery: AcknowledgedDelivery,
    private val clock: Clock,
) : HealthRecordWriter by delegate {

    override suspend fun write(samples: List<SensorSample>): Result<Unit> {
        val ids = runCatching { historyLog.recordPending(samples, clock.instant()) }
            .onFailure { Log.w(TAG, "Failed to record ${samples.size} sample(s) in history", it) }
            .getOrNull()
        return delivery.deliver(samples, ids)
    }

    private companion object {
        const val TAG = "AckHealthRecordWriter"
    }
}
