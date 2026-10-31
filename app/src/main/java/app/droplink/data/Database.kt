package app.droplink.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

enum class Direction { SENT, RECEIVED }
enum class ItemStatus { OK, FAILED, CANCELLED }

@Entity(tableName = "history", indices = [Index("timestamp"), Index("transferId")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transferId: String,
    val direction: Direction,
    val peerName: String,
    val peerDeviceId: String?,
    val fileName: String,
    val relativePath: String,
    /** Content Uri / path of the received file, or the source Uri of a sent file. */
    val location: String?,
    val sizeBytes: Long,
    val mime: String,
    val status: ItemStatus,
    val reason: String?,
    val mode: String,
    /** Average measured throughput of the whole transfer (bytes/s). */
    val bytesPerSecond: Long,
    val timestamp: Long,
)

@Entity(tableName = "trusted_devices")
data class TrustedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val fingerprint: String,
    val addedAt: Long,
)

@Entity(tableName = "recent_devices")
data class RecentDeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val lastMode: String,
    val lastSeen: Long,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC, id DESC LIMIT 1000")
    fun observe(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insertAll(items: List<HistoryEntity>)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("SELECT transferId, MAX(timestamp) AS timestamp, direction, peerName, mode, MAX(bytesPerSecond) AS bytesPerSecond, SUM(sizeBytes) AS totalBytes, COUNT(*) AS files FROM history GROUP BY transferId ORDER BY timestamp DESC LIMIT 30")
    fun throughputLog(): Flow<List<ThroughputRow>>
}

data class ThroughputRow(
    val transferId: String,
    val timestamp: Long,
    val direction: Direction,
    val peerName: String,
    val mode: String,
    val bytesPerSecond: Long,
    val totalBytes: Long,
    val files: Int,
)

@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_devices ORDER BY name")
    fun observe(): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT * FROM trusted_devices WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): TrustedDeviceEntity?

    @Upsert
    suspend fun upsert(device: TrustedDeviceEntity)

    @Query("DELETE FROM trusted_devices WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}

@Dao
interface RecentDeviceDao {
    @Query("SELECT * FROM recent_devices ORDER BY lastSeen DESC LIMIT 6")
    fun observe(): Flow<List<RecentDeviceEntity>>

    @Upsert
    suspend fun upsert(device: RecentDeviceEntity)
}

@Database(
    entities = [HistoryEntity::class, TrustedDeviceEntity::class, RecentDeviceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun trusted(): TrustedDeviceDao
    abstract fun recent(): RecentDeviceDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "droplink.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
