package org.sand.ticwatch.watch.datalayer

import android.content.Context
import org.sand.ticwatch.watch.sensors.DeviceCapabilityProbe
import org.sand.ticwatch.watch.sensors.FileRawWindowEventLog
import org.sand.ticwatch.watch.sensors.FileTemperatureChannelLog
import org.sand.ticwatch.watch.sensors.HealthDeviceCapabilityProbe
import org.sand.ticwatch.watch.sensors.VendorDataProbe
import org.sand.ticwatch.watch.sensors.VendorSensorRecorder
import java.io.File

/** Debug bridge: a probe answered on [responsePath] when [requestPath] arrives. */
class DebugProbeRoute(
    val requestPath: String,
    val responsePath: String,
    val probe: DeviceCapabilityProbe,
)

fun defaultDebugProbeRoutes(context: Context): List<DebugProbeRoute> = listOf(
    DebugProbeRoute(
        DataLayerPaths.MESSAGE_DEBUG_CAPABILITIES_REQUEST,
        DataLayerPaths.MESSAGE_DEBUG_CAPABILITIES_RESPONSE,
        HealthDeviceCapabilityProbe(context),
    ),
    DebugProbeRoute(
        DataLayerPaths.MESSAGE_DEBUG_VENDOR_DATA_REQUEST,
        DataLayerPaths.MESSAGE_DEBUG_VENDOR_DATA_RESPONSE,
        VendorDataProbe(context),
    ),
    DebugProbeRoute(
        DataLayerPaths.MESSAGE_DEBUG_SLEEP_LOG_REQUEST,
        DataLayerPaths.MESSAGE_DEBUG_SLEEP_LOG_RESPONSE,
        VendorSensorRecorder.probe(
            listOf(
                VendorSensorRecorder.SLEEP_LOG_FILE_NAME,
                VendorSensorRecorder.TEMPERATURE_LOG_FILE_NAME,
                FileTemperatureChannelLog.LOG_FILE_NAME,
                FileRawWindowEventLog.RESPIRATION_LOG_FILE_NAME,
            )
                .map { File(context.filesDir, it) },
        ),
    ),
)
