package org.sand.ticwatch.phone.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.SleepStageQueries
import org.sand.ticwatch.phone.route.ExerciseRouteQueries
import java.time.ZoneId

/** [HealthRecordWriter] backed by Jetpack Health Connect. */
class HealthConnectRecordWriter(
    private val context: Context,
    exerciseRoutes: ExerciseRouteQueries,
    sleepStages: SleepStageQueries,
    history: SensorHistoryQueries,
    private val client: HealthConnectClient = HealthConnectClient.getOrCreate(context),
    // Zone the samples were measured in; Health Connect consumers use the stored
    // offset to place records on the user's local timeline.
    zoneId: ZoneId = ZoneId.systemDefault(),
    private val mappers: Map<SensorType, HealthRecordMapper> = defaultHealthRecordMappers(
        zoneId = zoneId,
        exerciseRoutes = ExerciseRouteFactory(SessionRoutePoints(exerciseRoutes)),
        sleepStages = SleepStageFactory(sleepStages, HeartRateSleepStageEstimator(SampleWindowReader(history))),
        storedReadings = SampleWindowReader(history),
        isFeatureAvailable = { client.features.getFeatureStatus(it) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE },
    ),
) : HealthRecordWriter {

    override val requiredPermissions: Set<String> = mappers.values.flatMap { it.writePermissions }.toSet()

    override suspend fun hasRequiredPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(requiredPermissions)

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
        var anySucceeded = false
        var lastFailure: Throwable? = null
        for ((type, typeSamples) in samples.groupBy { it.type }) {
            // Mapping is inside the per-type isolation too: Health Connect record constructors
            // throw on invalid values, and one bad type must not sink the others.
            runCatching {
                val records = toRecords(type, typeSamples)
                if (records.isEmpty()) return@runCatching
                client.insertRecords(records)
                anySucceeded = true
                Log.i(TAG, "Inserted ${records.size} $type record(s)")
            }.onFailure { error ->
                lastFailure = error
                Log.w(TAG, "Failed to write $type records to Health Connect", error)
            }
        }

        val failure = lastFailure
        return if (anySucceeded || failure == null) Result.success(Unit) else Result.failure(failure)
    }

    private suspend fun toRecords(type: SensorType, samples: List<SensorSample>): List<Record> =
        mappers[type]?.toRecords(samples) ?: emptyList<Record>().also { Log.w(TAG, "No Health Connect mapper for $type") }

    private companion object {
        const val TAG = "HealthConnectWriter"
    }
}
