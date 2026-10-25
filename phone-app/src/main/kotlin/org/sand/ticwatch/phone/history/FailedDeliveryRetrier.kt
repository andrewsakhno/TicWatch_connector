package org.sand.ticwatch.phone.history

/**
 * Re-sends history rows whose Health Connect write failed. A failed type's batch was rejected
 * atomically by `insertRecords`, so nothing of it reached Health Connect and a resend cannot
 * duplicate records. Walks the rows by id in pages, so rows failing again are not re-read.
 */
class FailedDeliveryRetrier(
    private val historyLog: SensorHistoryLog,
    private val delivery: AcknowledgedDelivery,
) {
    /** Returns the number of rows that were re-sent. */
    suspend fun retry(): Int {
        var afterId = 0L
        var resent = 0
        while (true) {
            val rows = historyLog.findFailed(afterId, PAGE_SIZE)
            if (rows.isEmpty()) return resent
            delivery.deliver(rows.map { it.sample }, rows.map { it.id })
            resent += rows.size
            afterId = rows.last().id
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
    }
}
