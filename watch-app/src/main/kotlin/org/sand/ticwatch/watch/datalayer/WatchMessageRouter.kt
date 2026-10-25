package org.sand.ticwatch.watch.datalayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.sand.ticwatch.watch.install.ApkInstaller
import org.sand.ticwatch.watch.install.PackageInstallerApkInstaller
import org.sand.ticwatch.watch.sensors.SensorCollector
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Business logic for requests arriving from the phone, independent of which transport
 * delivered them (Wear Data Layer or direct Bluetooth) - both
 * `WatchDataLayerListenerService` and `BluetoothLinkServerService` forward into this
 * one place instead of duplicating the handling.
 */
class WatchMessageRouter(
    private val context: Context,
    private val apkInstaller: ApkInstaller,
    private val sensorCollector: SensorCollector,
    private val replyTransport: DeviceLinkTransport,
    private val scope: CoroutineScope,
) {
    private val pendingInstallPackage = AtomicReference<String?>()

    fun handleMessage(path: String, payload: ByteArray) {
        when (path) {
            DataLayerPaths.MESSAGE_INSTALL_APP_REQUEST ->
                pendingInstallPackage.set(String(payload, Charsets.UTF_8))

            DataLayerPaths.MESSAGE_UNINSTALL_APP_REQUEST -> {
                val packageName = String(payload, Charsets.UTF_8)
                scope.launch {
                    val result = apkInstaller.uninstall(packageName)
                    sendResult(DataLayerPaths.MESSAGE_UNINSTALL_APP_RESULT, result.isSuccess)
                }
            }

            DataLayerPaths.MESSAGE_DEBUG_SENSOR_SNAPSHOT_REQUEST -> {
                Log.i(TAG, "Debug sensor snapshot requested")
                scope.launch {
                    runCatching { replyWithSensorSnapshot() }
                        .onFailure { Log.w(TAG, "replyWithSensorSnapshot failed", it) }
                }
            }
        }
    }

    /** Consumes an APK byte stream announced by a preceding install request. */
    suspend fun handleIncomingApk(input: InputStream) {
        val packageName = pendingInstallPackage.getAndSet(null) ?: "unknown"
        val stagedFile = PackageInstallerApkInstaller.stagedApkFile(context, "$packageName.apk")

        val result = runCatching {
            stagedFile.parentFile?.mkdirs()
            stagedFile.outputStream().use { output -> input.copyTo(output) }
            apkInstaller.install(stagedFile).getOrThrow()
        }
        sendResult(DataLayerPaths.MESSAGE_INSTALL_APP_RESULT, result.isSuccess)
        stagedFile.delete()
    }

    private suspend fun replyWithSensorSnapshot() {
        val payload = runCatching {
            val sample = withTimeoutOrNull(SENSOR_SNAPSHOT_TIMEOUT_MS) {
                sensorCollector.heartRateSamples().first()
            } ?: error("No heart-rate reading within ${SENSOR_SNAPSHOT_TIMEOUT_MS}ms")

            JSONObject()
                .put("type", sample.type.name)
                .put("value", sample.value)
                .put("recordedAtEpochMs", sample.recordedAt.toEpochMilli())
                .toString()
        }.getOrElse { error -> JSONObject().put("error", error.message).toString() }

        Log.i(TAG, "Replying with sensor snapshot: $payload")
        replyTransport.sendMessage(
            DataLayerPaths.MESSAGE_DEBUG_SENSOR_SNAPSHOT_RESPONSE,
            payload.toByteArray(Charsets.UTF_8),
        ).onSuccess { Log.i(TAG, "Sensor snapshot reply sent") }
            .onFailure { Log.w(TAG, "Sensor snapshot reply failed", it) }
    }

    private suspend fun sendResult(path: String, success: Boolean) {
        replyTransport.sendMessage(path, (if (success) "OK" else "FAILED").toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val SENSOR_SNAPSHOT_TIMEOUT_MS = 5_000L
        private const val TAG = "WatchMessageRouter"
    }
}
