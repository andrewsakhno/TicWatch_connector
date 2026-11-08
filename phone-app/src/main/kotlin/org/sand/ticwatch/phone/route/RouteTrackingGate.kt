package org.sand.ticwatch.phone.route

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Why a phone-side route can't be recorded right now. */
enum class RouteTrackingBlocker {
    /** ACCESS_FINE_LOCATION not granted at all. */
    LOCATION_PERMISSION_MISSING,

    /** App is in the background and "Allow all the time" (ACCESS_BACKGROUND_LOCATION) isn't granted. */
    BACKGROUND_LOCATION_MISSING,

    /** FOREGROUND_SERVICE_LOCATION not granted (Android 14+). */
    FOREGROUND_SERVICE_PERMISSION_MISSING,
}

/** Decides up front whether Android would let the app start a `location` foreground service. */
fun interface RouteTrackingGate {
    /** @return the first blocker found, or null when the service may be started. */
    fun blocker(): RouteTrackingBlocker?
}

/**
 * [RouteTrackingGate] mirroring Android 14's FGS-location prerequisites: a runtime location
 * grant, the FGS-location permission, and - while the app isn't in the foreground - background
 * location, which is the only exemption that applies to a Data Layer-triggered start.
 */
class PermissionRouteTrackingGate(private val context: Context) : RouteTrackingGate {

    override fun blocker(): RouteTrackingBlocker? = when {
        !isGranted(Manifest.permission.ACCESS_FINE_LOCATION) -> RouteTrackingBlocker.LOCATION_PERMISSION_MISSING
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !isGranted(Manifest.permission.FOREGROUND_SERVICE_LOCATION) ->
            RouteTrackingBlocker.FOREGROUND_SERVICE_PERMISSION_MISSING
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isInForeground() &&
            !isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
            RouteTrackingBlocker.BACKGROUND_LOCATION_MISSING
        else -> null
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isInForeground(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
