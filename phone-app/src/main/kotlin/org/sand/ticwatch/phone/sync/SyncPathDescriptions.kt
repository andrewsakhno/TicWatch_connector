package org.sand.ticwatch.phone.sync

import org.sand.ticwatch.phone.datalayer.DataLayerPaths

/**
 * Human-readable "what and where" for a Data Layer / Bluetooth-link path, shown next
 * to the Dashboard's progress indicator. Centralized here instead of inline at each
 * report call so the wording stays consistent whether a path is reported from a
 * transport (sending) or [org.sand.ticwatch.phone.datalayer.PhoneMessageRouter]
 * (receiving).
 */
fun describeSyncPath(path: String, direction: SyncDirection): String = when (path) {
    DataLayerPaths.MESSAGE_INSTALL_APP_REQUEST -> "Sending install command to watch"
    DataLayerPaths.CHANNEL_APK_TRANSFER -> "Sending watch-app APK to watch"
    DataLayerPaths.MESSAGE_UNINSTALL_APP_REQUEST -> "Sending uninstall command to watch"
    DataLayerPaths.MESSAGE_INSTALL_APP_RESULT -> "Receiving install result from watch"
    DataLayerPaths.MESSAGE_UNINSTALL_APP_RESULT -> "Receiving uninstall result from watch"
    DataLayerPaths.MESSAGE_SENSOR_DATA_PUSH -> "Receiving sensor data from watch"
    DataLayerPaths.MESSAGE_HEALTH_SCORES -> "Sending health scores to watch"
    DataLayerPaths.MESSAGE_SPACE_WEATHER -> "Sending space weather to watch"
    DataLayerPaths.MESSAGE_BLOOD_PRESSURE_HISTORY -> "Sending blood pressure readings to watch"
    DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_RESULT -> "Sending blood pressure result to watch"
    DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_REQUEST -> "Blood pressure request from watch"
    DataLayerPaths.MESSAGE_DEBUG_SENSOR_SNAPSHOT_REQUEST -> "Requesting sensor snapshot from watch"
    DataLayerPaths.MESSAGE_DEBUG_SENSOR_SNAPSHOT_RESPONSE -> "Receiving sensor snapshot from watch"
    DataLayerPaths.MESSAGE_DEBUG_CAPABILITIES_REQUEST -> "Requesting capabilities from watch"
    DataLayerPaths.MESSAGE_DEBUG_CAPABILITIES_RESPONSE -> "Receiving capabilities from watch"
    DataLayerPaths.MESSAGE_DEBUG_VENDOR_DATA_REQUEST -> "Requesting vendor sensor probe from watch"
    DataLayerPaths.MESSAGE_DEBUG_VENDOR_DATA_RESPONSE -> "Receiving vendor sensor probe from watch"
    DataLayerPaths.MESSAGE_DEBUG_SLEEP_LOG_REQUEST -> "Requesting sleep-sensor log from watch"
    DataLayerPaths.MESSAGE_DEBUG_SLEEP_LOG_RESPONSE -> "Receiving sleep-sensor log from watch"
    DataLayerPaths.MESSAGE_DEBUG_LOGS_REQUEST -> "Requesting app log from watch"
    DataLayerPaths.MESSAGE_DEBUG_LOGS_RESPONSE -> "Receiving app log from watch"
    DataLayerPaths.MESSAGE_DEBUG_SCREENSHOT_REQUEST -> "Requesting screenshot from watch"
    DataLayerPaths.MESSAGE_DEBUG_SCREENSHOT_RESPONSE -> "Receiving screenshot from watch"
    DataLayerPaths.MESSAGE_CAPTURE_SNAPSHOT -> "Requesting snapshot from watch"
    DataLayerPaths.MESSAGE_SNAPSHOT_RESULT -> "Receiving snapshot from watch"
    else -> when (direction) {
        SyncDirection.TO_WATCH -> "Sending data to watch"
        SyncDirection.FROM_WATCH -> "Receiving data from watch"
    }
}
