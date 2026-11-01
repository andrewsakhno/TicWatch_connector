package org.sand.ticwatch.phone.sync

import androidx.annotation.StringRes
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.datalayer.DataLayerPaths

/**
 * Human-readable "what and where" for a Data Layer / Bluetooth-link path, shown next
 * to the Dashboard's progress indicator. Centralized here instead of inline at each
 * report call so the wording stays consistent whether a path is reported from a
 * transport (sending) or [org.sand.ticwatch.phone.datalayer.PhoneMessageRouter]
 * (receiving).
 */
@StringRes
fun describeSyncPath(path: String, direction: SyncDirection): Int = when (path) {
    DataLayerPaths.MESSAGE_INSTALL_APP_REQUEST -> R.string.sync_install_request
    DataLayerPaths.CHANNEL_APK_TRANSFER -> R.string.sync_apk_transfer
    DataLayerPaths.MESSAGE_UNINSTALL_APP_REQUEST -> R.string.sync_uninstall_request
    DataLayerPaths.MESSAGE_INSTALL_APP_RESULT -> R.string.sync_install_result
    DataLayerPaths.MESSAGE_UNINSTALL_APP_RESULT -> R.string.sync_uninstall_result
    DataLayerPaths.MESSAGE_SENSOR_DATA_PUSH -> R.string.sync_sensor_push
    DataLayerPaths.MESSAGE_HEALTH_SCORES -> R.string.sync_health_scores
    DataLayerPaths.MESSAGE_SPACE_WEATHER -> R.string.sync_space_weather
    DataLayerPaths.MESSAGE_BLOOD_PRESSURE_HISTORY -> R.string.sync_bp_history
    DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_RESULT -> R.string.sync_bp_result
    DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_REQUEST -> R.string.sync_bp_request
    DataLayerPaths.MESSAGE_DEBUG_SENSOR_SNAPSHOT_REQUEST -> R.string.sync_sensor_snapshot_request
    DataLayerPaths.MESSAGE_DEBUG_SENSOR_SNAPSHOT_RESPONSE -> R.string.sync_sensor_snapshot_response
    DataLayerPaths.MESSAGE_DEBUG_CAPABILITIES_REQUEST -> R.string.sync_capabilities_request
    DataLayerPaths.MESSAGE_DEBUG_CAPABILITIES_RESPONSE -> R.string.sync_capabilities_response
    DataLayerPaths.MESSAGE_DEBUG_VENDOR_DATA_REQUEST -> R.string.sync_vendor_request
    DataLayerPaths.MESSAGE_DEBUG_VENDOR_DATA_RESPONSE -> R.string.sync_vendor_response
    DataLayerPaths.MESSAGE_DEBUG_SLEEP_LOG_REQUEST -> R.string.sync_sleep_log_request
    DataLayerPaths.MESSAGE_DEBUG_SLEEP_LOG_RESPONSE -> R.string.sync_sleep_log_response
    DataLayerPaths.MESSAGE_DEBUG_LOGS_REQUEST -> R.string.sync_logs_request
    DataLayerPaths.MESSAGE_DEBUG_LOGS_RESPONSE -> R.string.sync_logs_response
    DataLayerPaths.MESSAGE_DEBUG_SCREENSHOT_REQUEST -> R.string.sync_screenshot_request
    DataLayerPaths.MESSAGE_DEBUG_SCREENSHOT_RESPONSE -> R.string.sync_screenshot_response
    DataLayerPaths.MESSAGE_CAPTURE_SNAPSHOT -> R.string.sync_snapshot_request
    DataLayerPaths.MESSAGE_SNAPSHOT_RESULT -> R.string.sync_snapshot_result
    DataLayerPaths.MESSAGE_SNAPSHOT_PROGRESS -> R.string.sync_snapshot_progress
    else -> when (direction) {
        SyncDirection.TO_WATCH -> R.string.sync_to_watch
        SyncDirection.FROM_WATCH -> R.string.sync_from_watch
    }
}
