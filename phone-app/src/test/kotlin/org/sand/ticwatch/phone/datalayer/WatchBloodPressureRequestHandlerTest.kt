package org.sand.ticwatch.phone.datalayer

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.health.BloodPressureCalibration
import org.sand.ticwatch.phone.health.BloodPressureTestFixture
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.text.ResourceText
import org.sand.ticwatch.phone.text.TextResolver
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.PayloadSource

class WatchBloodPressureRequestHandlerTest {

    private val cuffAt = Instant.parse("2026-10-29T08:00:00Z")
    private val fixture = BloodPressureTestFixture(cuffAt)

    private class RecordingTransport : DeviceLinkTransport {
        val sent = mutableListOf<Pair<String, String>>()

        override suspend fun send(path: String, length: Long, source: PayloadSource): Result<Unit> {
            sent += path to source.open().use { String(it.readBytes(), Charsets.UTF_8) }
            return Result.success(Unit)
        }
    }

    private fun measureRequest(heartRate: Double, at: Instant): ByteArray = JSONObject()
        .put(BloodPressureWatchPayloads.HEART_RATE, heartRate)
        .put(BloodPressureWatchPayloads.HEART_RATE_AT, at.toEpochMilli())
        .toString().toByteArray(Charsets.UTF_8)

    @Test
    fun `watch measurement right after the cuff is estimated from the cuff calibration`() = runTest {
        fixture.calibrations.current = BloodPressureCalibration(120.0, 80.0, 60.0, null, null, cuffAt.minus(Duration.ofDays(2)))
        fixture.saveCuffReading(150.0, 95.0, cuffAt)
        fixture.clock.advance(Duration.ofMinutes(2))
        val transport = RecordingTransport()

        WatchBloodPressureRequestHandler(fixture.monitor, transport, fixture.clock, ID_TEXTS)
            .onMeasureRequested(measureRequest(70.0, fixture.clock.now))

        val (path, reply) = transport.sent.single()
        assertEquals(DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_RESULT, path)
        assertTrue(JSONObject(reply).getBoolean(BloodPressureWatchPayloads.SUCCESS))
        assertEquals(cuffAt, fixture.calibrations.current?.calibratedAt)
        val estimate = fixture.monitor.recent(1).single()
        assertEquals(SampleSource.WATCH, estimate.source)
        assertEquals(150.0, estimate.systolic, DELTA)
    }

    @Test
    fun `watch measurement without any calibration asks for the cuff`() = runTest {
        val transport = RecordingTransport()

        WatchBloodPressureRequestHandler(fixture.monitor, transport, fixture.clock, ID_TEXTS)
            .onMeasureRequested(measureRequest(70.0, fixture.clock.now))

        val reply = JSONObject(transport.sent.single().second)
        assertEquals(false, reply.getBoolean(BloodPressureWatchPayloads.SUCCESS))
        assertEquals(R.string.watch_calibrate_first.toString(), reply.getString(BloodPressureWatchPayloads.STATUS))
    }

    private companion object {
        const val DELTA = 1e-9

        /** Resolves a text to its resource id, so assertions don't depend on the language. */
        val ID_TEXTS = TextResolver { (it as ResourceText).id.toString() }
    }
}
