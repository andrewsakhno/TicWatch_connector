package org.sand.ticwatch.watch.sensors

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.getCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant

/**
 * [SampleSource] backed by one Health Services passive-listener registration.
 * An app gets exactly one passive callback - a second `setPassiveListenerCallback`
 * replaces the first - so interval data types and every [UserActivitySessionTracker]
 * share this single registration instead of each registering its own.
 *
 * Requested data types are narrowed to what the device reports as supported: one
 * unsupported type fails the whole registration.
 */
class HealthServicesPassiveSampleSource(
    context: Context,
    private val intervalBindings: List<IntervalBinding<*>>,
    private val sessionTrackers: List<UserActivitySessionTracker>,
    private val activityObservers: List<UserActivityObserver> = emptyList(),
) : SampleSource {

    private val passiveClient = HealthServices.getClient(context).passiveMonitoringClient

    override fun samples(): Flow<SensorSample> =
        callbackFlow {
            val supported = passiveClient.getCapabilities().supportedDataTypesPassiveMonitoring
            val (requested, unsupported) = intervalBindings.partition { it.dataType in supported }
            Log.i(TAG, "Passive data types: ${requested.map { it.sensorType }}; unsupported: ${unsupported.map { it.sensorType }}")

            val callback = object : PassiveListenerCallback {
                override fun onRegistrationFailed(throwable: Throwable) {
                    Log.w(TAG, "Passive listener registration failed", throwable)
                }

                override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                    toSamples(dataPoints, requested).forEach { trySend(it) }
                }

                override fun onUserActivityInfoReceived(info: UserActivityInfo) {
                    sessionTrackers.forEach { tracker -> tracker.onUserActivity(info)?.let { trySend(it) } }
                    activityObservers.forEach { it.onUserActivity(info) }
                }
            }
            val config = PassiveListenerConfig.Builder()
                .setDataTypes(requested.map<IntervalBinding<*>, DataType<*, *>> { it.dataType }.toSet())
                .setShouldUserActivityInfoBeRequested(true)
                .build()
            passiveClient.setPassiveListenerCallback(config, callback)
            awaitClose { passiveClient.clearPassiveListenerCallbackAsync() }
        }

    private fun toSamples(dataPoints: DataPointContainer, requested: List<IntervalBinding<*>>): List<SensorSample> {
        val bootInstant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        return requested.flatMap { binding -> binding.samples(dataPoints, bootInstant) }
    }

    private companion object {
        const val TAG = "PassiveSampleSource"
    }
}

/** Maps one Health Services interval [dataType] onto the wire [sensorType]. */
class IntervalBinding<T : Number>(
    val dataType: DeltaDataType<T, IntervalDataPoint<T>>,
    val sensorType: SensorType,
) {
    fun samples(dataPoints: DataPointContainer, bootInstant: Instant): List<SensorSample> =
        dataPoints.getData(dataType).map { point ->
            SensorSample(
                type = sensorType,
                value = point.value.toDouble(),
                recordedAt = point.getStartInstant(bootInstant),
                endedAt = point.getEndInstant(bootInstant),
            )
        }
}
