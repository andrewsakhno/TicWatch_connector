package org.sand.ticwatch.phone.deviation

import org.sand.ticwatch.phone.health.WorkoutActivitySource
import android.content.Context
import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.history.SensorHistoryModule
import org.sand.ticwatch.phone.medication.CardioDatabase
import org.sand.ticwatch.phone.medication.RoomMedicationIntakeLog
import java.time.Duration

/** Wiring for deviation episodes, shared by `AppContainer`, cloud upload and the reflectively created [DeviationScanWorker]. */
object DeviationModule {

    fun log(context: Context): RoomDeviationEpisodeLog =
        RoomDeviationEpisodeLog(CardioDatabase.getInstance(context).deviationEpisodes())

    fun asker(context: Context): DeviationQuestionAsker = log(context).let { log ->
        DeviationQuestionAsker(
            log, log, SystemDeviationQuestionNotifier(context, SensorHistoryModule.zone),
            DeviationQuestionPolicy(SensorHistoryModule.zone), SensorHistoryModule.clock,
        )
    }

    @Volatile private var corridorSource: HeartRateCorridorSource? = null

    /** One per process, local store only; shared by the scanner and the watch publisher. */
    fun corridors(context: Context): HeartRateCorridorSource = corridorSource ?: synchronized(this) {
        corridorSource ?: CachingHeartRateCorridorSource(
            SensorHistoryModule.store(context).let { store ->
                RollupHeartRateCorridorSource(store, WorkoutActivitySource(store), PersonalCorridorCalculator())
            },
            CORRIDOR_MAX_AGE,
        ).also { corridorSource = it }
    }

    /** Local store only: a scan must not wait for the cloud server. */
    fun scanner(context: Context): DeviationEpisodeScanner {
        val history = SensorHistoryModule.store(context)
        return DeviationEpisodeScanner(
            newRows = history,
            reader = SampleWindowReader(history),
            detector = RestingDeviationDetector(),
            activity = WorkoutActivitySource(history),
            corridors = corridors(context),
            context = HistoryDeviationContextSource(
                SampleWindowReader(history),
                WorkoutActivitySource(history),
                RoomMedicationIntakeLog(CardioDatabase.getInstance(context).medicationIntakes(), SensorHistoryModule.clock),
            ),
            sink = log(context),
            asker = asker(context),
            cursor = SharedPreferencesDeviationScanCursor(context),
            clock = SensorHistoryModule.clock,
        )
    }

    /** The corridors move over days; an hour old is current enough. */
    private val CORRIDOR_MAX_AGE: Duration = Duration.ofHours(1)
}
