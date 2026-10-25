package org.sand.ticwatch.watch.datalayer

/**
 * Message/channel path contract shared with phone-app's WearDataLayerWatchAppManager.
 * Both sides must agree on these strings; keep them in sync manually since phone-app
 * and watch-app are independent Gradle projects with no shared module.
 */
object DataLayerPaths {
    const val MESSAGE_INSTALL_APP_REQUEST = "/app/install/request"
    const val MESSAGE_INSTALL_APP_RESULT = "/app/install/result"
    const val MESSAGE_UNINSTALL_APP_REQUEST = "/app/uninstall/request"
    const val MESSAGE_UNINSTALL_APP_RESULT = "/app/uninstall/result"

    const val CHANNEL_APK_TRANSFER = "/app/install/apk"

    const val MESSAGE_SENSOR_DATA_PUSH = "/sensors/push"

    /** Watch -> phone: a workout started/ended on the watch (`{"active": bool}`), so the phone can track GPS. */
    const val MESSAGE_WORKOUT_STATE = "/workout/state"

    /** Watch -> phone: GPS fixes the watch recorded on its own during a workout (JSON array). */
    const val MESSAGE_EXERCISE_ROUTE_PUSH = "/workout/route"

    /** Debug bridge: phone asks for a one-shot sensor reading, watch replies with one.
     *  Goes over the same Wear Data Layer session as everything else, so it works
     *  whenever the watch and phone are paired - independent of either being reachable
     *  by adb. See phone-app's DebugBridgeReceiver for the adb-triggerable entry point. */
    const val MESSAGE_DEBUG_SENSOR_SNAPSHOT_REQUEST = "/debug/sensors/request"
    const val MESSAGE_DEBUG_SENSOR_SNAPSHOT_RESPONSE = "/debug/sensors/response"

    /** Debug bridge: phone asks which health data/sensors this watch exposes. */
    const val MESSAGE_DEBUG_CAPABILITIES_REQUEST = "/debug/capabilities/request"
    const val MESSAGE_DEBUG_CAPABILITIES_RESPONSE = "/debug/capabilities/response"

    /** Debug bridge: phone asks the watch to sample Mobvoi's vendor sensors/providers. */
    const val MESSAGE_DEBUG_VENDOR_DATA_REQUEST = "/debug/vendor/request"
    const val MESSAGE_DEBUG_VENDOR_DATA_RESPONSE = "/debug/vendor/response"

    /** Debug bridge: phone pulls the tail of the watch's raw sleep-sensor log. */
    const val MESSAGE_DEBUG_SLEEP_LOG_REQUEST = "/debug/sleep-log/request"
    const val MESSAGE_DEBUG_SLEEP_LOG_RESPONSE = "/debug/sleep-log/response"
}
