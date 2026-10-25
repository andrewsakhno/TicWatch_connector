package org.sand.ticwatch.phone.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.permission.HealthPermission
import java.time.Duration
import java.time.ZoneId

/** [HealthRecordWriter] backed by Jetpack Health Connect. */
class HealthConnectRecordWriter(
    private val context: Context,
    private val client: HealthConnectClient = HealthConnectClient.getOrCreate(context),
    // Zone the samples were measured in; Health Connect consumers use the stored
    // offset to place records on the user's local timeline.
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : HealthRecordWriter {

    override val requiredPermissions: Set<String> = REQUIRED_PERMISSIONS

    override suspend fun hasRequiredPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    /**
     * Inserts each [SensorType]'s records in its own [HealthConnectClient.insertRecords] call
     * rather than one combined call. Health Connect's alpha client has been observed granting
     * [HeartRateRecord]/[StepsRecord] permissions reliably while [RestingHeartRateRecord]'s
     * write permission intermittently reports granted (`getGrantedPermissions()`) yet still
     * throws `SecurityException` at insert time - since `insertRecords` rejects its whole batch
     * on any single permission failure, that one flaky record type was silently blocking heart
     * rate and steps too. Isolating each type means one type's failure no longer sinks the rest.
     */
    override suspend fun write(samples: List<SensorSample>): Result<Unit> {
        val recordsByType = samples
            .groupBy { it.type }
            .mapValues { (type, typeSamples) -> toRecords(type, typeSamples) }
            .filterValues { it.isNotEmpty() }

        var anySucceeded = false
        var lastFailure: Throwable? = null
        for ((type, records) in recordsByType) {
            runCatching { client.insertRecords(records) }
                .onSuccess { anySucceeded = true }
                .onFailure { error ->
                    lastFailure = error
                    Log.w(TAG, "Failed to write $type records to Health Connect", error)
                }
        }

        return if (anySucceeded || recordsByType.isEmpty()) Result.success(Unit) else Result.failure(lastFailure!!)
    }

    private fun toRecords(type: SensorType, samples: List<SensorSample>): List<Record> =
        when (type) {
            // Series type: one record per watch push carrying every sample, instead of one
            // record per ~1s reading, which floods Health Connect with near-identical rows.
            SensorType.HEART_RATE -> listOf(toHeartRateSeries(samples))
            else -> samples.mapNotNull(::toRecord)
        }

    private fun toHeartRateSeries(samples: List<SensorSample>): HeartRateRecord {
        val sorted = samples.sortedBy { it.recordedAt }
        val start = sorted.first().recordedAt
        val end = sorted.last().recordedAt
        return HeartRateRecord(
            startTime = start,
            startZoneOffset = start.atZone(zoneId).offset,
            endTime = end,
            endZoneOffset = end.atZone(zoneId).offset,
            samples = sorted.map { HeartRateRecord.Sample(time = it.recordedAt, beatsPerMinute = it.value.toLong()) },
            metadata = AUTOMATIC_METADATA,
        )
    }

    private fun toRecord(sample: SensorSample): Record? {
        val zoned = sample.recordedAt.atZone(zoneId)
        return when (sample.type) {
            SensorType.HEART_RATE -> toHeartRateSeries(listOf(sample))

            SensorType.STEPS -> StepsRecord(
                startTime = sample.recordedAt,
                startZoneOffset = zoned.offset,
                endTime = sample.recordedAt.plus(Duration.ofMinutes(1)),
                endZoneOffset = zoned.offset,
                count = sample.value.toLong(),
                metadata = AUTOMATIC_METADATA,
            )

            SensorType.RESTING_HEART_RATE -> RestingHeartRateRecord(
                time = sample.recordedAt,
                zoneOffset = zoned.offset,
                beatsPerMinute = sample.value.toLong(),
                metadata = AUTOMATIC_METADATA,
            )

            SensorType.CALORIES -> null // TODO: map to ActiveCaloriesBurnedRecord.
        }
    }

    companion object {
        private const val TAG = "HealthConnectWriter"
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        )

        /** Samples originate from watch sensors pushed automatically, not typed in by the user. */
        private val AUTOMATIC_METADATA = Metadata(recordingMethod = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED)
    }
}
