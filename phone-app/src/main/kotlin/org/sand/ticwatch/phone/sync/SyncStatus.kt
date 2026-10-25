package org.sand.ticwatch.phone.sync

/** Which side of the phone<->watch link a transfer is moving bytes towards. */
enum class SyncDirection {
    TO_WATCH,
    FROM_WATCH,
}

/**
 * Live state of the phone<->watch data exchange, observed by the Dashboard to render
 * a progress indicator. [InProgress.totalBytes] is null when the size isn't known
 * upfront (e.g. a Data Layer message read in one shot), in which case the UI falls
 * back to an indeterminate indicator instead of a percentage.
 */
sealed interface SyncStatus {
    data object Idle : SyncStatus

    data class InProgress(
        val direction: SyncDirection,
        val description: String,
        val bytesTransferred: Long,
        val totalBytes: Long?,
    ) : SyncStatus
}
