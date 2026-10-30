package org.sand.ticwatch.watch.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import org.json.JSONObject
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.datalayer.FlushCompanion
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage
import java.time.Clock
import java.time.Instant

/** The watch's charge level at [readAt]. */
data class BatteryStatus(val percent: Int, val charging: Boolean, val readAt: Instant)

fun interface BatteryStatusReader {
    fun read(): BatteryStatus?
}

/** Reads the sticky `ACTION_BATTERY_CHANGED` broadcast - no receiver stays registered, so no wakeups of its own. */
class StickyIntentBatteryStatusReader(context: Context, private val clock: Clock) : BatteryStatusReader {
    private val appContext = context.applicationContext

    override fun read(): BatteryStatus? {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, UNKNOWN)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, UNKNOWN)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, UNKNOWN)
        return BatteryStatus(
            percent = level * PERCENT / scale,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            readAt = clock.instant(),
        )
    }

    private companion object {
        const val UNKNOWN = -1
        const val PERCENT = 100
    }
}

/** JSON on [DataLayerPaths.MESSAGE_WATCH_BATTERY], mirrored by phone-app's `WatchBatteryPayload`. */
object WatchBatteryPayload {
    private const val PERCENT = "percent"
    private const val CHARGING = "charging"
    private const val AT = "at"

    fun encode(status: BatteryStatus): ByteArray = JSONObject()
        .put(PERCENT, status.percent)
        .put(CHARGING, status.charging)
        .put(AT, status.readAt.toEpochMilli())
        .toString()
        .toByteArray(Charsets.UTF_8)
}

/** Rides on every sync flush (radio already up), so the phone's widget and title bar follow within one sync interval. */
class WatchBatteryReporter(
    private val reader: BatteryStatusReader,
    private val transport: DeviceLinkTransport,
) : FlushCompanion {
    override suspend fun onFlush() {
        val status = reader.read() ?: return
        transport.sendMessage(DataLayerPaths.MESSAGE_WATCH_BATTERY, WatchBatteryPayload.encode(status))
            .onFailure { Log.d(TAG, "Battery status not delivered; next flush retries", it) }
    }

    private companion object {
        const val TAG = "WatchBatteryReporter"
    }
}
