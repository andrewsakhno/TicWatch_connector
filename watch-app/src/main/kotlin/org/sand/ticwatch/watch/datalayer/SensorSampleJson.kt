package org.sand.ticwatch.watch.datalayer

import org.json.JSONObject
import org.sand.ticwatch.watch.sensors.SensorSample

/** Wire shape of one sample (`/sensors/push` entries, snapshot samples), shared with phone-app's decoder. */
object SensorSampleJson {
    const val TYPE = "type"
    const val VALUE = "value"
    const val RECORDED_AT = "recordedAtEpochMs"

    /** Present only on interval samples. */
    const val ENDED_AT = "endedAtEpochMs"

    fun encode(sample: SensorSample): JSONObject = JSONObject()
        .put(TYPE, sample.type.name)
        .put(VALUE, sample.value)
        .put(RECORDED_AT, sample.recordedAt.toEpochMilli())
        .apply { sample.endedAt?.let { put(ENDED_AT, it.toEpochMilli()) } }
}
