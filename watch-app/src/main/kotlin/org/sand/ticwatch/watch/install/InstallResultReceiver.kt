package org.sand.ticwatch.watch.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** Receives the PackageInstaller session outcome and hands it to [InstallResultListener]. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmationIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            Log.i(TAG, "Install session $sessionId awaiting user confirmation")
            if (confirmationIntent != null) InstallConfirmationPrompt(context).show(sessionId, confirmationIntent)
            return
        }

        val success = status == PackageInstaller.STATUS_SUCCESS
        Log.i(TAG, "Install session $sessionId finished: success=$success status=$status message=$message")
        InstallResultListener.notify(sessionId, success, message)
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
    }
}

/**
 * Hands the real, asynchronous PackageInstaller outcome back to [PackageInstallerApkInstaller],
 * keyed by session ID so the two don't have to share anything beyond that. Needed because
 * `session.commit()` returning without throwing only means the request was accepted, not
 * that the install (which still requires user confirmation) actually succeeded.
 */
object InstallResultListener {
    private val callbacks = ConcurrentHashMap<Int, (success: Boolean, message: String?) -> Unit>()

    fun awaitResult(sessionId: Int, callback: (success: Boolean, message: String?) -> Unit) {
        callbacks[sessionId] = callback
    }

    fun notify(sessionId: Int, success: Boolean, message: String?) {
        callbacks.remove(sessionId)?.invoke(success, message)
    }
}
