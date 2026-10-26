package org.sand.ticwatch.phone.transport

import org.sand.ticwatch.phone.sync.SyncDirection
import org.sand.ticwatch.phone.sync.SyncStatusReporter

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
        val progress = reporter.begin(path, SyncDirection.TO_WATCH, length.takeIf { it > 0 })
        val countingSource = object : PayloadSource {
            override fun open() = CountingInputStream(source.open(), progress::update)

            // Hashing reads the whole payload up front; counting it would flash 100% progress.
            override fun contentHash() = source.contentHash()
        }
        return try {
            delegate.send(path, length, countingSource)
        } finally {
            progress.end()
        }
    }
}
