package org.sand.ticwatch.watch.transport

import android.content.Context
import com.google.android.gms.wearable.Wearable

/**
 * Builds the standard transport used everywhere watch-app talks to the phone.
 *
 * Bluetooth is primary, Data Layer is the fallback - not the other way round. Reason:
 * `MessageClient.sendMessage()` reports success as soon as Play Services accepts the
 * message for delivery, not when the peer actually receives it, so when the Data
 * Layer's underlying node connection isn't genuinely live (e.g. the watch was paired
 * via a vendor companion app rather than official Wear OS by Google pairing) sends
 * silently vanish instead of failing - defeating the fallback. [BluetoothClassicTransport]
 * has its own explicit ack, so a "success" from it means the peer really got the frame.
 */
object TransportFactory {
    fun create(context: Context): DeviceLinkTransport = FallbackDeviceLinkTransport(
        primary = BluetoothClassicTransport(context),
        fallback = DataLayerTransport(
            nodeClient = Wearable.getNodeClient(context),
            messageClient = Wearable.getMessageClient(context),
            channelClient = Wearable.getChannelClient(context),
        ),
    )
}
