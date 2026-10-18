package com.filemind.smartmanager.data.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "operation_log")
data class OperationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, val timeMs: Long,
    val oldName: String, val newName: String, val oldPath: String, val newPath: String,
    val size: Long, val status: String, val reason: String?, val taskId: Long? = null,
)

@Entity(tableName = "saved_filter")
data class SavedFilterEntity(@PrimaryKey val sectionKey: String, val name: String, val json: String)

@Entity(tableName = "saved_task")
data class SavedTaskEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val json: String)

@Dao
interface LogDao {
    @Insert suspend fun insert(l: OperationLog)
    @Query("SELECT * FROM operation_log ORDER BY timeMs DESC LIMIT 1000") fun all(): Flow<List<OperationLog>>
    @Query("DELETE FROM operation_log") suspend fun clear()
    @Query("UPDATE operation_log SET status = :s WHERE id = :id") suspend fun setStatus(id: Long, s: String)
    @Query("DELETE FROM operation_log WHERE timeMs < :before") suspend fun deleteOlderThan(before: Long)
}

@Dao
interface FilterDao {
    @Query("SELECT * FROM saved_filter WHERE sectionKey = :k") suspend fun get(k: String): SavedFilterEntity?
    @Upsert suspend fun put(f: SavedFilterEntity)
    @Query("DELETE FROM saved_filter WHERE sectionKey = :k") suspend fun reset(k: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM saved_task ORDER BY name") fun all(): Flow<List<SavedTaskEntity>>
    @Upsert suspend fun put(t: SavedTaskEntity)
    @Delete suspend fun delete(t: SavedTaskEntity)
}

@Database(entities = [OperationLog::class, SavedFilterEntity::class, SavedTaskEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logs(): LogDao
    abstract fun filters(): FilterDao
    abstract fun tasks(): TaskDao
    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "filemind.db").build().also { inst = it }
        }
    }
}
