package com.wearos.ancsbridge.health

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A health sample buffered on the watch until the iPhone companion confirms
 * it was written to HealthKit. See [HealthProtocol] for field meanings.
 */
@Entity(
    tableName = "health_records",
    indices = [Index(value = ["type", "subtype", "startMs"], unique = true)]
)
data class HealthRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: Int,
    val subtype: Int = 0,
    val startMs: Long,
    val durationMs: Long,
    val value: Double,
    val value2: Double = 0.0,
    val synced: Boolean = false
)

@Dao
interface HealthRecordDao {
    /** Duplicate (type, subtype, startMs) rows are ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<HealthRecord>)

    @Query("SELECT * FROM health_records WHERE synced = 0 ORDER BY startMs LIMIT :limit")
    suspend fun unsynced(limit: Int): List<HealthRecord>

    @Query("UPDATE health_records SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("DELETE FROM health_records WHERE synced = 1 AND startMs < :beforeMs")
    suspend fun deleteSyncedBefore(beforeMs: Long)

    @Query("SELECT COUNT(*) FROM health_records WHERE synced = 0")
    fun unsyncedCount(): Flow<Int>
}

@Database(entities = [HealthRecord::class], version = 1, exportSchema = false)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun recordDao(): HealthRecordDao

    companion object {
        @Volatile private var instance: HealthDatabase? = null

        fun get(context: Context): HealthDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HealthDatabase::class.java,
                    "health.db"
                ).build().also { instance = it }
            }
    }
}
