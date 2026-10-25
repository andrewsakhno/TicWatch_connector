package org.sand.ticwatch.phone.history

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import java.io.File
import java.time.Instant

/**
 * Transfer-history database in the app's private data area (`databases/`, not cache, so the
 * system never evicts it). Process-wide singleton: the activity and both link services each
 * build their own `AppContainer`, and they must share one connection pool.
 *
 * Raw rows (`sensor_history`, kept per the Settings retention, the source for delivery
 * acks/retries) plus [HistoryProjection]s that outlive them: per-tier aggregates
 * (`sensor_rollup`, see [RollupResolution]), sleep sessions (`sleep_session`) and their stages
 * (`sleep_stage`, see [SleepStageWriter]), plus workout
 * GPS fixes (`exercise_route_point`, see [SqliteExerciseRouteStore]).
 * Enum columns hold compact integer codes ([SensorTypeCodes], [DeliveryStatus.code]).
 */
class SensorHistoryDatabase private constructor(
    context: Context,
    private val rollupWriter: HistoryProjection,
    private val sleepSessionWriter: HistoryProjection,
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    /** Main file plus WAL/SHM/journal side files - what the database really occupies on disk. */
    val files: List<File> = context.getDatabasePath(DATABASE_NAME).let { main ->
        listOf(main) + SIDE_FILE_SUFFIXES.map { File(main.path + it) }
    }

    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < VERSION_COMPACT_SCHEMA) migrateToCompactSchema(db)
        if (oldVersion < VERSION_SLEEP_SESSIONS) addSleepSessions(db)
        if (oldVersion < VERSION_EXERCISE_ROUTES) createRoutePointTable(db)
        if (oldVersion < VERSION_SLEEP_STAGES) createSleepStageTable(db)
    }

    /** v5 added sleep stages; nothing to backfill, the watch never sent SLEEP_STAGE before. */
    private fun createSleepStageTable(db: SQLiteDatabase) {
        // Keyed by start like sleep_session, so a re-sent stage replaces itself.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ${SleepStageTable.NAME} (
                ${SleepStageTable.START} INTEGER PRIMARY KEY,
                ${SleepStageTable.END} INTEGER NOT NULL,
                ${SleepStageTable.STAGE_TYPE} INTEGER NOT NULL,
                ${SleepStageTable.SESSION_START} INTEGER
            )
            """.trimIndent(),
        )
    }

    /** v4 added workout GPS fixes; nothing to backfill, routes were never recorded before. */
    private fun createRoutePointTable(db: SQLiteDatabase) {
        // (time, source) key: a re-sent watch batch replaces itself, and phone + watch fixes can coexist.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ${RoutePointTable.NAME} (
                ${RoutePointTable.RECORDED_AT} INTEGER NOT NULL,
                ${RoutePointTable.SOURCE} INTEGER NOT NULL,
                ${RoutePointTable.LATITUDE} REAL NOT NULL,
                ${RoutePointTable.LONGITUDE} REAL NOT NULL,
                ${RoutePointTable.ALTITUDE} REAL,
                ${RoutePointTable.ACCURACY} REAL,
                PRIMARY KEY (${RoutePointTable.RECORDED_AT}, ${RoutePointTable.SOURCE})
            ) WITHOUT ROWID
            """.trimIndent(),
        )
    }

    /** v3 added `sleep_session`; seed it from the SLEEP rows the raw retention still holds. */
    private fun addSleepSessions(db: SQLiteDatabase) {
        createSleepSessionTable(db)
        backfill(db, sleepSessionWriter, "${Table.TYPE} = ${SensorTypeCodes.code(SensorType.SLEEP)}")
    }

    private fun createSleepSessionTable(db: SQLiteDatabase) {
        // Keyed by start: the rowid itself, so no separate index; IF NOT EXISTS as a v1 upgrade runs createSchema first.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ${SleepSessionTable.NAME} (
                ${SleepSessionTable.START} INTEGER PRIMARY KEY,
                ${SleepSessionTable.END} INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createSchema(db: SQLiteDatabase) {
        // INTEGER PRIMARY KEY without AUTOINCREMENT: the rowid itself, no sqlite_sequence upkeep.
        db.execSQL(
            """
            CREATE TABLE ${Table.NAME} (
                ${Table.ID} INTEGER PRIMARY KEY,
                ${Table.TYPE} INTEGER NOT NULL,
                ${Table.VALUE} REAL NOT NULL,
                ${Table.RECORDED_AT} INTEGER NOT NULL,
                ${Table.ENDED_AT} INTEGER,
                ${Table.RECEIVED_AT} INTEGER NOT NULL,
                ${Table.DELIVERY_STATUS} INTEGER NOT NULL,
                ${Table.DELIVERY_UPDATED_AT} INTEGER
            )
            """.trimIndent(),
        )
        // Serves MCP range queries; the daily received_at purge scans instead of paying for an index.
        db.execSQL(
            "CREATE INDEX idx_${Table.NAME}_type_recorded ON ${Table.NAME}(${Table.TYPE}, ${Table.RECORDED_AT})",
        )
        // Partial index: only the few FAILED rows, so the daily retry never scans the table.
        db.execSQL(
            "CREATE INDEX idx_${Table.NAME}_failed ON ${Table.NAME}(${Table.ID}) " +
                "WHERE ${Table.DELIVERY_STATUS} = ${DeliveryStatus.FAILED.code}",
        )
        db.execSQL(
            """
            CREATE TABLE ${RollupTable.NAME} (
                ${RollupTable.TYPE} INTEGER NOT NULL,
                ${RollupTable.RESOLUTION} INTEGER NOT NULL,
                ${RollupTable.BUCKET_START} INTEGER NOT NULL,
                ${RollupTable.MIN} REAL NOT NULL,
                ${RollupTable.MAX} REAL NOT NULL,
                ${RollupTable.SUM} REAL NOT NULL,
                ${RollupTable.COUNT} INTEGER NOT NULL,
                PRIMARY KEY (${RollupTable.TYPE}, ${RollupTable.RESOLUTION}, ${RollupTable.BUCKET_START})
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        createSleepSessionTable(db)
        createRoutePointTable(db)
        createSleepStageTable(db)
    }

    /** v1 stored enums as TEXT and had no rollups: convert rows in place, then backfill the rollups. */
    private fun migrateToCompactSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE ${Table.NAME} RENAME TO $LEGACY_TABLE")
        createSchema(db)
        val typeCase = SensorType.entries.joinToString(" ") { "WHEN '${it.name}' THEN ${SensorTypeCodes.code(it)}" }
        val statusCase = DeliveryStatus.entries.joinToString(" ") { "WHEN '${it.name}' THEN ${it.code}" }
        val knownTypes = SensorType.entries.joinToString(",") { "'${it.name}'" }
        db.execSQL(
            """
            INSERT INTO ${Table.NAME} (
                ${Table.ID}, ${Table.TYPE}, ${Table.VALUE}, ${Table.RECORDED_AT}, ${Table.ENDED_AT},
                ${Table.RECEIVED_AT}, ${Table.DELIVERY_STATUS}, ${Table.DELIVERY_UPDATED_AT}
            )
            SELECT ${Table.ID}, CASE ${Table.TYPE} $typeCase END, ${Table.VALUE}, ${Table.RECORDED_AT},
                ${Table.ENDED_AT}, ${Table.RECEIVED_AT}, CASE ${Table.DELIVERY_STATUS} $statusCase END,
                ${Table.DELIVERY_UPDATED_AT}
            FROM $LEGACY_TABLE WHERE ${Table.TYPE} IN ($knownTypes)
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE $LEGACY_TABLE")
        backfill(db, rollupWriter)
    }

    /** Replays the raw rows matching [filter] (all by default) through [projection], page by page. */
    private fun backfill(db: SQLiteDatabase, projection: HistoryProjection, filter: String? = null) {
        var afterId = 0L
        while (true) {
            val page = db.query(
                Table.NAME,
                SAMPLE_COLUMNS + Table.ID,
                "${Table.ID} > ?" + filter?.let { " AND $it" }.orEmpty(),
                arrayOf(afterId.toString()),
                null,
                null,
                "${Table.ID} ASC",
                BACKFILL_PAGE_SIZE.toString(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        afterId = cursor.getLong(cursor.getColumnIndexOrThrow(Table.ID))
                        add(cursor.readSample())
                    }
                }
            }
            if (page.isEmpty()) return
            projection.accumulate(db, page)
        }
    }

    object Table {
        const val NAME = "sensor_history"
        const val ID = "id"
        const val TYPE = "type"
        const val VALUE = "value"
        const val RECORDED_AT = "recorded_at_epoch_ms"
        const val ENDED_AT = "ended_at_epoch_ms"
        const val RECEIVED_AT = "received_at_epoch_ms"
        const val DELIVERY_STATUS = "delivery_status"
        const val DELIVERY_UPDATED_AT = "delivery_updated_at_epoch_ms"
    }

    object RollupTable {
        const val NAME = "sensor_rollup"
        const val TYPE = "type"
        const val RESOLUTION = "resolution"
        const val BUCKET_START = "bucket_start_epoch_ms"
        const val MIN = "min_value"
        const val MAX = "max_value"
        const val SUM = "sum_value"
        const val COUNT = "sample_count"
    }

    object SleepSessionTable {
        const val NAME = "sleep_session"
        const val START = "start_epoch_ms"
        const val END = "end_epoch_ms"
    }

    /**
     * [SESSION_START] is the enclosing `sleep_session` row's start (its key);
     * [STAGE_TYPE] is an [org.sand.ticwatch.phone.health.SleepStageType.code].
     */
    object SleepStageTable {
        const val NAME = "sleep_stage"
        const val START = "start_epoch_ms"
        const val END = "end_epoch_ms"
        const val STAGE_TYPE = "stage_type"
        const val SESSION_START = "session_start_epoch_ms"
    }

    object RoutePointTable {
        const val NAME = "exercise_route_point"
        const val RECORDED_AT = "recorded_at_epoch_ms"
        const val SOURCE = "source"
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"
        const val ALTITUDE = "altitude_m"
        const val ACCURACY = "horizontal_accuracy_m"
    }

    companion object {
        private const val DATABASE_NAME = "sensor_history.db"
        private const val VERSION_COMPACT_SCHEMA = 2
        private const val VERSION_SLEEP_SESSIONS = 3
        private const val VERSION_EXERCISE_ROUTES = 4
        private const val VERSION_SLEEP_STAGES = 5
        private const val DATABASE_VERSION = VERSION_SLEEP_STAGES
        private const val LEGACY_TABLE = "sensor_history_v1"
        private const val BACKFILL_PAGE_SIZE = 2000
        private val SIDE_FILE_SUFFIXES = listOf("-wal", "-shm", "-journal")

        /** Columns [readSample] needs. */
        val SAMPLE_COLUMNS = arrayOf(Table.TYPE, Table.VALUE, Table.RECORDED_AT, Table.ENDED_AT)

        @Volatile
        private var instance: SensorHistoryDatabase? = null

        fun getInstance(
            context: Context,
            rollupWriter: HistoryProjection,
            sleepSessionWriter: HistoryProjection,
        ): SensorHistoryDatabase = instance ?: synchronized(this) {
            instance ?: SensorHistoryDatabase(context.applicationContext, rollupWriter, sleepSessionWriter)
                .also { instance = it }
        }
    }
}

/** Reads the [SensorHistoryDatabase.SAMPLE_COLUMNS] of the current `sensor_history` row. */
internal fun Cursor.readSample(): SensorSample {
    val endedAtColumn = getColumnIndexOrThrow(SensorHistoryDatabase.Table.ENDED_AT)
    return SensorSample(
        type = SensorTypeCodes.type(getInt(getColumnIndexOrThrow(SensorHistoryDatabase.Table.TYPE))),
        value = getDouble(getColumnIndexOrThrow(SensorHistoryDatabase.Table.VALUE)),
        recordedAt = Instant.ofEpochMilli(getLong(getColumnIndexOrThrow(SensorHistoryDatabase.Table.RECORDED_AT))),
        endedAt = if (isNull(endedAtColumn)) null else Instant.ofEpochMilli(getLong(endedAtColumn)),
    )
}
