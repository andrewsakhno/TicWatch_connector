package org.sand.ticwatch.phone.medication

import android.content.Context
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
 * Room database for cardio data the user enters (medication intakes), kept apart from the hand-written
 * `sensor_history.db`. Schemas are exported to `phone-app/schemas/` - bump [VERSION] with a `Migration` per change.
 */
@Database(entities = [MedicationIntakeEntity::class], version = CardioDatabase.VERSION)
abstract class CardioDatabase : RoomDatabase() {
    abstract fun medicationIntakes(): MedicationIntakeDao

    companion object {
        const val VERSION = 1
        private const val NAME = "cardio.db"

        fun open(context: Context): CardioDatabase =
            Room.databaseBuilder(context, CardioDatabase::class.java, NAME).build()
    }
}
