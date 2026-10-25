package org.sand.ticwatch.watch.sensors

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.SampleDataPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant

/** [SensorCollector] backed by the Health Services Measure API for live readings. */
class HealthServicesSensorCollector(context: Context) : SensorCollector {

    private val measureClient = HealthServices.getClient(context).measureClient

    override fun heartRateSamples(): Flow<SensorSample> =
        measure(DataType.HEART_RATE_BPM, SensorType.HEART_RATE)

    override fun stepSamples(): Flow<SensorSample> =
        measure(DataType.STEPS_PER_MINUTE, SensorType.STEPS)

    private fun <T : Number> measure(
        dataType: DeltaDataType<T, SampleDataPoint<T>>,
        sensorType: SensorType,
    ): Flow<SensorSample> =
        callbackFlow {
            val callback = object : MeasureCallback {
                override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit

                override fun onDataReceived(data: DataPointContainer) {
                    data.getData(dataType).forEach { point ->
                        trySend(SensorSample(sensorType, point.value.toDouble(), Instant.now()))
                    }
                }
            }
            measureClient.registerMeasureCallback(dataType, callback)
            awaitClose { measureClient.unregisterMeasureCallbackAsync(dataType, callback) }
        }
}
