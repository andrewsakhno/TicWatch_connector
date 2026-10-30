package org.sand.ticwatch.phone.cloud

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/** Whether the phone has a working link to the cloud server. */
enum class CloudLink {
    /** Cloud storage switched off in Settings. */
    DISABLED,

    /** Never found, or the last attempt failed. */
    NO_SERVER,

    /** The last sync reached the server. */
    CONNECTED,
}

/** What the title bar's cloud icon shows. */
enum class CloudSyncIndicatorKind { OFF, NOT_SYNCED, SYNCING, SYNCED }

/** Last known cloud state; [pending] = rows (all outboxes) not on the server yet. */
data class CloudSyncStatus(
    val link: CloudLink = CloudLink.NO_SERVER,
    val syncing: Boolean = false,
    val pending: Long = 0,
    val host: String? = null,
    val lastSyncAt: Instant? = null,
) {
    val indicator: CloudSyncIndicatorKind
        get() = when {
            syncing -> CloudSyncIndicatorKind.SYNCING
            link != CloudLink.CONNECTED -> CloudSyncIndicatorKind.OFF
            pending > 0 -> CloudSyncIndicatorKind.NOT_SYNCED
            else -> CloudSyncIndicatorKind.SYNCED
        }
}

interface CloudSyncStatusStore {
    val status: StateFlow<CloudSyncStatus>
    fun update(transform: (CloudSyncStatus) -> CloudSyncStatus)
}

/**
 * [CloudSyncStatusStore] in SharedPreferences, one per process, so the title bar sees what the worker
 * stored. [CloudSyncStatus.syncing] isn't persisted: a killed process is not syncing.
 * First access reads the preferences file - call off the main thread.
 */
class SharedPreferencesCloudSyncStatusStore private constructor(private val prefs: SharedPreferences) : CloudSyncStatusStore {

    private val state = MutableStateFlow(read())
    override val status: StateFlow<CloudSyncStatus> = state

    override fun update(transform: (CloudSyncStatus) -> CloudSyncStatus) {
        state.update(transform)
        val saved = state.value
        prefs.edit()
            .putString(KEY_LINK, saved.link.name)
            .putLong(KEY_PENDING, saved.pending)
            .putString(KEY_HOST, saved.host)
            .putLong(KEY_LAST_SYNC_AT, saved.lastSyncAt?.toEpochMilli() ?: NEVER)
            .apply()
    }

    private fun read() = CloudSyncStatus(
        link = CloudLink.entries.firstOrNull { it.name == prefs.getString(KEY_LINK, null) } ?: CloudLink.NO_SERVER,
        pending = prefs.getLong(KEY_PENDING, 0),
        host = prefs.getString(KEY_HOST, null),
        lastSyncAt = prefs.getLong(KEY_LAST_SYNC_AT, NEVER).takeIf { it != NEVER }?.let(Instant::ofEpochMilli),
    )

    companion object {
        private const val PREFS_NAME = "cloud_sync_status"
        private const val KEY_LINK = "link"
        private const val KEY_PENDING = "pending"
        private const val KEY_HOST = "host"
        private const val KEY_LAST_SYNC_AT = "last_sync_at_epoch_ms"
        private const val NEVER = -1L

        @Volatile private var instance: SharedPreferencesCloudSyncStatusStore? = null

        fun get(context: Context): SharedPreferencesCloudSyncStatusStore = instance ?: synchronized(this) {
            instance ?: SharedPreferencesCloudSyncStatusStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
