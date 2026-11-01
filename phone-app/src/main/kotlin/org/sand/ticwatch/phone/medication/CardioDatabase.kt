package org.sand.ticwatch.phone.medication

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import org.sand.ticwatch.phone.ecg.EcgRecordingDao
import org.sand.ticwatch.phone.ecg.EcgRecordingEntity
import org.sand.ticwatch.phone.ecg.PulseBloodPressurePairDao
import org.sand.ticwatch.phone.ecg.PulseBloodPressurePairEntity

/** A logged intake row; [doseCode] is [MedicationDose.code], [takenAtMs] epoch millis. */
@Entity(tableName = "medication_intake", indices = [Index("taken_at")])
data class MedicationIntakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "dose") val doseCode: Int,
    @ColumnInfo(name = "taken_at") val takenAtMs: Long,
)

@Dao
interface MedicationIntakeDao {
    @Insert
    suspend fun insert(intake: MedicationIntakeEntity): Long

    @Query("SELECT * FROM medication_intake WHERE taken_at >= :fromMs AND taken_at < :toMs ORDER BY taken_at")
    suspend fun between(fromMs: Long, toMs: Long): List<MedicationIntakeEntity>
}

/**
 * Room database for cardio data the user enters (medication intakes) or records (ECG rhythm strips), kept apart from the hand-written
 * `sensor_history.db`. Schemas are exported to `phone-app/schemas/` - bump [VERSION] with a `Migration` per change.
 */
@Database(
    entities = [MedicationIntakeEntity::class, EcgRecordingEntity::class, PulseBloodPressurePairEntity::class],
    version = CardioDatabase.VERSION,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class CardioDatabase : RoomDatabase() {
    abstract fun medicationIntakes(): MedicationIntakeDao
    abstract fun ecgRecordings(): EcgRecordingDao
    abstract fun pulseBloodPressurePairs(): PulseBloodPressurePairDao

    companion object {
        const val VERSION = 5
        private const val NAME = "cardio.db"

        @Volatile
        private var instance: CardioDatabase? = null

        /** One instance per process: the activity, link services and workers each build their own composition root. */
        fun getInstance(context: Context): CardioDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CardioDatabase::class.java, NAME).build().also { instance = it }
        }
    }
}
