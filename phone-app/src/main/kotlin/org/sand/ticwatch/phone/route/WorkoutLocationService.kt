package org.sand.ticwatch.phone.route

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.LocationServices
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.history.SensorHistoryModule
import org.sand.ticwatch.phone.transport.TransportFactory

/**
 * Foreground `location` service hosting [WorkoutLocationTracker] for the duration of a watch
 * workout. Started/stopped by [ServiceWorkoutRouteRecorder] on the watch's workout-state
 * messages; stops itself after [MAX_TRACKING] in case the "ended" message is lost, so a dropped
 * Bluetooth link can't leave GPS running for days.
 */
class WorkoutLocationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val safetyStop = Runnable { stopTracking() }

    private val tracker by lazy {
        WorkoutLocationTracker(
            locationSource = FusedLocationSource(LocationServices.getFusedLocationProviderClient(this)),
            routeLog = SensorHistoryModule.routeStore(this),
            scope = serviceScope,
            announcer = PhoneRouteTrackAnnouncer(TransportFactory.create(this), serviceScope),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            else -> {
                if (!enterForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                tracker.start()
                mainHandler.removeCallbacks(safetyStop)
                mainHandler.postDelayed(safetyStop, MAX_TRACKING.toMillis())
            }
        }
        // A restart after a kill would track a workout that may well have ended.
        return START_NOT_STICKY
    }

    /**
     * Promotes the service to a `location` FGS. Android 14 re-checks the permissions and the
     * app's while-in-use eligibility here and throws instead of refusing quietly; a refusal only
     * means no phone route for this workout, so it's logged rather than allowed to kill the process.
     */
    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Location FGS refused (missing permission or not eligible in background); skipping GPS route", e)
        false
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31+) and FGS-type exceptions (API 34+).
        Log.w(TAG, "Location FGS start not allowed; skipping GPS route", e)
        false
    }

    private fun stopTracking() {
        tracker.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(safetyStop)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_route_channel), NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_route_title))
            .setContentText(getString(R.string.notif_route_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WorkoutLocationService"
        private const val NOTIFICATION_ID = 47
        private const val CHANNEL_ID = "workout_route"
        private const val ACTION_STOP = "org.sand.ticwatch.phone.action.STOP_WORKOUT_ROUTE"
        private val MAX_TRACKING: Duration = Duration.ofHours(6)

        fun startIntent(context: Context): Intent = Intent(context, WorkoutLocationService::class.java)

        fun stopIntent(context: Context): Intent = startIntent(context).setAction(ACTION_STOP)
    }
}
