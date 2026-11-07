package org.sand.ticwatch.phone.ecg

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import org.sand.ticwatch.phone.cloud.CloudOutbox
import org.sand.ticwatch.phone.cloud.CloudRetentionHold
import org.sand.ticwatch.phone.history.SettingStore
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A stored rhythm strip; [beatsJson] is [EcgPayloads.encodeBeats], times are epoch millis. */
@Entity(tableName = "ecg_recording", indices = [Index("started_at")])
data class EcgRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAtMs: Long,
    @ColumnInfo(name = "finished_at") val finishedAtMs: Long,
    @ColumnInfo(name = "beats") val beatsJson: String,
    /** [EcgPayloads.encodeWave]; empty for strips recorded before the pulse wave. */
    @ColumnInfo(name = "wave", defaultValue = "") val waveJson: String = "",
    /** True once the cloud server acknowledged the strip (v5). */
    @ColumnInfo(name = "cloud_synced", defaultValue = "0") val cloudSynced: Boolean = false,
    /** [EcgRecordingKind.code] (v8). */
    @ColumnInfo(name = "kind", defaultValue = "0") val kind: Int = EcgRecordingKind.REQUESTED.code,
)

@Dao
interface EcgRecordingDao {
    @Insert
    suspend fun insert(recording: EcgRecordingEntity): Long

    @Query("SELECT * FROM ecg_recording ORDER BY started_at DESC")
    suspend fun newestFirst(): List<EcgRecordingEntity>

    @Query("SELECT * FROM ecg_recording WHERE started_at >= :fromMs AND started_at < :toMs ORDER BY started_at DESC")
    suspend fun startedBetween(fromMs: Long, toMs: Long): List<EcgRecordingEntity>

    /** Deletes strips started before [beforeMs] the cloud holds, and those it doesn't unless [keepUnsynced]. */
    @Query("DELETE FROM ecg_recording WHERE started_at < :beforeMs AND (cloud_synced = 1 OR :keepUnsynced = 0)")
    suspend fun deleteBefore(beforeMs: Long, keepUnsynced: Boolean): Int

    @Query("SELECT * FROM ecg_recording WHERE cloud_synced = 0 ORDER BY id LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<EcgRecordingEntity>

    @Query("UPDATE ecg_recording SET cloud_synced = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM ecg_recording WHERE cloud_synced = 0")
    suspend fun pendingUploadCount(): Long

    @Query("DELETE FROM ecg_recording WHERE id = :id")
    suspend fun delete(id: Long): Int
}

/** Rhythm strips kept on the phone. */
interface EcgRecordingLog {
    /** Returns the stored recording's id. */
    suspend fun save(recording: EcgRecording): Long

    suspend fun newestFirst(): List<EcgRecording>

    /** Recordings started in [from] (inclusive) .. [to] (exclusive), newest first. */
    suspend fun startedBetween(from: Instant, to: Instant): List<EcgRecording>

    suspend fun delete(id: Long)

    /** Deletes recordings started before [cutoff] the cloud holds, and those it doesn't unless [keepUnsynced]; returns how many. */
    suspend fun purgeBefore(cutoff: Instant, keepUnsynced: Boolean): Int
}

/** [EcgRecordingLog] and its [CloudOutbox] over the `ecg_recording` table of `cardio.db`. */
class RoomEcgRecordingLog(private val dao: EcgRecordingDao) : EcgRecordingLog, CloudOutbox<EcgRecordingEntity> {

    override suspend fun save(recording: EcgRecording): Long = dao.insert(
        EcgRecordingEntity(
            startedAtMs = recording.startedAt.toEpochMilli(),
            finishedAtMs = recording.finishedAt.toEpochMilli(),
            beatsJson = EcgPayloads.encodeBeats(recording.beats),
            waveJson = if (recording.wave.isEmpty()) "" else EcgPayloads.encodeWave(recording.wave),
            kind = recording.kind.code,
        ),
    )

    override suspend fun newestFirst(): List<EcgRecording> = dao.newestFirst().map(::toRecording)

    override suspend fun startedBetween(from: Instant, to: Instant): List<EcgRecording> =
        dao.startedBetween(from.toEpochMilli(), to.toEpochMilli()).map(::toRecording)

    private fun toRecording(row: EcgRecordingEntity): EcgRecording =
        EcgRecording(
            row.id,
            Instant.ofEpochMilli(row.startedAtMs),
            Instant.ofEpochMilli(row.finishedAtMs),
            EcgPayloads.decodeBeats(row.beatsJson),
            EcgPayloads.decodeWave(row.waveJson),
            EcgRecordingKind.of(row.kind),
        )

    override suspend fun delete(id: Long) {
        dao.delete(id)
    }

    override suspend fun purgeBefore(cutoff: Instant, keepUnsynced: Boolean): Int =
        dao.deleteBefore(cutoff.toEpochMilli(), keepUnsynced)

    override suspend fun pendingUpload(limit: Int): List<EcgRecordingEntity> = dao.pendingUpload(limit)

    override suspend fun markUploaded(items: List<EcgRecordingEntity>) {
        if (items.isNotEmpty()) dao.markUploaded(items.map { it.id })
    }

    override suspend fun pendingUploadCount(): Long = dao.pendingUploadCount()
}

/** Deletes cardiograms older than the selected [EcgRetention]. */
class EcgRecordingPurger(
    private val log: EcgRecordingLog,
    private val retention: SettingStore<EcgRetention>,
    private val cloudHold: CloudRetentionHold,
    private val clock: Clock,
) {
    suspend fun purge(): Int {
        val cutoff = clock.instant().minus(Duration.ofDays(retention.get().days))
        return log.purgeBefore(cutoff, cloudHold.keepsUnsynced())
    }
}
