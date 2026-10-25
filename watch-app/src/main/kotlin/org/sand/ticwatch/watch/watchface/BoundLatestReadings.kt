package org.sand.ticwatch.watch.watchface

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.sand.ticwatch.watch.sensors.LatestReadings
import org.sand.ticwatch.watch.sensors.SensorCollectionService
import org.sand.ticwatch.watch.sensors.SensorType

/**
 * [LatestReadings] of a bound [SensorCollectionService]; empty until it connects. Binds
 * without auto-create: the service is started at boot / app launch, and a watch face must not
 * start a foreground service from the background.
 */
class BoundLatestReadings(private val context: Context) : LatestReadings {

    private val readings = MutableStateFlow<LatestReadings?>(null)
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            readings.value = (binder as? SensorCollectionService.LocalBinder)?.readings
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            readings.value = null
        }
    }

    fun bind() {
        if (bound) return
        bound = context.bindService(Intent(context, SensorCollectionService::class.java), connection, NO_FLAGS)
    }

    fun unbind() {
        if (bound) context.unbindService(connection)
        bound = false
        readings.value = null
    }

    /** Suspends until the service connects, for one-shot readers like a tile; false on timeout. */
    suspend fun awaitConnected(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { readings.filterNotNull().first() } != null

    override fun current(type: SensorType): Double? = readings.value?.current(type)

    private companion object {
        const val NO_FLAGS = 0
    }
}
