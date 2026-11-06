package org.sand.ticwatch.phone.datalayer

import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.sand.ticwatch.phone.health.BloodPressureCalibration
import org.sand.ticwatch.phone.health.BloodPressureCalibrationStore
import org.sand.ticwatch.phone.health.SystolicOffsetSource
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import java.time.Clock
import kotlin.math.roundToInt

/**
 * Sends the active blood pressure calibration and the current systolic offset on
 * [DataLayerPaths.MESSAGE_BLOOD_PRESSURE_CALIBRATION] after sensor pushes, so the watch can estimate on its own while
 * the phone is out of reach. Deduplicated: the offset is rounded to [OFFSET_STEPS_PER_MMHG], so only a new
 * calibration or a moved offset is re-sent. Mirrored by watch-app's `bloodpressure/BloodPressureCalibration.kt`.
 */
class WatchBloodPressureCalibrationPublisher(
    calibrations: BloodPressureCalibrationStore,
    offset: SystolicOffsetSource,
    clock: Clock,
    transport: DeviceLinkTransport,
    scope: CoroutineScope,
) : WatchStatePublisher {

    private val delegate = DedupingPayloadPublisher(DataLayerPaths.MESSAGE_BLOOD_PRESSURE_CALIBRATION, transport, scope, TAG) {
        calibrations.bloodPressureCalibration()?.let { encode(it, offset.offsetAt(clock.instant())) }
    }

    override fun publish() = delegate.publish()

    override suspend fun publishAndWait() = delegate.publishAndWait()

    private fun encode(calibration: BloodPressureCalibration, systolicOffset: Double): String = JSONObject()
        .put(SYSTOLIC, calibration.systolic)
        .put(DIASTOLIC, calibration.diastolic)
        .put(HEART_RATE, calibration.heartRate)
        .apply { calibration.rmssdMs?.let { put(RMSSD, it) } }
        .apply { calibration.skinTemperatureCelsius?.let { put(SKIN_TEMPERATURE, it) } }
        .put(CALIBRATED_AT, calibration.calibratedAt.toEpochMilli())
        .put(SYSTOLIC_OFFSET, (systolicOffset * OFFSET_STEPS_PER_MMHG).roundToInt() / OFFSET_STEPS_PER_MMHG)
        .toString()

    private companion object {
        const val TAG = "BpCalibrationPublisher"
        const val SYSTOLIC = "systolic"
        const val DIASTOLIC = "diastolic"
        const val HEART_RATE = "heart_rate"
        const val RMSSD = "rmssd"
        const val SKIN_TEMPERATURE = "skin_temperature"
        const val CALIBRATED_AT = "calibrated_at"
        const val SYSTOLIC_OFFSET = "systolic_offset"
        const val OFFSET_STEPS_PER_MMHG = 10.0
    }
}
