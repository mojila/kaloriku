package id.kaloriku.shared.domain

/** Which meal a logged entry belongs to. */
enum class MealType(val label: String) {
    SARAPAN("Sarapan"),
    MAKAN_SIANG("Makan Siang"),
    MAKAN_MALAM("Makan Malam"),
    CAMILAN("Camilan"),
    ;

    companion object {
        fun fromKey(key: String?): MealType =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: CAMILAN
    }
}

/** Where an entry came from. */
enum class LogSource(val label: String) {
    VOICE_PHONE("Suara (HP)"),
    VOICE_WATCH("Suara (Jam)"),
    TEXT("Teks"),
    MANUAL("Manual"),
    ;

    companion object {
        fun fromKey(key: String?): LogSource =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: MANUAL
    }
}

/**
 * A single food item produced by the analysis pipeline, before it is persisted.
 *
 * [kcal] is the Jev point estimate; [kcalLow] and [kcalHigh] are the low/high
 * bounds derived from the score distribution so the UI can show an honest range.
 */
data class AnalyzedItem(
    val name: String,
    val canonicalName: String? = null,
    val portionText: String = "",
    val grams: Double? = null,
    val kcal: Int = 0,
    val kcalLow: Int = 0,
    val kcalHigh: Int = 0,
    val confidence: Double = 0.0,
    val isLocal: Boolean = false,
    val needsClarification: Boolean = false,
    /**
     * Dominant macro profile of this item, decided by Jev (never by heuristics).
     * One of: karbohidrat, protein, lemak, serat, seimbang, tidak_jelas.
     * Transient: shown in the UI and used for insights, not persisted to the DB.
     */
    val macroProfile: String = "tidak_jelas",
    /**
     * True when this item's calories were grounded with a Kenari web search because
     * the local catalog did not know it (typically a branded or restaurant food).
     * Jev still produced the number; the flag only tells the user where it came from.
     */
    val webGrounded: Boolean = false,
)

/** The full result of analyzing one utterance or typed sentence. */
data class AnalysisResult(
    val transcript: String,
    val normalized: String,
    val items: List<AnalyzedItem>,
    val meal: MealType,
    val healthScore: Double,
    val needsClarification: Boolean,
    val totalKcal: Int,
    val totalLow: Int,
    val totalHigh: Int,
    val isDuplicate: Boolean = false,
    val duplicateOfId: Long? = null,
    val engine: String = "",
) {
    val itemCount: Int get() = items.size
    val confidence: Double
        get() = if (items.isEmpty()) 0.0 else items.map { it.confidence }.average()

    /** True when at least one item was grounded with a Kenari web search. */
    val usedWebGrounding: Boolean get() = items.any { it.webGrounded }
}

/**
 * A persisted, logged food entry.
 *
 * [updatedAt] and [deleted] make the phone<->watch merge last-write-wins and let a
 * deletion travel as a tombstone instead of being silently resurrected by the peer.
 */
data class FoodEntry(
    val id: Long = 0,
    /**
     * Stable identity that survives the trip between phone and watch. The peer
     * merges on this value, so it must never be derived from the local row id.
     */
    val syncId: String = newSyncId(),
    val loggedAt: Long,
    val dayKey: String,
    val meal: MealType,
    val foodName: String,
    val canonicalName: String? = null,
    val portionText: String = "",
    val grams: Double? = null,
    val kcal: Int,
    val kcalLow: Int = kcal,
    val kcalHigh: Int = kcal,
    val confidence: Double = 1.0,
    val isLocal: Boolean = false,
    /** True when the calories were grounded with a Kenari web search. */
    val webGrounded: Boolean = false,
    val healthScore: Double = 0.0,
    val source: LogSource = LogSource.MANUAL,
    val rawTranscript: String? = null,
    val notes: String? = null,
    /**
     * Revision timestamp for conflict resolution. Starts equal to [loggedAt] and is
     * bumped on every edit or delete, so the newest revision of a [syncId] wins.
     */
    val updatedAt: Long = loggedAt,
    /**
     * True for a tombstone: the entry was deleted here and the row is kept only so
     * the deletion can be delivered to the peer and stale copies cannot resurrect it.
     * Deleted entries are never shown or counted.
     */
    val deleted: Boolean = false,
) {
    val kcalRangeText: String
        get() = if (kcalLow == kcalHigh) "$kcal kkal" else "$kcalLow-$kcalHigh kkal"

    /** True when the entry has been changed since it was first logged. */
    val isEdited: Boolean get() = updatedAt > loggedAt

    companion object {
        /**
         * A collision-resistant id. Combines the wall clock with a per-process
         * counter and a random suffix; two devices logging in the same millisecond
         * still produce different ids.
         */
        fun newSyncId(nowMillis: Long = System.currentTimeMillis()): String =
            "$nowMillis-${counter.incrementAndGet()}-${randomSuffix()}"

        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        private val random = java.util.Random()

        private fun randomSuffix(): String =
            java.lang.Long.toHexString(random.nextLong() and 0xFFFFFFFFL)
    }
}

/** Aggregated numbers for one day. */
data class DailySummary(
    val dayKey: String,
    val totalKcal: Int,
    val entryCount: Int,
    val byMeal: Map<MealType, Int>,
    val localKcal: Int,
    val avgHealthScore: Double,
) {
    val localShare: Double
        get() = if (totalKcal == 0) 0.0 else localKcal.toDouble() / totalKcal
}

/** One point in a trend series. */
data class DayTotal(val dayKey: String, val kcal: Int, val entries: Int)

/** The settings the apps share. */
data class AppSettings(
    val dailyTargetKcal: Int = DEFAULT_TARGET,
) {
    companion object {
        const val DEFAULT_TARGET = 2000
    }
}
