package org.sand.ticwatch.phone.transport

import org.sand.ticwatch.phone.sync.SyncDirection
import org.sand.ticwatch.phone.sync.SyncStatusReporter
import org.sand.ticwatch.phone.sync.describeSyncPath

/**
 * Decorates any [DeviceLinkTransport] to report begin/progress/end into [reporter]
 * around every send (OCP: neither [BluetoothClassicTransport] nor [DataLayerTransport]
 * needs to know progress reporting exists). It instruments the [PayloadSource] with a
 * [CountingInputStream], since both implementations already read through the source to
 * copy the payload to the peer.
 */
class ProgressReportingDeviceLinkTransport(
    private val delegate: DeviceLinkTransport,
    private val reporter: SyncStatusReporter,
) : DeviceLinkTransport {

    override suspend fun send(path: String, length: Long, source: PayloadSource): Result<Unit> {
        reporter.begin(SyncDirection.TO_WATCH, describeSyncPath(path, SyncDirection.TO_WATCH), length.takeIf { it > 0 })
        val countingSource = PayloadSource { CountingInputStream(source.open()) { reporter.updateProgress(it) } }
        return try {
            delegate.send(path, length, countingSource)
        } finally {
            reporter.end()
        }
    }
}
