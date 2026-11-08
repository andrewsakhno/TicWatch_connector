package org.sand.ticwatch.phone.watchbattery

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.datalayer.WatchStatePublisher
import org.sand.ticwatch.phone.health.HealthRecordWriter
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.text.UiText
import org.sand.ticwatch.phone.text.uiText

/** The watch's charge level as it reported it at [reportedAt] (watch clock). */
data class WatchBatteryStatus(val percent: Int, val charging: Boolean, val reportedAt: Instant) {
    /** Older than [STALE_AFTER] (watch out of range, sync stuck): shown greyed out. */
    fun isStale(now: Instant): Boolean = Duration.between(reportedAt, now) > STALE_AFTER

    /** "64%", with a charging mark. */
    fun levelLabel(): String = if (charging) "$CHARGING_MARK$percent%" else "$percent%"

    /** "25 min ago". */
    fun ageLabel(now: Instant): UiText {
        val age = Duration.between(reportedAt, now)
        return when {
            age < Duration.ofMinutes(1) -> uiText(R.string.age_now)
            age < Duration.ofHours(1) -> uiText(R.string.age_minutes, age.toMinutes())
            age < Duration.ofDays(1) -> uiText(R.string.age_hours, age.toHours())
            else -> uiText(R.string.age_days, age.toDays())
        }
    }

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(2)
        const val CHARGING_MARK = "⚡"
    }
}

/** JSON on [org.sand.ticwatch.phone.datalayer.DataLayerPaths.MESSAGE_WATCH_BATTERY], mirrored by watch-app's encoder. */
object WatchBatteryPayload {
    private const val PERCENT = "percent"
    private const val CHARGING = "charging"
    private const val AT = "at"

    fun decode(payload: ByteArray): WatchBatteryStatus = JSONObject(String(payload, Charsets.UTF_8)).let {
        WatchBatteryStatus(
            percent = it.getInt(PERCENT),
            charging = it.getBoolean(CHARGING),
            reportedAt = Instant.ofEpochMilli(it.getLong(AT)),
        )
    }
}

/** Last reported watch battery; the title bar observes [status], the widget reads its value. */
interface WatchBatteryStore {
    val status: StateFlow<WatchBatteryStatus?>
    fun save(status: WatchBatteryStatus)
}

/**
 * [WatchBatteryStore] in SharedPreferences, one per process, so the activity's title bar sees what the link
 * service's router stored. First access reads the preferences file - call off the main thread.
 */
class SharedPreferencesWatchBatteryStore private constructor(private val prefs: SharedPreferences) : WatchBatteryStore {

    private val state = MutableStateFlow(read())
    override val status: StateFlow<WatchBatteryStatus?> = state

    override fun save(status: WatchBatteryStatus) {
        prefs.edit()
            .putInt(KEY_PERCENT, status.percent)
            .putBoolean(KEY_CHARGING, status.charging)
            .putLong(KEY_REPORTED_AT, status.reportedAt.toEpochMilli())
            .apply()
        state.value = status
    }

    private fun read(): WatchBatteryStatus? {
        if (!prefs.contains(KEY_PERCENT)) return null
        return WatchBatteryStatus(
            percent = prefs.getInt(KEY_PERCENT, 0),
            charging = prefs.getBoolean(KEY_CHARGING, false),
            reportedAt = Instant.ofEpochMilli(prefs.getLong(KEY_REPORTED_AT, 0L)),
        )
    }

    companion object {
        private const val PREFS_NAME = "watch_battery"
        private const val KEY_PERCENT = "percent"
        private const val KEY_CHARGING = "charging"
        private const val KEY_REPORTED_AT = "reported_at_epoch_ms"

        @Volatile private var instance: SharedPreferencesWatchBatteryStore? = null

        fun get(context: Context): SharedPreferencesWatchBatteryStore = instance ?: synchronized(this) {
            instance ?: SharedPreferencesWatchBatteryStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}

/** Handles [org.sand.ticwatch.phone.datalayer.DataLayerPaths.MESSAGE_WATCH_BATTERY]. */
fun interface WatchBatteryReceiver {
    fun onWatchBattery(payload: ByteArray)
}

/** Appends the reported level to the sensor history, at most one point per [minInterval] (flushes come every few seconds live). */
class WatchBatteryHistoryRecorder(
    private val writer: HealthRecordWriter,
    private val scope: CoroutineScope,
    private val minInterval: Duration = Duration.ofMinutes(5),
) {
    private var lastRecordedAt: Instant? = null

    @Synchronized
    fun record(status: WatchBatteryStatus) {
        val last = lastRecordedAt
        if (last != null && Duration.between(last, status.reportedAt) < minInterval) return
        lastRecordedAt = status.reportedAt
        scope.launch {
            writer.write(listOf(SensorSample(SensorType.WATCH_BATTERY_LEVEL, status.percent.toDouble(), status.reportedAt)))
        }
    }
}

/** Stores the reported level, keeps its history and re-renders [onChanged] (the advisor widget). */
class StoringWatchBatteryReceiver(
    private val store: WatchBatteryStore,
    private val history: WatchBatteryHistoryRecorder,
    private val onChanged: WatchStatePublisher,
) : WatchBatteryReceiver {
    override fun onWatchBattery(payload: ByteArray) {
        runCatching { WatchBatteryPayload.decode(payload) }
            .onSuccess { status ->
                store.save(status)
                history.record(status)
                onChanged.publish()
            }
            .onFailure { Log.w(TAG, "Malformed watch battery status: ${String(payload, Charsets.UTF_8)}", it) }
    }

    private companion object {
        const val TAG = "WatchBatteryReceiver"
    }
}
