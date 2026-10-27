package org.sand.ticwatch.watch

import android.app.Application
import org.sand.ticwatch.watch.logging.LogcatFileRecorder
import org.sand.ticwatch.watch.logging.RotatingLogStore

/** Starts the on-watch log capture first, so every component's logs survive for the debug bridge. */
class WatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LogcatFileRecorder(RotatingLogStore.inFilesDir(filesDir)).start()
    }
}
