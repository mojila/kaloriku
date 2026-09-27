package id.kaloriku.shared

import id.kaloriku.shared.data.FoodEntryDao
import id.kaloriku.shared.data.FoodEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [FoodEntryDao] for unit tests. Mirrors the real Room semantics that
 * matter to sync: syncId uniqueness, pending flags and ordering by loggedAt.
 */
class FakeFoodEntryDao : FoodEntryDao {

    private val rows = MutableStateFlow<List<FoodEntryEntity>>(emptyList())
    private var nextId = 1L

    /** Mirrors the unique index on syncId: returns -1 on a duplicate. */
    private fun insertInternal(entry: FoodEntryEntity): Long {
        val current = rows.value
        if (entry.syncId.isNotBlank() && current.any { it.syncId == entry.syncId }) {
            return -1L
        }
        val id = if (entry.id != 0L) entry.id else nextId++
        if (id >= nextId) nextId = id + 1
        rows.value = current.filterNot { it.id == id } + entry.copy(id = id)
        return id
    }

    override suspend fun insert(entry: FoodEntryEntity): Long = insertInternal(entry)

    override suspend fun update(entry: FoodEntryEntity) {
        rows.value = rows.value.map { if (it.id == entry.id) entry else it }
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }

    override suspend fun tombstoneAll(now: Long) {
        rows.value = rows.value.map {
            if (it.deleted) it else it.copy(deleted = true, pendingSync = true, updatedAt = now)
        }
    }

    override suspend fun findById(id: Long): FoodEntryEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun findBySyncId(syncId: String): FoodEntryEntity? =
        rows.value.firstOrNull { it.syncId == syncId }

    override fun observeRecent(limit: Int): Flow<List<FoodEntryEntity>> =
        rows.map { list ->
            list.filterNot { it.deleted }.sortedByDescending { it.loggedAt }.take(limit)
        }

    override fun observeDay(dayKey: String): Flow<List<FoodEntryEntity>> =
        rows.map { list ->
            list.filter { it.dayKey == dayKey && !it.deleted }.sortedByDescending { it.loggedAt }
        }

    override suspend fun entriesForDay(dayKey: String): List<FoodEntryEntity> =
        rows.value.filter { it.dayKey == dayKey && !it.deleted }.sortedByDescending { it.loggedAt }

    override fun observeDayTotal(dayKey: String): Flow<Int> =
        rows.map { list -> list.filter { it.dayKey == dayKey && !it.deleted }.sumOf { it.kcal } }

    override suspend fun count(): Int = rows.value.count { !it.deleted }

    override suspend fun pendingSyncEntries(): List<FoodEntryEntity> =
        rows.value.filter { it.pendingSync }.sortedBy { it.loggedAt }

    override suspend fun markSynced(syncIds: List<String>) {
        if (syncIds.isEmpty()) return
        val set = syncIds.toSet()
        rows.value = rows.value.map { if (it.syncId in set) it.copy(pendingSync = false) else it }
    }

    override suspend fun purgeSyncedTombstones(olderThan: Long) {
        rows.value = rows.value.filterNot { it.deleted && !it.pendingSync && it.updatedAt < olderThan }
    }

    /** Test helper: everything currently stored, tombstones included. */
    override suspend fun all(): List<FoodEntryEntity> = rows.value.sortedBy { it.loggedAt }
}
