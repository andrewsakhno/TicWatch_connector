package org.sand.ticwatch.phone.transport

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.phone.sync.SyncStatusRepository

/**
 * Builds the standard transport used everywhere phone-app talks to the watch.
 *
 * Bluetooth is primary, Data Layer is the fallback - not the other way round. Reason:
 * `MessageClient`/`ChannelClient` operations report success as soon as Play Services
 * accepts them for delivery, not when the peer actually receives them, so when the
 * Data Layer's underlying node connection isn't genuinely live (e.g. the watch was
 * paired via a vendor companion app rather than official Wear OS by Google pairing)
 * sends silently vanish instead of failing - defeating the fallback.
 * [BluetoothClassicTransport] has its own explicit ack, so a "success" from it means
 * the peer really got the frame.
 *
 * Wrapped in [ProgressReportingDeviceLinkTransport] so every caller - `AppContainer`,
 * `DebugBridgeReceiver`, `WatchAppInstallService` - gets Dashboard progress reporting
 * for free instead of remembering to wrap it themselves.
 */
object TransportFactory {
    /** Shared by every transport this factory builds: callers each create their own instance. */
    private val arbiter = LinkArbiter()
    private val heldLink = HeldRfcommLink()

    /** Process-wide hold on the phone->watch socket, for live sync. */
    val linkHold: LinkHold get() = heldLink

    val bulkTransferActive: StateFlow<Boolean> get() = arbiter.bulkActive

    fun create(context: Context): DeviceLinkTransport {
        val link = FallbackDeviceLinkTransport(
            primary = BluetoothClassicTransport(context, heldLink),
            fallback = DataLayerTransport(
                nodeClient = Wearable.getNodeClient(context),
                messageClient = Wearable.getMessageClient(context),
                channelClient = Wearable.getChannelClient(context),
            ),
        )
        return ArbitratedDeviceLinkTransport(
            delegate = ProgressReportingDeviceLinkTransport(
                delegate = LargePayloadRoutingTransport(
                    large = FallbackDeviceLinkTransport(
                        primary = WifiLanTransport(
                            networks = ConnectivityWifiNetworkSource(context),
                            endpoints = RfcommWifiEndpointRequester(context),
                        ),
                        fallback = link,
                    ),
                    regular = link,
                ),
                reporter = SyncStatusRepository,
            ),
            arbiter = arbiter,
        )
    }
}
