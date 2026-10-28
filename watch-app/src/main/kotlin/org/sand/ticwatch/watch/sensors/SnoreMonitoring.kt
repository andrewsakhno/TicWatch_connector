package org.sand.ticwatch.watch.sensors

import org.sand.ticwatch.watch.datalayer.LatestMessageSender
import org.sand.ticwatch.watch.settings.SnoreMicrophone
import org.sand.ticwatch.watch.settings.WatchSettings
import java.time.Duration
import java.time.Instant

/** [SnoreMeter] that only listens while [settings] ask for snore analysis on the watch's own microphone. */
class SettingsGatedSnoreMeter(
    private val settings: () -> WatchSettings,
    private val delegate: SnoreMeter,
) : SnoreMeter {

    override suspend fun snoringFraction(slot: Duration): Double? {
        val current = settings()
        if (!current.snoreAnalysis || current.snoreMicrophone != SnoreMicrophone.WATCH) return null
        return delegate.snoringFraction(slot)
    }
}

/**
 * Hands sleep-state updates on to [next] and, while [settings] ask for snore analysis on the phone's microphone,
 * tells the phone through [sender] when a sleep session opens and closes. Re-evaluated on every sleep event, so a
 * setting changed mid-night applies at the next one. A night ended by sensor silence sends no "off": the phone
 * caps its own listening.
 */
class PhoneSnoreMonitorRelay(
    private val next: SleepStateSink,
    private val settings: () -> WatchSettings,
    private val sender: LatestMessageSender,
) : SleepStateSink {

    override fun update(inSession: Boolean, at: Instant) {
        next.update(inSession, at)
        val current = settings()
        val wanted = inSession && current.snoreAnalysis && current.snoreMicrophone == SnoreMicrophone.PHONE
        // Nothing to switch off before anything was switched on.
        if (wanted == (sender.current ?: false)) return
        sender.update(wanted)
    }
}
