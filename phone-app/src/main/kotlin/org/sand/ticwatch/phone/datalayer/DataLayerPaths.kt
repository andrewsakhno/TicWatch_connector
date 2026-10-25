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

    /** Debug bridge: phone asks for a one-shot sensor reading, watch replies with one.
     *  Goes over the same Wear Data Layer session as everything else, so it works
     *  whenever the watch and phone are paired - independent of either being reachable
     *  by adb. See DebugBridgeReceiver for the adb-triggerable entry point. */
    const val MESSAGE_DEBUG_SENSOR_SNAPSHOT_REQUEST = "/debug/sensors/request"
    const val MESSAGE_DEBUG_SENSOR_SNAPSHOT_RESPONSE = "/debug/sensors/response"
}
