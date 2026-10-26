package org.sand.ticwatch.phone.health

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.time.TimeRangeFilter
import org.sand.ticwatch.phone.history.SleepHistoryEraser
import java.time.Instant

/**
 * One-shot repair for a night an older watch decoder recorded wrongly (e.g. split into fragments):
 * deletes this app's Health Connect sleep sessions and the stored sleep history in [from, to), then
 * ingests [payload] - a watch sensor push re-derived from the watch's raw sleep log - through the
 * normal [ingest] path, so history, rollups and Health Connect get it exactly like a live push.
 */
class SleepNightReplacement(
    private val client: HealthConnectClient,
    private val eraser: SleepHistoryEraser,
    private val ingest: SensorDataSyncWorker,
) {
    suspend fun run(from: Instant, to: Instant, payload: ByteArray): Result<Int> = runCatching {
        client.deleteRecords(SleepSessionRecord::class, TimeRangeFilter.between(from, to))
        val erased = eraser.eraseSleep(from, to)
        val written = ingest.onSensorDataPush(payload).getOrThrow()
        Log.i(TAG, "Replaced sleep in [$from, $to): erased $erased stored session(s), ingested $written sample(s)")
        written
    }.onFailure { Log.w(TAG, "Sleep night replacement failed", it) }

    private companion object {
        const val TAG = "SleepNightReplacement"
    }
}
