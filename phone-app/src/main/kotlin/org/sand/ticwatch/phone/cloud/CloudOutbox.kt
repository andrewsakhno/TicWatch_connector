package org.sand.ticwatch.phone.cloud

import java.time.Instant

/**
 * Local rows the cloud server has not acknowledged yet, oldest first. Every store holding watch data
 * implements one; [CloudSyncEngine] drains them through an [OutboxUploadChannel].
 */
interface CloudOutbox<T> {
    suspend fun pendingUpload(limit: Int): List<T>

    /** Called only after the server answered the upload of exactly these [items]. */
    suspend fun markUploaded(items: List<T>)

    suspend fun pendingUploadCount(): Long
}

/** How long rows the server has not acknowledged survive the local retention purge. */
fun interface CloudRetentionHold {
    /** Purge cutoff for not-yet-uploaded rows, given the retention's [cutoff]; never later than it. */
    suspend fun unsyncedCutoff(cutoff: Instant): Instant
}
