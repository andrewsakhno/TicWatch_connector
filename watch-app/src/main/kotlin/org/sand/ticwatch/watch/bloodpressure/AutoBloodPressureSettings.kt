package org.sand.ticwatch.watch.bloodpressure

/** Whether blood pressure is requested automatically while the wearer rests (synced setting, off by default). */
fun interface AutoBloodPressureSettings {
    fun isEnabled(): Boolean
}
