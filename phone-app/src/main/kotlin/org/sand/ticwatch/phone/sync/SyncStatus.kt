package org.sand.ticwatch.phone.sync

/** Which side of the phone<->watch link a transfer is moving bytes towards. */
enum class SyncDirection {
    TO_WATCH,
    FROM_WATCH,
}

/**
 * One in-flight phone<->watch transfer, observed by the Dashboard and the install
 * notification to render progress. [totalBytes] is null when the size isn't known
 * upfront, in which case the UI falls back to an indeterminate indicator.
 */
data class SyncTransfer(
    val id: Long,
    val path: String,
    val direction: SyncDirection,
    val description: String,
    val bytesTransferred: Long,
    val totalBytes: Long?,
) {
    /** Completed fraction in 0..1, or null when [totalBytes] is unknown. */
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesTransferred.toFloat() / it).coerceIn(0f, 1f) }
}
