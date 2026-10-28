package org.sand.ticwatch.watch

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.sand.ticwatch.watch.logging.LogCaptureController
import org.sand.ticwatch.watch.logging.LogcatFileRecorder
import org.sand.ticwatch.watch.logging.RotatingLogStore
import org.sand.ticwatch.watch.settings.SharedPreferencesWatchSettingsStore

/**
 * Records crashes from the start; the full on-watch log capture (for the debug bridge) runs only while the
 * user's "Log capture" setting is on, since a continuously running logcat reader costs battery.
 */
class WatchApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val recorder = LogcatFileRecorder(RotatingLogStore.inFilesDir(filesDir)).apply { recordCrashes() }
        // First settings access reads the preferences file: off the main thread.
        scope.launch {
            val settings = SharedPreferencesWatchSettingsStore.get(this@WatchApplication).settings
            LogCaptureController(recorder, settings.map { it.logCapture }).bind(scope)
        }
    }
}
