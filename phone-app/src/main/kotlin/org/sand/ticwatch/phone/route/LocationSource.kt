package org.sand.ticwatch.phone.route

import android.annotation.SuppressLint
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Duration
import java.time.Instant

/** A stream of the phone's own GPS fixes; abstracted so the tracker never touches Play services directly (DIP). */
fun interface LocationSource {
    fun fixes(interval: Duration): Flow<RoutePoint>
}

/**
 * [LocationSource] over [FusedLocationProviderClient] at high accuracy. The caller must hold
 * `ACCESS_FINE_LOCATION`; updates stop when the collector is cancelled.
 */
class FusedLocationSource(private val client: FusedLocationProviderClient) : LocationSource {

    @SuppressLint("MissingPermission")
    override fun fixes(interval: Duration): Flow<RoutePoint> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(
                        RoutePoint(
                            recordedAt = Instant.ofEpochMilli(location.time),
                            latitude = location.latitude,
                            longitude = location.longitude,
                            altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
                            horizontalAccuracyMeters = location.accuracy.toDouble().takeIf { location.hasAccuracy() },
                            source = RouteSource.PHONE,
                        ),
                    )
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval.toMillis())
            .setMinUpdateIntervalMillis(interval.toMillis() / MIN_INTERVAL_DIVISOR)
            .build()
        // The callback only hands fixes to the channel; storing them happens on the collector's dispatcher.
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private companion object {
        /** Accept fixes up to twice as often as requested, e.g. every 2 s at a 4 s interval. */
        const val MIN_INTERVAL_DIVISOR = 2
    }
}
