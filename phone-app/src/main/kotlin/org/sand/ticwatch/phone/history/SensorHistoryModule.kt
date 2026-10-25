package org.sand.ticwatch.phone.history

import android.content.Context
import org.sand.ticwatch.phone.health.HealthConnectRecordWriter
import org.sand.ticwatch.phone.health.HealthRecordWriter
import java.time.Clock
import java.time.ZoneId

/**
 * Wiring for the transfer-history feature, shared by `AppContainer` and the reflectively
 * created [SensorHistoryPurgeWorker] so both get the same object graph.
 */
object SensorHistoryModule {
    val clock: Clock = Clock.systemUTC()

    /** Zone that day rollup buckets and MCP-facing timestamps are expressed in. */
    val zone: ZoneId = ZoneId.systemDefault()

    private val rollupWriter = SensorRollupWriter(zone)
    private val sleepSessionWriter = SleepSessionWriter()
    private val sleepStageWriter = SleepStageWriter()

    fun store(context: Context): SqliteSensorHistoryStore = SqliteSensorHistoryStore(
        SensorHistoryDatabase.getInstance(context, rollupWriter, sleepSessionWriter),
        // Sessions before stages: a stage arriving with its session links to it on insert.
        listOf(rollupWriter, sleepSessionWriter, sleepStageWriter),
        zone,
    )

    fun routeStore(context: Context): SqliteExerciseRouteStore =
        SqliteExerciseRouteStore(SensorHistoryDatabase.getInstance(context, rollupWriter, sleepSessionWriter))

    fun retentionStore(context: Context): HistoryRetentionStore = SharedPreferencesEnumSettingStore(
        context, HistorySettingKeys.RETENTION, HistoryRetention.entries, HistoryRetention.DEFAULT,
    )

    fun sleepSessionRetentionStore(context: Context): SleepSessionRetentionStore = SharedPreferencesEnumSettingStore(
        context, HistorySettingKeys.SLEEP_SESSION_RETENTION, SleepSessionRetention.entries, SleepSessionRetention.DEFAULT,
    )

    fun purger(context: Context): SensorHistoryPurger =
        SensorHistoryPurger(
            store(context), routeStore(context), retentionStore(context), sleepSessionRetentionStore(context), clock, zone,
        )

    fun delivery(writer: HealthRecordWriter, historyLog: SensorHistoryLog): AcknowledgedDelivery =
        AcknowledgedDelivery(writer, historyLog, clock)

    fun retrier(context: Context): FailedDeliveryRetrier {
        val store = store(context)
        return FailedDeliveryRetrier(store, delivery(HealthConnectRecordWriter(context, routeStore(context), store), store))
    }
}
