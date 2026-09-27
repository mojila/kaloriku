package id.kaloriku.shared.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room access for food entries.
 *
 * Deleted rows are kept as tombstones (`deleted = 1`) so the deletion can reach the
 * peer device. Every read that feeds the UI or the statistics filters them out; only
 * [pendingSyncEntries] and [findBySyncId] see them, because the sync layer must be
 * able to deliver and reconcile tombstones.
 */
@Dao
interface FoodEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FoodEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<FoodEntryEntity>)

    @Update
    suspend fun update(entry: FoodEntryEntity)

    /**
     * Removes the row outright. Used for entries that were never delivered anywhere
     * (no peer copy can exist) and for local cleanup; otherwise prefer tombstoning
     * via [FoodRepository] so the peer learns about the deletion.
     */
    @Query("DELETE FROM food_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM food_entries")
    suspend fun deleteAll()

    /**
     * Tombstones every live entry in one statement, so a "delete all" travels to
     * the peer as deletions instead of vanishing only on this device. Rows that are
     * already tombstones are left untouched.
     */
    @Query("UPDATE food_entries SET deleted = 1, pendingSync = 1, updatedAt = :now WHERE deleted = 0")
    suspend fun tombstoneAll(now: Long)

    @Query("SELECT * FROM food_entries WHERE id = :id")
    suspend fun findById(id: Long): FoodEntryEntity?

    @Query("SELECT * FROM food_entries WHERE syncId = :syncId LIMIT 1")
    suspend fun findBySyncId(syncId: String): FoodEntryEntity?

    @Query("SELECT * FROM food_entries WHERE deleted = 0 ORDER BY loggedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM food_entries WHERE deleted = 0 AND dayKey = :dayKey ORDER BY loggedAt DESC")
    fun observeDay(dayKey: String): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM food_entries WHERE deleted = 0 AND dayKey = :dayKey ORDER BY loggedAt DESC")
    suspend fun entriesForDay(dayKey: String): List<FoodEntryEntity>

    @Query("SELECT * FROM food_entries WHERE deleted = 0 AND loggedAt BETWEEN :start AND :end ORDER BY loggedAt DESC")
    suspend fun entriesBetween(start: Long, end: Long): List<FoodEntryEntity>

    @Query("SELECT * FROM food_entries WHERE deleted = 0 AND loggedAt BETWEEN :start AND :end ORDER BY loggedAt DESC")
    fun observeBetween(start: Long, end: Long): Flow<List<FoodEntryEntity>>

    @Query("SELECT COALESCE(SUM(kcal), 0) FROM food_entries WHERE deleted = 0 AND dayKey = :dayKey")
    fun observeDayTotal(dayKey: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM food_entries WHERE deleted = 0")
    suspend fun count(): Int

    /**
     * Every row in the table, tombstones included. Used only by the backup export: a
     * backup must carry deletions as well, or a restore would resurrect entries the
     * user had removed once the peer re-syncs its copy.
     */
    @Query("SELECT * FROM food_entries ORDER BY loggedAt ASC")
    suspend fun all(): List<FoodEntryEntity>

    /**
     * Entries changed here that the peer has not confirmed receiving yet. Includes
     * tombstones: a deletion is a change the peer must apply too.
     */
    @Query("SELECT * FROM food_entries WHERE pendingSync = 1 ORDER BY loggedAt ASC")
    suspend fun pendingSyncEntries(): List<FoodEntryEntity>

    /** Clears the pending flag for the given sync ids. Call with a non-empty list. */
    @Query("UPDATE food_entries SET pendingSync = 0 WHERE syncId IN (:syncIds)")
    suspend fun markSynced(syncIds: List<String>)

    /**
     * Tombstones the peer has confirmed and that are old enough to be safely dropped.
     *
     * The age cutoff matters: a peer that has not yet acked its own stale copy of an
     * entry would re-push that copy, and with no tombstone left the entry would come
     * back from the dead. Keeping tombstones for a grace period closes that window
     * while still bounding table growth.
     */
    @Query("DELETE FROM food_entries WHERE deleted = 1 AND pendingSync = 0 AND updatedAt < :olderThan")
    suspend fun purgeSyncedTombstones(olderThan: Long)
}
