package org.sand.ticwatch.watch.route

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import java.time.Duration

/** Delivers GPS fixes between [start] and [stop]; restarting replaces the previous listener. */
interface LocationFixSource {
    fun start(onFix: (RouteFix) -> Unit)
    fun stop()
}

/**
 * The watch's own GNSS receiver through the platform [LocationManager] (no Play Services fused
 * provider needed). Needs ACCESS_FINE_LOCATION plus ACCESS_BACKGROUND_LOCATION: workouts start
 * while the app is in the background, where a health-type foreground service has no while-in-use
 * location access; without the grant the start is skipped and logged.
 */
class GpsLocationFixSource(
    private val context: Context,
    private val locationManager: LocationManager,
    private val interval: Duration,
) : LocationFixSource {

    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission") // Checked in hasLocationAccess.
    override fun start(onFix: (RouteFix) -> Unit) {
        stop()
        if (!hasLocationAccess()) {
            Log.w(TAG, "Location permission missing; the watch won't record a GPS route")
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "GPS provider disabled; the watch won't record a GPS route")
            return
        }
        val newListener = LocationListener { location -> onFix(location.toRouteFix()) }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, interval.toMillis(), MIN_DISTANCE_METERS, newListener, Looper.getMainLooper(),
        )
        listener = newListener
        Log.i(TAG, "GPS route recording started")
    }

    override fun stop() {
        val current = listener ?: return
        locationManager.removeUpdates(current)
        listener = null
        Log.i(TAG, "GPS route recording stopped")
    }

    private fun hasLocationAccess(): Boolean = REQUIRED_PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun Location.toRouteFix() = RouteFix(
        recordedAtEpochMs = time,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) altitude else null,
        horizontalAccuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
    )

    private companion object {
        const val TAG = "GpsLocationFixSource"
        const val MIN_DISTANCE_METERS = 0f
        val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
    }
}
