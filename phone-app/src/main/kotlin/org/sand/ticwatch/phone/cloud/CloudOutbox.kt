package org.sand.ticwatch.phone.cloud

/**
 * Local rows the cloud server has not acknowledged yet, oldest first. Every store holding history data
 * implements one; [CloudSyncEngine] drains them through an [OutboxUploadChannel]. A retention purge never
 * deletes a row still pending here while [CloudRetentionHold] holds.
 */
interface CloudOutbox<T> {
    suspend fun pendingUpload(limit: Int): List<T>

    /** Called only after the server answered the upload of exactly these [items]. */
    suspend fun markUploaded(items: List<T>)

    suspend fun pendingUploadCount(): Long
}

/** Decides whether the local retention purge spares rows the server has not acknowledged. */
fun interface CloudRetentionHold {
    suspend fun keepsUnsynced(): Boolean
}
