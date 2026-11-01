package org.sand.ticwatch.phone.snapshot

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.text.uiText

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessSnapshotResultsTest {

    @After
    fun tearDown() {
        ProcessSnapshotResults.resetForTest()
    }

    private class FakeRequester(
        private val reply: suspend () -> Result<WatchSnapshotResult?>,
    ) : WatchSnapshotRequester {
        var requested = 0

        override suspend fun request(): Result<String> {
            requested++
            return Result.success("test-id")
        }

        override suspend fun requestAndAwait(timeout: Duration, onAccepted: () -> Unit): Result<WatchSnapshotResult?> {
            requested++
            return reply()
        }
    }

    @Test
    fun `snapshot request marks isRunning true until record is received`() = runTest {
        ProcessSnapshotResults.resetForTest(this)
        val deferredResult = CompletableDeferred<Result<WatchSnapshotResult?>>()
        val requester = FakeRequester { deferredResult.await() }

        assertFalse(ProcessSnapshotResults.isRunning.value)
        ProcessSnapshotResults.requestSnapshot(requester, 5.minutes)
        assertTrue(ProcessSnapshotResults.isRunning.value)

        val result = WatchSnapshotResult(
            status = SnapshotStatus.COMPLETED,
            message = "Snapshot OK",
            startedAt = Instant.EPOCH,
            finishedAt = Instant.EPOCH.plusSeconds(10),
            samples = emptyList(),
            requestId = "req-1",
        )

        // Simulating the watch result arriving via onSnapshotResult -> record(result)
        ProcessSnapshotResults.record(result)

        // Should immediately become active (isRunning = false)
        assertFalse(ProcessSnapshotResults.isRunning.value)
        assertEquals(result, ProcessSnapshotResults.latest.value)

        // Then await finishes
        deferredResult.complete(Result.success(result))
        advanceUntilIdle()

        assertFalse(ProcessSnapshotResults.isRunning.value)
        assertNull(ProcessSnapshotResults.error.value)
    }

    @Test
    fun `failure in request sets error and clears isRunning immediately`() = runTest {
        ProcessSnapshotResults.resetForTest(this)
        val requester = FakeRequester { Result.failure(IllegalStateException("Bluetooth disconnected")) }

        ProcessSnapshotResults.requestSnapshot(requester, 5.minutes)
        advanceUntilIdle()

        assertFalse(ProcessSnapshotResults.isRunning.value)
        assertEquals(uiText(R.string.watch_unreachable, "Bluetooth disconnected"), ProcessSnapshotResults.error.value)
    }

    @Test
    fun `timeout without answer sets error and clears isRunning`() = runTest {
        ProcessSnapshotResults.resetForTest(this)
        val requester = FakeRequester { Result.success(null) }

        ProcessSnapshotResults.requestSnapshot(requester, 5.minutes)
        advanceUntilIdle()

        assertFalse(ProcessSnapshotResults.isRunning.value)
        assertEquals(uiText(R.string.snapshot_stuck, uiText(SnapshotStage.CONNECTING.label)), ProcessSnapshotResults.error.value)
    }
}
