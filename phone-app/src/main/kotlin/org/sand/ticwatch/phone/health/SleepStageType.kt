package org.sand.ticwatch.phone.health

import androidx.health.connect.client.records.SleepSessionRecord

/**
 * Sleep stage carried as a [SensorType.SLEEP_STAGE] sample's value. [code] is the wire and storage
 * value (it equals the Mobvoi stage code the watch decodes), [healthConnectType] the
 * `SleepSessionRecord.STAGE_TYPE_*` constant it maps to.
 */
enum class SleepStageType(val code: Int, val healthConnectType: Int) {
    AWAKE(1, SleepSessionRecord.STAGE_TYPE_AWAKE),
    LIGHT(2, SleepSessionRecord.STAGE_TYPE_LIGHT),
    DEEP(3, SleepSessionRecord.STAGE_TYPE_DEEP),
    REM(4, SleepSessionRecord.STAGE_TYPE_REM);

    companion object {
        fun fromCode(code: Int): SleepStageType? = entries.firstOrNull { it.code == code }
    }
}
