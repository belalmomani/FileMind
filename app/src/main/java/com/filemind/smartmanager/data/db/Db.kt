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

/** A bulk operation that was started; its items are removed only when every item finished (so an interrupted run can be resumed). */
@Entity(tableName = "pending_op")
data class PendingOp(@PrimaryKey(autoGenerate = true) val id: Long = 0, val kind: String, val destTree: String, val createdMs: Long)

@Entity(tableName = "pending_item", indices = [Index("opId")])
data class PendingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val opId: Long,
    val path: String, val name: String, val size: Long, val modifiedMs: Long?, val parent: String,
    val destDir: String, val done: Boolean = false,
)

@Entity(tableName = "indexed_file")
data class IndexedFile(@PrimaryKey val path: String, val name: String, val size: Long, val modifiedMs: Long?, val exifDateMs: Long?, val parent: String)

data class RemainingRow(val opId: Long, val remaining: Int)

/** Locally stored reference set (path, size, SHA-256) used to detect missing/changed files later. */
@Entity(tableName = "snapshot")
data class SnapshotEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdMs: Long, val json: String)

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

@Dao
interface PendingDao {
    @Insert suspend fun insertOp(op: PendingOp): Long
    @Insert suspend fun insertItems(items: List<PendingItem>)
    @Query("SELECT * FROM pending_op WHERE id = :id") suspend fun op(id: Long): PendingOp?
    @Query("SELECT * FROM pending_item WHERE opId = :id AND done = 0") suspend fun remaining(id: Long): List<PendingItem>
    @Query("SELECT COUNT(*) FROM pending_item WHERE opId = :id") suspend fun total(id: Long): Int
    @Query("UPDATE pending_item SET done = 1 WHERE opId = :opId AND path = :path") suspend fun markDone(opId: Long, path: String)
    @Query("DELETE FROM pending_item WHERE opId = :id") suspend fun deleteItems(id: Long)
    @Query("DELETE FROM pending_op WHERE id = :id") suspend fun deleteOp(id: Long)
    @Query("SELECT opId AS opId, COUNT(*) AS remaining FROM pending_item WHERE done = 0 GROUP BY opId") fun remainingByOp(): Flow<List<RemainingRow>>
}

/** Local file index: a CACHE of the last scan (never treated as truth; every launch rescans). */
@Dao
abstract class IndexDao {
    @Query("SELECT * FROM indexed_file") abstract suspend fun all(): List<IndexedFile>
    @Query("DELETE FROM indexed_file") abstract suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertAll(l: List<IndexedFile>)
    @Transaction open suspend fun replaceAll(l: List<IndexedFile>) { clear(); l.chunked(500).forEach { insertAll(it) } }
}

@Dao
interface SnapshotDao {
    @Insert suspend fun put(s: SnapshotEntity): Long
    @Query("SELECT * FROM snapshot ORDER BY createdMs DESC") fun all(): Flow<List<SnapshotEntity>>
    @Query("SELECT * FROM snapshot WHERE id = :id") suspend fun get(id: Long): SnapshotEntity?
    @Delete suspend fun delete(s: SnapshotEntity)
}

@Database(
    entities = [OperationLog::class, SavedFilterEntity::class, SavedTaskEntity::class, PendingOp::class, PendingItem::class, SnapshotEntity::class, IndexedFile::class],
    version = 3, exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logs(): LogDao
    abstract fun filters(): FilterDao
    abstract fun tasks(): TaskDao
    abstract fun pending(): PendingDao
    abstract fun snapshots(): SnapshotDao
    abstract fun index(): IndexDao
    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            // TODO: replace destructive fallback with real migrations once the app is published.
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "filemind.db")
                .fallbackToDestructiveMigration().build().also { inst = it }
        }
    }
}
