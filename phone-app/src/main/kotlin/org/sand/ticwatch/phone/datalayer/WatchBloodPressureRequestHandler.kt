package org.sand.ticwatch.phone.datalayer

import android.util.Log
import org.sand.ticwatch.phone.health.BloodPressureMeasurement
import org.sand.ticwatch.phone.health.BloodPressureMeasurer
import org.sand.ticwatch.phone.health.SavedMeasurement
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage
import java.time.Clock
import kotlin.math.roundToInt

/** Handles the watch's "measure blood pressure" request. */
fun interface BloodPressureRequestHandler {
    suspend fun onMeasureRequested(payload: ByteArray)
}

/**
 * Estimates and saves blood pressure for the watch, then answers on
 * [DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_RESULT] with a status line; the saved reading itself reaches the watch
 * through the history publisher.
 */
class WatchBloodPressureRequestHandler(
    private val measurer: BloodPressureMeasurer,
    private val transport: DeviceLinkTransport,
    private val clock: Clock,
) : BloodPressureRequestHandler {

    override suspend fun onMeasureRequested(payload: ByteArray) {
        val liveHeartRate = runCatching { BloodPressureWatchPayloads.decodeLiveHeartRate(payload) }
            .onFailure { Log.w(TAG, "Malformed measure request, using the stored heart rate", it) }
            .getOrNull()
        val result = runCatching { measurer.measureAndSave(liveHeartRate) }
            .onFailure { Log.w(TAG, "Blood pressure measurement failed", it) }
            .getOrNull()
        val reply = result?.let(::describe) ?: MeasureReply(false, "Measurement failed")
        Log.i(TAG, "Watch measure request (live HR $liveHeartRate): ${reply.status}")
        val payloadText = BloodPressureWatchPayloads.encodeResult(reply.success, reply.status, clock.instant())
        transport.sendMessage(DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_RESULT, payloadText.toByteArray(Charsets.UTF_8))
            .onFailure { Log.w(TAG, "Sending the measure result to the watch failed", it) }
    }

    private fun describe(result: SavedMeasurement): MeasureReply = when (val measurement = result.measurement) {
        BloodPressureMeasurement.NotCalibrated -> MeasureReply(false, "Calibrate with the cuff first")
        BloodPressureMeasurement.NoHeartRate -> MeasureReply(false, "No heart rate yet - keep the watch on")
        is BloodPressureMeasurement.NotAtRest ->
            MeasureReply(false, "Sit still (${measurement.stepsPerMinute.roundToInt()} steps/min)")
        is BloodPressureMeasurement.Estimated ->
            if (result.saved?.isSuccess == true) MeasureReply(true, "Saved") else MeasureReply(false, "Estimated, saving failed")
    }

    private data class MeasureReply(val success: Boolean, val status: String)

    private companion object {
        const val TAG = "WatchBloodPressure"
    }
}
