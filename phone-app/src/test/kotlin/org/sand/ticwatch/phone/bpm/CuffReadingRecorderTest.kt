package org.sand.ticwatch.phone.bpm

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.sand.ticwatch.phone.datalayer.WatchSyncOutcome
import org.sand.ticwatch.phone.datalayer.WatchSyncRequester
import org.sand.ticwatch.phone.health.BloodPressureTestFixture
import org.sand.ticwatch.phone.health.HeartRateReading
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.time.Duration as KotlinDuration

class CuffReadingRecorderTest {

    private val cuffAt = Instant.parse("2026-10-29T08:00:00Z")
    private val fixture = BloodPressureTestFixture(cuffAt)
    private val reading = CuffReading(140, 90, 72, LocalDateTime.ofInstant(cuffAt, ZoneOffset.UTC))

    /** Counts requests and runs [onSync] as the watch's flush. */
    private class FakeWatchSync(private val outcome: WatchSyncOutcome, private val onSync: () -> Unit = {}) : WatchSyncRequester {
        var requests = 0

        override suspend fun syncNow(timeout: KotlinDuration): WatchSyncOutcome {
            requests++
            onSync()
            return outcome
        }
    }

    private fun TestScope.recorder(watchSync: WatchSyncRequester) =
        CuffReadingRecorder(fixture.monitor, {}, watchSync, {}, this, fixture.clock, ZoneOffset.UTC)

    @Test
    fun `missing watch heart rate pulls the watch buffer and calibrates without waiting`() = runTest {
        val watchSync = FakeWatchSync(WatchSyncOutcome.DELIVERED) { fixture.watchHeartRate(66.0, cuffAt.minus(Duration.ofMinutes(1))) }

        recorder(watchSync).onReading(reading, live = true)
        advanceUntilIdle()

        assertEquals(1, watchSync.requests)
        assertEquals(cuffAt, fixture.calibrations.current?.calibratedAt)
        assertEquals(66.0, fixture.calibrations.current!!.heartRate, DELTA)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `stored watch heart rate calibrates without a sync`() = runTest {
        fixture.watchHeartRate(64.0, cuffAt.minus(Duration.ofMinutes(2)))
        val watchSync = FakeWatchSync(WatchSyncOutcome.DELIVERED)

        recorder(watchSync).onReading(reading, live = true)
        advanceUntilIdle()

        assertEquals(0, watchSync.requests)
        assertEquals(cuffAt, fixture.calibrations.current?.calibratedAt)
    }

    @Test
    fun `unreachable watch leaves the cuff reading for the next watch measurement`() = runTest {
        val watchSync = FakeWatchSync(WatchSyncOutcome.UNREACHABLE)

        recorder(watchSync).onReading(reading, live = true)
        advanceUntilIdle()
        assertNull(fixture.calibrations.current)

        fixture.clock.advance(Duration.ofMinutes(4))
        fixture.monitor.measureAndSave(HeartRateReading(70.0, fixture.clock.now))

        assertEquals(140.0, fixture.calibrations.current!!.systolic, DELTA)
        assertEquals(cuffAt, fixture.calibrations.current!!.calibratedAt)
    }

    private companion object {
        const val DELTA = 1e-9
    }
}
