package org.sand.ticwatch.phone.history

import android.util.Log
import org.sand.ticwatch.phone.health.HealthRecordWriter
import org.sand.ticwatch.phone.health.SensorSample
import java.time.Clock

/**
 * Writes samples through [writer] one sensor type at a time and acknowledges each type's
 * history rows as delivered or failed by that write's outcome. Shared by the live sync path
 * and the daily retry of failed rows. History errors are logged and never block the write.
 */
class AcknowledgedDelivery(
    private val writer: HealthRecordWriter,
    private val historyLog: SensorHistoryLog,
    private val clock: Clock,
) {
    /** [rowIds] are aligned with [samples], or null when the samples could not be recorded. */
    suspend fun deliver(samples: List<SensorSample>, rowIds: List<Long>?): Result<Unit> {
        var anySucceeded = false
        var lastFailure: Throwable? = null
        // Per-type writes mirror the writer's own per-type isolation and give per-type acks.
        for ((_, typeSamples) in samples.withIndex().groupBy { it.value.type }) {
            val result = writer.write(typeSamples.map { it.value })
            result.onSuccess { anySucceeded = true }.onFailure { lastFailure = it }

            rowIds?.let { ids ->
                val typeIds = typeSamples.map { ids[it.index] }
                val status = if (result.isSuccess) DeliveryStatus.DELIVERED else DeliveryStatus.FAILED
                runCatching { historyLog.markDelivery(typeIds, status, clock.instant()) }
                    .onFailure { Log.w(TAG, "Failed to mark ${typeIds.size} history row(s) $status", it) }
            }
        }

        val failure = lastFailure
        return if (anySucceeded || failure == null) Result.success(Unit) else Result.failure(failure)
    }

    private companion object {
        const val TAG = "AcknowledgedDelivery"
    }
}
