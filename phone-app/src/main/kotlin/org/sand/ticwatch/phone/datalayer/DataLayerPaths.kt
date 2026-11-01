package org.sand.ticwatch.phone.datalayer

/**
 * Message/channel path contract shared with watch-app's WatchDataLayerListenerService.
 * Both sides must agree on these strings; keep them in sync manually since phone-app
 * and watch-app are independent Gradle projects with no shared module.
 */
object DataLayerPaths {
    const val CAPABILITY_WATCH_APP = "ticwatch_connector_watch_app"

    const val MESSAGE_INSTALL_APP_REQUEST = "/app/install/request"
    const val MESSAGE_INSTALL_APP_RESULT = "/app/install/result"
    const val MESSAGE_UNINSTALL_APP_REQUEST = "/app/uninstall/request"
    const val MESSAGE_UNINSTALL_APP_RESULT = "/app/uninstall/result"

    const val CHANNEL_APK_TRANSFER = "/app/install/apk"

    const val MESSAGE_SENSOR_DATA_PUSH = "/sensors/push"

    /** Watch -> phone: a workout started/ended on the watch (`{"active": bool}`), so the phone can track GPS. */
    const val MESSAGE_WORKOUT_STATE = "/workout/state"

    /** Phone -> watch: the phone gets good GPS fixes for the running workout (`{"active": true}`, renewed every 2 min). */
    const val MESSAGE_PHONE_ROUTE_TRACK = "/workout/phone-track"

    /** Watch -> phone: a sleep session opened/closed and the phone's microphone should listen for snoring (`{"active": bool}`). */
    const val MESSAGE_SNORE_MONITOR = "/sleep/snore-monitor"

    /** Watch -> phone: GPS fixes the watch recorded on its own during a workout (JSON array). */
    const val MESSAGE_EXERCISE_ROUTE_PUSH = "/workout/route"

    /** Watch -> phone: the watch's battery (`{"percent", "charging", "at"}`), sent with every sync flush. */
    const val MESSAGE_WATCH_BATTERY = "/status/battery"

    /** Phone -> watch: today's derived scores (sleep score, HRV status, load, stress, light) as JSON. */
    const val MESSAGE_HEALTH_SCORES = "/insights/scores"

    /** Phone -> watch: NOAA geomagnetic (Kp) and solar activity, today + forecast days, as JSON. */
    const val MESSAGE_SPACE_WEATHER = "/insights/space-weather"

    /** Phone -> watch: cardio readiness score + latest blood pressure and pulse with their colour levels, as JSON. */
    const val MESSAGE_CARDIO_READINESS = "/insights/readiness"

    /** Both ways: battery-relevant watch features (`{"auto_blood_pressure", "log_capture", "changed_at"}`), newest change wins. */
    const val MESSAGE_WATCH_SETTINGS = "/settings/watch"

    /** Watch -> phone: estimate and save blood pressure now (`{"heart_rate": bpm, "heart_rate_at": epochMs}`, optional). */
    const val MESSAGE_BLOOD_PRESSURE_MEASURE_REQUEST = "/blood-pressure/measure"

    /** Phone -> watch: outcome of a measure request (`{"success", "status", "at"}`). */
    const val MESSAGE_BLOOD_PRESSURE_MEASURE_RESULT = "/blood-pressure/measure/result"

    /** Phone -> watch: the latest saved readings, cuff and wrist estimates, newest first (JSON). */
    const val MESSAGE_BLOOD_PRESSURE_HISTORY = "/blood-pressure/history"

    /** Phone -> watch: take a Baseline Snapshot now (`{"request_id": id}`, optional; echoed in the result). */
    const val MESSAGE_CAPTURE_SNAPSHOT = "/command/capture_snapshot"

    /** Watch -> phone: a snapshot's outcome and samples (`{"status", "message", "samples": [...], "request_id"?}`). */
    const val MESSAGE_SNAPSHOT_RESULT = "/snapshot_result"

