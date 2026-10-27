package org.sand.ticwatch.watch.bloodpressure

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.phonedata.PhonePayloadSink
import org.sand.ticwatch.watch.sensors.ReadingHistory
import org.sand.ticwatch.watch.sensors.SensorType
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage
import java.time.Clock

/** Asks the phone to estimate and save blood pressure now. */
fun interface BloodPressureMeasureRequester {
    fun request()
}

/** Asks the phone the same way, suspending until the request was sent or failed (for callers holding a wake lock). */
fun interface AwaitedBloodPressureRequester {
    suspend fun requestAndAwait()
}

/** Delivers the samples still buffered on the watch, so the phone estimates from the freshest wrist signals. */
fun interface PendingSampleFlusher {
    suspend fun flush()
}

/**
 * Flushes the buffered samples, then sends [DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_REQUEST] with the latest
 * on-screen heart rate (live Measure API readings aren't buffered for the phone). The phone answers on
 * [DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_RESULT]; when the request can't be sent, [results] gets a local
 * failure instead so the screen doesn't wait for an answer that won't come.
 */
class PhoneBloodPressureMeasureRequester(
    private val flusher: PendingSampleFlusher,
    private val readings: ReadingHistory,
    private val transport: DeviceLinkTransport,
    private val results: PhonePayloadSink,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : BloodPressureMeasureRequester, AwaitedBloodPressureRequester {

    override fun request() {
        scope.launch { requestAndAwait() }
    }

    override suspend fun requestAndAwait() {
        runCatching { flusher.flush() }.onFailure { Log.w(TAG, "Flushing samples before the request failed", it) }
        val heartRate = readings.history(SensorType.HEART_RATE).value.lastOrNull()
        val payload = BloodPressurePayloads.encodeRequest(heartRate?.value, heartRate?.at)
        transport.sendMessage(DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_REQUEST, payload.toByteArray(Charsets.UTF_8))
            .onSuccess { Log.i(TAG, "Blood pressure requested (HR $heartRate)") }
            .onFailure { error ->
                Log.w(TAG, "Blood pressure request not delivered", error)
                results.accept(BloodPressurePayloads.encodeResult(BloodPressureMeasureResult(false, PHONE_UNREACHABLE, clock.instant())))
            }
    }

    private companion object {
        const val TAG = "BloodPressureRequester"
        const val PHONE_UNREACHABLE = "Phone unreachable"
    }
}
