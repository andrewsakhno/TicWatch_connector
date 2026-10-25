package org.sand.ticwatch.watch.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * [ApkInstaller] backed by [PackageInstaller]. This app is a regular (non-system,
 * non-device-owner) package, so both install and uninstall still surface the
 * standard Android confirmation dialog on the watch's screen — there is no way to
 * make this fully silent without the watch-app being a system/privileged app or the
 * device being under MDM with an allowlisted installer. Callers should treat this as
 * "prompted deploy", not true silent `adb install`.
 */
class PackageInstallerApkInstaller(private val context: Context) : ApkInstaller {

    private val packageInstaller get() = context.packageManager.packageInstaller

    override suspend fun install(apk: File): Result<Unit> = suspendCancellableCoroutine { continuation ->
        runCatching {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("watch-app-deploy", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                // commit() only means the request was accepted, not that the install
                // (which still needs the user to confirm on-screen) actually succeeded -
                // wait for InstallResultReceiver's real, asynchronous outcome instead of
                // resolving here.
                InstallResultListener.awaitResult(sessionId) { success, message ->
                    if (continuation.isActive) {
                        continuation.resume(
                            if (success) Result.success(Unit) else Result.failure(IllegalStateException(message)),
                        )
                    }
                }

                val intent = Intent(context, InstallResultReceiver::class.java)
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pendingIntent.intentSender)
            }
        }.onFailure { if (continuation.isActive) continuation.resume(Result.failure(it)) }
    }

    override suspend fun uninstall(packageName: String): Result<Unit> = runCatching {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:$packageName")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        /** Directory FileProvider exposes for APK bytes staged from the Data Layer channel. */
        fun stagedApkFile(context: Context, fileName: String): File =
            File(context.cacheDir, "staged-apks/$fileName")

        fun contentUriFor(context: Context, file: File): Uri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
