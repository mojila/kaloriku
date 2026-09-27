package id.kaloriku.shared.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "food_entries",
    indices = [Index(value = ["syncId"], unique = true)],
)
data class FoodEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable, device-independent identity used to merge phone and watch databases. */
    val syncId: String,
    val loggedAt: Long,
    val dayKey: String,
    val meal: String,
    val foodName: String,
    val canonicalName: String?,
    val portionText: String,
    val grams: Double?,
    val kcal: Int,
    val kcalLow: Int,
    val kcalHigh: Int,
    val confidence: Double,
    val isLocal: Boolean,
    /** True when the calories were grounded with a Kenari web search. */
    val webGrounded: Boolean = false,
    val healthScore: Double,
    val source: String,
    val rawTranscript: String?,
    val notes: String?,
    /** True for entries logged on this device that the peer has not acked yet. */
    val pendingSync: Boolean = false,
    /** Revision timestamp; the newest revision of a syncId wins during a merge. */
    val updatedAt: Long = loggedAt,
    /** True for a tombstone: hidden locally, delivered to the peer, blocks resurrection. */
    val deleted: Boolean = false,
)
