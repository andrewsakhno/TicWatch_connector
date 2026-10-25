package org.sand.ticwatch.watch.sensors

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Read side of the latest value per [SensorType], for displays that just render a snapshot. */
fun interface LatestReadings {
    fun current(type: SensorType): Double?
}

/**
 * The latest value of every [SensorType] collected this process lifetime, observable per
 * type. Owned by [SensorCollectionService] and handed to the app UI and the watch face over
 * its local binder.
 */
class LiveReadings : LatestReadings {
    private val values: Map<SensorType, MutableStateFlow<Double?>> =
        SensorType.entries.associateWith { MutableStateFlow(null) }

    fun latest(type: SensorType): StateFlow<Double?> = values.getValue(type)

    override fun current(type: SensorType): Double? = values.getValue(type).value

    fun record(sample: SensorSample) {
        values.getValue(sample.type).value = sample.value
    }
}
