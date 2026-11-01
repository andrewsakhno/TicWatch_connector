package org.sand.ticwatch.watch.watchface

import android.text.format.DateFormat
import android.view.SurfaceHolder
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sand.ticwatch.watch.phonedata.PhonePayloadStores

/**
 * Digital watch face showing the time plus the live heart rate and ambient temperature from
 * [org.sand.ticwatch.watch.sensors.SensorCollectionService], and the phone's cardio readiness between
 * them. Framework-instantiated, so it is also this face's composition root.
 */
class TicWatchFaceService : WatchFaceService() {

    private val readings by lazy { BoundLatestReadings(this) }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        readings.bind()
        val stores = withContext(Dispatchers.IO) { PhonePayloadStores.get(applicationContext) }
        val renderer = TicWatchFaceRenderer(
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            currentUserStyleRepository = currentUserStyleRepository,
            readings = readings,
            cardioReadiness = stores.cardioReadiness.latest,
            is24Hour = { DateFormat.is24HourFormat(this) },
        )
        return WatchFace(WatchFaceType.DIGITAL, renderer)
    }

    override fun onDestroy() {
        readings.unbind()
        super.onDestroy()
    }
}
