package org.sand.ticwatch.watch.bloodpressure

import android.util.Log
import org.sand.ticwatch.watch.power.AwakeRunner
import org.sand.ticwatch.watch.sensors.QualifierProbe
import org.sand.ticwatch.watch.sensors.WindowGate
import org.sand.ticwatch.watch.sensors.WindowQualifier
import org.sand.ticwatch.watch.sensors.WindowScheduler
import java.time.Duration

/**
 * Requests a blood pressure estimate every [interval] while the wearer rests: all [gates] open (worn, few steps,
 * no workout), then the [stillness] qualifiers (gyroscope) accept a [probeDuration] probe. The phone saves every
 * estimate to the history, the same as the page's "Measure" button. Paced by an alarm [scheduler], never `delay`.
 */
class AutoBloodPressureController(
    private val settings: AutoBloodPressureSettings,
    private val gates: List<WindowGate>,
    private val stillness: () -> List<WindowQualifier>,
    private val probe: QualifierProbe,
    private val requester: AwaitedBloodPressureRequester,
    private val scheduler: WindowScheduler,
    private val awakeRunner: AwakeRunner,
    private val interval: Duration,
    private val probeDuration: Duration,
) {

    suspend fun run() {
        while (true) {
            scheduler.awaitNext(interval)
            if (!settings.isEnabled() || gates.any { !it.isOpen() }) continue
            awakeRunner.runAwake(probeDuration.plus(REQUEST_TIMEOUT)) {
                if (probe.accepts(stillness(), probeDuration)) {
                    requester.requestAndAwait()
                } else {
                    Log.i(TAG, "Wrist not still, skipping the automatic measurement")
                }
            }
        }
    }

    private companion object {
        const val TAG = "AutoBloodPressure"

        /** Flush plus the request message; the flush holds its own wake lock beyond this. */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
