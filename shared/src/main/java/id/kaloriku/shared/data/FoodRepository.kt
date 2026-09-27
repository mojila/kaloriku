package id.kaloriku.shared.data

import id.kaloriku.shared.domain.AnalysisResult
import id.kaloriku.shared.domain.AppSettings
import id.kaloriku.shared.domain.DailySummary
import id.kaloriku.shared.domain.DayTotal
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Maps between the Room entity and the domain model. */
private fun FoodEntryEntity.toDomain() = FoodEntry(
    id = id,
    syncId = syncId,
    loggedAt = loggedAt,
    dayKey = dayKey,
    meal = MealType.fromKey(meal),
    foodName = foodName,
    canonicalName = canonicalName,
    portionText = portionText,
    grams = grams,
    kcal = kcal,
    kcalLow = kcalLow,
    kcalHigh = kcalHigh,
    confidence = confidence,
    isLocal = isLocal,
    webGrounded = webGrounded,
    healthScore = healthScore,
    source = LogSource.fromKey(source),
    rawTranscript = rawTranscript,
    notes = notes,
    updatedAt = updatedAt,
    deleted = deleted,
)

private fun FoodEntry.toEntity(
    pendingSync: Boolean = false,
    deleted: Boolean = this.deleted,
) = FoodEntryEntity(
    id = id,
    syncId = syncId,
    loggedAt = loggedAt,
    dayKey = dayKey,
    meal = meal.name,
    foodName = foodName,
    canonicalName = canonicalName,
    portionText = portionText,
    grams = grams,
    kcal = kcal,
    kcalLow = kcalLow,
    kcalHigh = kcalHigh,
    confidence = confidence,
    isLocal = isLocal,
    webGrounded = webGrounded,
    healthScore = healthScore,
    source = source.name,
    rawTranscript = rawTranscript,
    notes = notes,
    pendingSync = pendingSync,
    updatedAt = updatedAt,
    deleted = deleted,
)

/**
 * The single data entry point for both apps. Reads and writes food entries,
 * builds daily summaries and trends, and keeps the settings flow.
 *
 * Every entry carries a [FoodEntry.syncId]. Rows written locally start out with
 * `pendingSync = true`; the sync layer clears that flag once the peer confirms it
 * has stored them, which makes delivery retry-safe.
 */
