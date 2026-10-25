package org.sand.ticwatch.phone.health

import android.content.SharedPreferences
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

/**
 * One-shot repair for records older builds wrote wrongly: deletes every [recordType]
 * record this app wrote (Health Connect's range delete only touches the caller's own
 * records), then clears [derivedState] so a phone-side estimator re-publishes today's
 * daily record. Used for the per-sample resting-HR flood and for active-only calories
 * that were written as total calories.
 */
class LegacyRecordCleanup(
    private val client: HealthConnectClient,
    private val recordType: KClass<out Record>,
    private val derivedState: SharedPreferences? = null,
) {
    suspend fun run(): Result<Unit> = runCatching {
        client.deleteRecords(recordType, TimeRangeFilter.before(Instant.now()))
        derivedState?.edit()?.clear()?.apply()
        Log.i(TAG, "Deleted this app's ${recordType.simpleName} records")
        Unit
    }.onFailure { Log.w(TAG, "${recordType.simpleName} cleanup failed", it) }

    private companion object {
        const val TAG = "LegacyRecordCleanup"
    }
}
