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
import androidx.health.services.client.data.SampleDataPoint
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.getCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant

/**
 * [SampleSource] backed by one Health Services passive-listener registration.
 * An app gets exactly one passive callback - a second `setPassiveListenerCallback`
 * replaces the first - so every [PassiveBinding] and every [UserActivitySessionTracker]
 * share this single registration instead of each registering its own.
 *
 * Requested data types are narrowed to what the device reports as supported: one
 * unsupported type fails the whole registration.
 *
 * Health Services once kept delivering steps but no heart rate for 8.5 h while the watch was worn (2026-11-03,
 * until the app restarted), so steps arriving [HEART_RATE_STALL] after the last heart rate while [worn] register the
 * callback again.
 */
class HealthServicesPassiveSampleSource(
    context: Context,
    private val bindings: List<PassiveBinding>,
    private val sessionTrackers: List<UserActivitySessionTracker>,
    private val activityObservers: List<UserActivityObserver> = emptyList(),
    private val worn: WindowGate = WindowGate { true },
) : SampleSource {

    private val passiveClient = HealthServices.getClient(context).passiveMonitoringClient

    override fun samples(): Flow<SensorSample> =
        callbackFlow {
            val supported = passiveClient.getCapabilities().supportedDataTypesPassiveMonitoring
            val (requested, unsupported) = bindings.partition { it.dataType in supported }
            Log.i(TAG, "Passive data types: ${requested.map { it.sensorType }}; unsupported: ${unsupported.map { it.sensorType }}")

            val config = PassiveListenerConfig.Builder()
                .setDataTypes(requested.map { it.dataType }.toSet())
                .setShouldUserActivityInfoBeRequested(true)
                .build()
            val watchesHeartRate = requested.any { it.sensorType == SensorType.HEART_RATE }
            var lastHeartRateAt = SystemClock.elapsedRealtime()
            var lastReRegisterAt = 0L
            val callback = object : PassiveListenerCallback {
                override fun onRegistrationFailed(throwable: Throwable) {
                    Log.w(TAG, "Passive listener registration failed", throwable)
                }

                override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                    val samples = toSamples(dataPoints, requested)
                    samples.forEach { trySend(it) }
                    val now = SystemClock.elapsedRealtime()
                    if (samples.any { it.type == SensorType.HEART_RATE }) {
                        lastHeartRateAt = now
                        return
                    }
                    val stalled = now - lastHeartRateAt > HEART_RATE_STALL_MS && now - lastReRegisterAt > HEART_RATE_STALL_MS
                    if (watchesHeartRate && stalled && samples.any { it.type == SensorType.STEPS && it.value > 0 } && worn.isOpen()) {
                        Log.w(TAG, "No passive heart rate for ${(now - lastHeartRateAt) / MILLIS_PER_MINUTE} min while worn and walking; registering again")
                        lastReRegisterAt = now
                        passiveClient.setPassiveListenerCallback(config, this)
                    }
                }

                override fun onUserActivityInfoReceived(info: UserActivityInfo) {
                    sessionTrackers.forEach { tracker -> tracker.onUserActivity(info)?.let { trySend(it) } }
                    activityObservers.forEach { it.onUserActivity(info) }
                }
            }
            passiveClient.setPassiveListenerCallback(config, callback)
            awaitClose { passiveClient.clearPassiveListenerCallbackAsync() }
        }

    private fun toSamples(dataPoints: DataPointContainer, requested: List<PassiveBinding>): List<SensorSample> {
        val bootInstant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        return requested.flatMap { binding -> binding.samples(dataPoints, bootInstant) }
    }

    private companion object {
        const val TAG = "PassiveSampleSource"
        const val MILLIS_PER_MINUTE = 60_000L

        /** Passive heart rate arrives every 1-10 s while worn; 20 min without any while walking is a stalled registration. */
        const val HEART_RATE_STALL_MS = 20 * MILLIS_PER_MINUTE
    }
}

/** Maps one Health Services passive [dataType] onto wire samples of [sensorType]. */
interface PassiveBinding {
    val dataType: DataType<*, *>
    val sensorType: SensorType
    fun samples(dataPoints: DataPointContainer, bootInstant: Instant): List<SensorSample>
}

/** [PassiveBinding] for an interval data type (steps, calories...): one interval sample per point. */
class IntervalBinding<T : Number>(
    override val dataType: DeltaDataType<T, IntervalDataPoint<T>>,
    override val sensorType: SensorType,
) : PassiveBinding {
    override fun samples(dataPoints: DataPointContainer, bootInstant: Instant): List<SensorSample> =
        dataPoints.getData(dataType).map { point ->
            SensorSample(
                type = sensorType,
                value = point.value.toDouble(),
                recordedAt = point.getStartInstant(bootInstant),
                endedAt = point.getEndInstant(bootInstant),
            )
        }
}

/** [PassiveBinding] for a point data type (heart rate): one point sample per reading. */
class SampleBinding<T : Number>(
    override val dataType: DeltaDataType<T, SampleDataPoint<T>>,
    override val sensorType: SensorType,
) : PassiveBinding {
    override fun samples(dataPoints: DataPointContainer, bootInstant: Instant): List<SensorSample> =
        dataPoints.getData(dataType).map { point ->
            SensorSample(type = sensorType, value = point.value.toDouble(), recordedAt = point.getTimeInstant(bootInstant))
        }
}