class FoodRepository(
    private val dao: FoodEntryDao,
    private val settingsStore: SettingsSource,
) {

    val settings: Flow<AppSettings> = settingsStore.settings

    fun observeRecent(limit: Int = 20): Flow<List<FoodEntry>> =
        dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeDay(dayKey: String = JakartaTime.todayKey()): Flow<List<FoodEntry>> =
        dao.observeDay(dayKey).map { list -> list.map { it.toDomain() } }

    fun observeDayTotal(dayKey: String = JakartaTime.todayKey()): Flow<Int> =
        dao.observeDayTotal(dayKey)

    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<FoodEntry>> =
        dao.observeBetween(startMillis, endMillis).map { list -> list.map { it.toDomain() } }

    suspend fun recent(limit: Int = 20): List<FoodEntry> =
        dao.observeRecent(limit).first().map { it.toDomain() }

    suspend fun dayEntries(dayKey: String = JakartaTime.todayKey()): List<FoodEntry> =
        dao.entriesForDay(dayKey).map { it.toDomain() }

    /**
     * Looks up a single live entry by its local row id, or null when it does not exist
     * or has already been deleted. Lets an edit flow work on the entry itself instead
     * of searching a recency window.
     */
    suspend fun find(id: Long): FoodEntry? =
        dao.findById(id)?.takeUnless { it.deleted }?.toDomain()

    suspend fun saveAnalysis(
        result: AnalysisResult,
        source: LogSource,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<Long> {
        val ids = mutableListOf<Long>()
        result.items.forEachIndexed { index, item ->
            // Stagger the timestamps by a millisecond so ordering stays stable.
            val at = nowMillis + index
            val entry = FoodEntry(
                loggedAt = at,
                dayKey = JakartaTime.dayKey(at),
                meal = result.meal,
                foodName = item.name,
                canonicalName = item.canonicalName,
                portionText = item.portionText,
                grams = item.grams,
                kcal = item.kcal,
                kcalLow = item.kcalLow,
                kcalHigh = item.kcalHigh,
                confidence = item.confidence,
                isLocal = item.isLocal,
                webGrounded = item.webGrounded,
                healthScore = result.healthScore,
                source = source,
                rawTranscript = result.transcript,
            )
            ids += dao.insert(entry.toEntity(pendingSync = true))
        }
        return ids
    }

    /** Stores a locally created entry and queues it for the peer. */
    suspend fun saveEntry(entry: FoodEntry): Long =
        dao.insert(entry.toEntity(pendingSync = true))

    /**
     * Applies an edit to an existing entry.
     *
     * [updatedAt] is bumped so the change wins the next merge, and the row is marked
     * pending again so the peer receives the new revision. Returns false when the
     * entry no longer exists (deleted elsewhere in the meantime).
     *
     * [newLoggedAt] moves the entry to a different point in time (the user corrected
     * the date/time it was logged at). It is null for an ordinary edit, which keeps
     * the original [FoodEntry.loggedAt] — the identity of the row — untouched. When a
     * new time is given, [FoodEntry.dayKey] is recomputed from it so the entry shows up
     * on the day it was actually eaten, and the new value is delivered to the peer like
     * any other revision. A reschedule must not be done through [transform] alone: the
     * repository deliberately overwrites [FoodEntry.loggedAt] and [FoodEntry.dayKey]
     * after the transform runs, so the two fields stay consistent with each other.
     */
    suspend fun updateEntry(
        id: Long,
        nowMillis: Long = System.currentTimeMillis(),
        newLoggedAt: Long? = null,
        transform: (FoodEntry) -> FoodEntry,
    ): Boolean {
        val existing = dao.findById(id) ?: return false
        if (existing.deleted) return false
        // An edit revision must never predate the entry's own loggedAt, otherwise a
        // reschedule to a future date would leave updatedAt < loggedAt. It must also stay
        // strictly greater than loggedAt: isEdited is defined as updatedAt > loggedAt, so
        // a revision that merely equals it would make a reschedule look un-edited.
        // Only a reschedule can move loggedAt, so an ordinary edit is left untouched.
        val revision = if (newLoggedAt == null) {
            nowMillis
        } else {
            val floor = if (newLoggedAt == Long.MAX_VALUE) Long.MAX_VALUE else newLoggedAt + 1
            maxOf(nowMillis, floor)
        }
        val edited = transform(existing.toDomain()).copy(
            id = existing.id,
            syncId = existing.syncId,
            loggedAt = newLoggedAt ?: existing.loggedAt,
            dayKey = newLoggedAt?.let { JakartaTime.dayKey(it) } ?: existing.dayKey,
            updatedAt = revision,
            deleted = false,
        )
        dao.update(edited.toEntity(pendingSync = true))
        return true
    }

    /**
     * Deletes an entry.
     *
     * The row is always kept as a tombstone and queued for the peer, so the deletion
     * cannot be undone by a copy still sitting on the other device. Tombstoning
     * unconditionally is deliberate: "pending" only means the peer has not acked the
     * latest revision, so an entry can be un-pending and still have an older copy on
     * the peer. Removing the row outright would let that copy come back to life.
     */
    suspend fun delete(
        id: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val existing = dao.findById(id) ?: return
        if (existing.deleted) return
        dao.update(
            existing.copy(
                deleted = true,
                pendingSync = true,
                updatedAt = nowMillis,
            ),
        )
    }

    /**
     * Merges entries received from the peer.
     *
     * Merging is idempotent and last-write-wins per [FoodEntry.syncId]: an incoming
     * revision is applied only when it is newer than the local one, so re-delivery is
     * harmless and an edit or delete made on either device eventually wins everywhere.
     * A tombstone that arrives for a locally newer entry is ignored, which is what
     * stops a delete from being undone by a stale copy.
     *
     * Note that a rejected revision is still reported as accepted. That is deliberate:
     * last-write-wins already decides the winner, and the loser is corrected by this
     * device's own revision travelling back (every sync also sends a recent list), so
     * acking cannot leave the two sides apart. Not acking would be worse — a rejected
     * revision on an entry older than the recent-list window would be re-pushed on
     * every exchange, forever, because nothing would ever relay this side's copy back.
     *
     * @return the sync ids the caller should ack to the sender.
     */
    suspend fun mergeSynced(entries: List<FoodEntry>): List<String> {
        val accepted = mutableListOf<String>()
        entries.forEach { entry ->
            if (entry.syncId.isBlank()) return@forEach
            val existing = dao.findBySyncId(entry.syncId)
            if (existing == null) {
                // Insert verbatim, preserving the origin device's row id when free.
                dao.insert(entry.copy(id = 0).toEntity(pendingSync = false))
                accepted += entry.syncId
                return@forEach
            }
            if (entry.supersedes(existing)) {
                // Newer revision from the peer: apply it but keep our row id and do not
                // re-queue it, otherwise the two devices would echo it forever.
                dao.update(
                    entry.copy(id = existing.id).toEntity(
                        pendingSync = false,
                        deleted = entry.deleted,
                    ),
                )
            }
            accepted += entry.syncId
        }
        return accepted.distinct()
    }

    /** Entries logged here that the peer has not confirmed yet. */
    suspend fun pendingSync(): List<FoodEntry> =
        dao.pendingSyncEntries().map { it.toDomain() }

    /** Marks entries as delivered once the peer has acked them. */
    suspend fun markSynced(
        syncIds: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (syncIds.isEmpty()) return
        dao.markSynced(syncIds)
        // Tombstones have served their purpose once the peer has the deletion, but
        // they are kept for a grace period so a stale copy still queued on the peer
        // cannot resurrect the entry (see FoodEntryDao.purgeSyncedTombstones).
        // Guard the subtraction so an absurd clock cannot wrap into a positive cutoff
        // and sweep away tombstones that are still protecting a deletion.
        val cutoff = if (nowMillis < TOMBSTONE_GRACE_MILLIS) 0L else nowMillis - TOMBSTONE_GRACE_MILLIS
        dao.purgeSyncedTombstones(cutoff)
    }

    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Deletes every live entry as a tombstone and queues the whole batch for the
     * peer, so clearing the log here also clears it on the other device.
     *
     * Prefer this over [deleteAll] whenever the deletion must reach the peer:
     * [deleteAll] is a hard local wipe the peer never hears about, which would let
     * its copy survive and later resurface on this device.
     */
    suspend fun deleteAllSynced(nowMillis: Long = System.currentTimeMillis()) {
        dao.tombstoneAll(nowMillis)
    }

    suspend fun count(): Int = dao.count()

    /**
     * Every row, tombstones included, for writing a backup.
     *
     * Tombstones are deliberately part of a backup: restoring a log without them would
     * let a deleted entry come back to life the moment the watch re-syncs its copy.
     */
    suspend fun allForBackup(): List<FoodEntry> =
        dao.all().map { it.toDomain() }

    /**
     * Restores entries from a backup.
     *
     * The restore is authoritative: each entry is written with a revision of [nowMillis]
     * so it wins the next merge against anything already on either device. That bump is
     * what makes a restore actually work rather than silently no-op — an entry restored
     * with its original, older `updatedAt` would lose to a local tombstone (the user
     * cleared their data) or to a newer copy on the watch, and would be deleted again.
     *
     * "Authoritative" holds while [nowMillis] is at least the competing revision. A row
     * whose stored revision is *later* than [nowMillis] keeps that later value (see the
     * `maxOf` below), so a future-dated tombstone can still win — correct, since the
     * clock cannot be trusted to decide that a deletion never happened.
     *
     * A consequence worth knowing: a restored row reports as edited, because it genuinely
     * was rewritten just now. That is the price of guaranteeing the restore sticks.
     *
     * Rows are matched on [FoodEntry.syncId], so restoring the same file twice is
     * idempotent instead of duplicating the log. Entries already present keep their local
     * row id; new ones are inserted. Returns the number of rows written.
     */
    suspend fun restore(entries: List<FoodEntry>, nowMillis: Long = System.currentTimeMillis()): Int {
        var written = 0
        entries.forEach { entry ->
            // Same validity bar as the decoder: a row with no identity could never be
            // deduplicated, and one with no name is invisible in the log. Rejecting both
            // here keeps the method safe for any caller, not just BackupCodec's.
            if (entry.syncId.isBlank() || entry.foodName.isBlank()) return@forEach
            val existing = dao.findBySyncId(entry.syncId)
            val revision = maxOf(nowMillis, entry.updatedAt)
            if (existing == null) {
                dao.insert(
                    entry.copy(
                        id = 0,
                        updatedAt = revision,
                        // Queue it so the peer converges to the restored state too.
                    ).toEntity(pendingSync = true, deleted = entry.deleted),
                )
            } else {
                dao.update(
                    entry.copy(
                        id = existing.id,
                        updatedAt = revision,
                    ).toEntity(pendingSync = true, deleted = entry.deleted),
                )
            }
            written++
        }
        return written
    }

    suspend fun summary(dayKey: String = JakartaTime.todayKey()): DailySummary {
        val entries = dayEntries(dayKey)
        return summarize(dayKey, entries)
    }

    suspend fun summaries(days: Int, endDay: String = JakartaTime.todayKey()): List<DailySummary> =
        JakartaTime.lastDayKeys(days, endDay).map { summary(it) }

    suspend fun trend(days: Int, endDay: String = JakartaTime.todayKey()): List<DayTotal> =
        JakartaTime.lastDayKeys(days, endDay).map { key ->
            val entries = dayEntries(key)
            DayTotal(key, entries.sumOf { it.kcal }, entries.size)
        }

    /** The highest-calorie foods over the last [days], aggregated by catalog identity. */
    suspend fun topFoods(days: Int, limit: Int = 5): List<Pair<String, Int>> =
        JakartaTime.lastDayKeys(days)
            .flatMap { dayEntries(it) }
            .groupBy { it.canonicalName ?: it.foodName.lowercase() }
            .map { (_, list) -> list.first().foodName to list.sumOf { it.kcal } }
            .sortedByDescending { it.second }
            .take(limit)

    private fun summarize(dayKey: String, entries: List<FoodEntry>): DailySummary {
        val byMeal = MealType.entries.associateWith { meal ->
            entries.filter { it.meal == meal }.sumOf { it.kcal }
        }
        return DailySummary(
            dayKey = dayKey,
            totalKcal = entries.sumOf { it.kcal },
            entryCount = entries.size,
            byMeal = byMeal,
            localKcal = entries.filter { it.isLocal }.sumOf { it.kcal },
            avgHealthScore = entries.map { it.healthScore }.filter { it > 0.0 }
                .takeIf { it.isNotEmpty() }?.average() ?: 0.0,
        )
    }

    companion object {
        /**
         * How long a delivered tombstone is retained before being dropped. Bounds
         * table growth while leaving room for the peer to settle its own queue.
         */
        const val TOMBSTONE_GRACE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * Whether this incoming revision should replace [existing] during a merge.
 *
 * The newest revision wins. Ties go to a deletion: two devices can only produce the
 * same millisecond by coincidence, and when that happens the safe reading is that the
 * user deleted the entry — letting a live copy win would resurrect it on the peer.
 */
private fun FoodEntry.supersedes(existing: FoodEntryEntity): Boolean = when {
    updatedAt != existing.updatedAt -> updatedAt > existing.updatedAt
    deleted != existing.deleted -> deleted
    else -> false
}