    /** Watch -> phone: the stage a phone-requested snapshot just entered (`{"request_id", "stage"}`). */
    const val MESSAGE_SNAPSHOT_PROGRESS = "/snapshot_progress"

    /** Phone -> watch: deliver the buffered samples (and battery) now instead of at the next sync window (`{"request_id"}`). */
    const val MESSAGE_SYNC_NOW = "/command/sync_now"

    /** Watch -> phone: the sync-now flush ended (`{"request_id", "delivered"}`); every pushed batch arrived before it. */
    const val MESSAGE_SYNC_NOW_RESULT = "/command/sync_now/result"

    /** Phone -> watch: the app is on screen (`{"active": true}`, renewed every 30 s) or left it (`false`); live = 5 s flushes. */
    const val MESSAGE_LIVE_SYNC = "/sync/live"

    /** Phone -> watch: record a 30 s rhythm strip (`{"request_id": id}`, optional; echoed in progress and result). */
    const val MESSAGE_ECG_CAPTURE = "/command/ecg_capture"

    /** Watch -> phone: beats recorded since the last progress (`{"beats": [{"t", "rr", "ok"}], "request_id"?}`). */
    const val MESSAGE_ECG_PROGRESS = "/ecg/progress"

    /** Watch -> phone: the finished strip (`{"status", "message", "started_at", "finished_at", "beats", "request_id"?}`). */
    const val MESSAGE_ECG_RESULT = "/ecg/result"

    /** Debug bridge: phone asks for a one-shot sensor reading, watch replies with one.
     *  Goes over the same Wear Data Layer session as everything else, so it works
     *  whenever the watch and phone are paired - independent of either being reachable
     *  by adb. See DebugBridgeReceiver for the adb-triggerable entry point. */
    const val MESSAGE_DEBUG_SENSOR_SNAPSHOT_REQUEST = "/debug/sensors/request"
    const val MESSAGE_DEBUG_SENSOR_SNAPSHOT_RESPONSE = "/debug/sensors/response"

    /** Debug bridge: phone asks which health data/sensors the watch exposes. */
    const val MESSAGE_DEBUG_CAPABILITIES_REQUEST = "/debug/capabilities/request"
    const val MESSAGE_DEBUG_CAPABILITIES_RESPONSE = "/debug/capabilities/response"

    /** Debug bridge: phone asks the watch to sample Mobvoi's vendor sensors/providers. */
    const val MESSAGE_DEBUG_VENDOR_DATA_REQUEST = "/debug/vendor/request"
    const val MESSAGE_DEBUG_VENDOR_DATA_RESPONSE = "/debug/vendor/response"
    const val MESSAGE_DEBUG_RAW_PPG_REQUEST = "/debug/raw-ppg/request"
    const val MESSAGE_DEBUG_RAW_PPG_RESPONSE = "/debug/raw-ppg/response"

    /** Debug bridge: phone pulls the tail of the watch's raw sleep-sensor log. */
    const val MESSAGE_DEBUG_SLEEP_LOG_REQUEST = "/debug/sleep-log/request"
    const val MESSAGE_DEBUG_SLEEP_LOG_RESPONSE = "/debug/sleep-log/response"

    /** Debug bridge: phone queries the watch app's own rotating logcat files (JSON filter in, JSON lines out). */
    const val MESSAGE_DEBUG_LOGS_REQUEST = "/debug/logs/request"
    const val MESSAGE_DEBUG_LOGS_RESPONSE = "/debug/logs/response"

    /** Debug bridge: phone asks for a PNG of the watch app's visible screen (`{"page": n}`, optional), watch answers base64 JSON. */
    const val MESSAGE_DEBUG_SCREENSHOT_REQUEST = "/debug/screenshot/request"
    const val MESSAGE_DEBUG_SCREENSHOT_RESPONSE = "/debug/screenshot/response"
}
