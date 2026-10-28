package org.sand.ticwatch.phone.datalayer

import android.util.Log
import org.json.JSONObject
import org.sand.ticwatch.phone.snore.SnoreMonitorControl
import org.sand.ticwatch.phone.snapshot.SnapshotResultReceiver
import org.sand.ticwatch.phone.health.SensorDataSyncWorker
import org.sand.ticwatch.phone.route.ExerciseRouteLog
import org.sand.ticwatch.phone.route.WorkoutPayloads
import org.sand.ticwatch.phone.route.WorkoutRouteRecorder
import org.sand.ticwatch.phone.sync.SyncDirection
import org.sand.ticwatch.phone.sync.SyncStatusReporter
import org.sand.ticwatch.phone.watchsettings.WatchSettingsReceiver

/**
 * Business logic for requests arriving from the watch, independent of which transport
 * delivered them (Wear Data Layer or direct Bluetooth) - both
 * `PhoneDataLayerListenerService` and `BluetoothLinkServerService` forward into this
 * one place instead of duplicating the handling. Also the single seam for reporting
 * *incoming* transfers to [syncStatusReporter] - unlike sending, a Data Layer message
 * or a Bluetooth-link frame always arrives as one already-buffered byte array, so
 * there's no read-pace to instrument the way [org.sand.ticwatch.phone.transport.ProgressReportingDeviceLinkTransport]
 * does for sends; reporting here still surfaces size and direction on the Dashboard.
 */
class PhoneMessageRouter(
    private val sensorDataSyncWorker: SensorDataSyncWorker,
    private val workoutRouteRecorder: WorkoutRouteRecorder,
    private val exerciseRouteLog: ExerciseRouteLog,
    private val syncStatusReporter: SyncStatusReporter,
    private val replySink: WatchReplySink,
    /** Health scores, blood pressure history, space weather: re-sent (deduplicated) after every stored push. */
    private val pushPublishers: List<WatchStatePublisher>,
    private val bloodPressureRequests: BloodPressureRequestHandler,
    private val watchSettings: WatchSettingsReceiver,
    private val snoreMonitor: SnoreMonitorControl,
    private val watchScreenshots: WatchScreenshotSink,
    private val snapshotResults: SnapshotResultReceiver,
) {
    /**
     * Handles one incoming message to completion. Returns false when the payload was received
     * but not durably handled, so the transport can tell the sender to keep it and retry
     * rather than drop it (the watch only deletes buffered samples on an acknowledged send).
     */
    suspend fun handleMessage(path: String, payload: ByteArray): Boolean {
        val progress = syncStatusReporter.begin(path, SyncDirection.FROM_WATCH, payload.size.toLong())
        progress.update(payload.size.toLong())
        var handled = true

        when (path) {
            DataLayerPaths.MESSAGE_INSTALL_APP_RESULT,
            DataLayerPaths.MESSAGE_UNINSTALL_APP_RESULT -> {
                Log.i(TAG, "Watch app-management result on $path: ${String(payload, Charsets.UTF_8)}")
            }

            DataLayerPaths.MESSAGE_DEBUG_SENSOR_SNAPSHOT_RESPONSE -> {
                Log.i(TAG, "Watch sensor snapshot: ${String(payload, Charsets.UTF_8)}")
            }

            DataLayerPaths.MESSAGE_DEBUG_CAPABILITIES_RESPONSE,
            DataLayerPaths.MESSAGE_DEBUG_VENDOR_DATA_RESPONSE,
            DataLayerPaths.MESSAGE_DEBUG_SLEEP_LOG_RESPONSE -> {
                // Logcat truncates long lines (~4KB); probe reports are larger.
                String(payload, Charsets.UTF_8).chunked(LOG_CHUNK_LENGTH).forEachIndexed { index, chunk ->
                    Log.i(TAG, "Watch probe $path [$index]: $chunk")
                }
            }

            // Answers a request someone is waiting on (e.g. the MCP `get_watch_logs` tool).
            DataLayerPaths.MESSAGE_DEBUG_LOGS_RESPONSE -> {
                val text = String(payload, Charsets.UTF_8)
                runCatching { JSONObject(text).getString(REQUEST_ID) }
                    .onSuccess { replySink.deliver(it, text) }
                    .onFailure { Log.w(TAG, "Watch log reply without a request id", it) }
            }

            DataLayerPaths.MESSAGE_DEBUG_SCREENSHOT_RESPONSE -> {
                runCatching { watchScreenshots.save(payload) }
                    .onFailure { Log.w(TAG, "Failed to store the watch screenshot", it) }
            }

            DataLayerPaths.MESSAGE_SENSOR_DATA_PUSH -> {
                handled = sensorDataSyncWorker.onSensorDataPush(payload)
                    .onSuccess { count -> Log.i(TAG, "Wrote $count sensor sample(s) to Health Connect") }
                    .onFailure { Log.w(TAG, "Failed to write sensor data to Health Connect; watch will retry", it) }
                    .isSuccess
                if (handled) pushPublishers.forEach(WatchStatePublisher::publish)
            }

            DataLayerPaths.MESSAGE_SNAPSHOT_RESULT -> {
                handled = snapshotResults.onSnapshotResult(payload)
                if (handled) pushPublishers.forEach(WatchStatePublisher::publish)
            }

            DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_REQUEST -> bloodPressureRequests.onMeasureRequested(payload)

            DataLayerPaths.MESSAGE_WATCH_SETTINGS -> watchSettings.onWatchSettings(payload)

            DataLayerPaths.MESSAGE_SNORE_MONITOR -> {
                runCatching { WorkoutPayloads.isWorkoutActive(payload) }
                    .onSuccess { active -> if (active) snoreMonitor.onSleepStarted() else snoreMonitor.onSleepEnded() }
                    .onFailure { Log.w(TAG, "Malformed snore monitor state: ${String(payload, Charsets.UTF_8)}", it) }
            }

            DataLayerPaths.MESSAGE_WORKOUT_STATE -> {
                runCatching { WorkoutPayloads.isWorkoutActive(payload) }
                    .onSuccess { active -> if (active) workoutRouteRecorder.onWorkoutStarted() else workoutRouteRecorder.onWorkoutEnded() }
                    .onFailure { Log.w(TAG, "Malformed workout state: ${String(payload, Charsets.UTF_8)}", it) }
            }

            // Route the watch tracked on its own GPS; stored like phone fixes and joined to the session by time.
            DataLayerPaths.MESSAGE_EXERCISE_ROUTE_PUSH -> {
                handled = runCatching { exerciseRouteLog.insert(WorkoutPayloads.watchRoutePoints(payload)) }
                    .onFailure { Log.w(TAG, "Failed to store the watch's route points; watch will retry", it) }
                    .isSuccess
            }

            else -> Log.d(TAG, "Unhandled message path: $path")
        }
        progress.end()
        return handled
    }

    companion object {
        private const val TAG = "PhoneMessageRouter"
        private const val LOG_CHUNK_LENGTH = 3_000
        const val REQUEST_ID = "request_id"
    }
}
