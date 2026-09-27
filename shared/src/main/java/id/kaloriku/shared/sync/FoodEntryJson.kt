package id.kaloriku.shared.sync

import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import org.json.JSONObject

/**
 * The single JSON mapping for a [FoodEntry], shared by the phone<->watch sync wire
 * format and the user-facing backup file.
 *
 * Both need to round-trip *every* field of an entry, and a field silently missing from
 * one of them would mean data loss that is invisible until a restore is needed. Keeping
 * one mapping means a new field cannot be added to the app and forgotten by the backup.
 *
 * Decoding is deliberately lenient about absent fields: a backup written by an older
 * build, or a message from a peer that predates a field, must still load. Defaults are
 * chosen so an older entry reads as its own first revision, which keeps merges sane.
 */
internal object FoodEntryJson {

    /**
     * JSON cannot represent a non-finite number: `JSONObject.put` throws
     * `JSONException: JSON does not allow non-finite numbers`, and `optDouble` silently
     * turns a non-numeric value (`"gram": "sekitar 100"`) into `NaN`. Either end of this
     * mapping is reachable from untrusted input — a model's extraction on the way in, a
     * hand-edited or truncated backup file on the way out — so every double is funnelled
     * through here rather than being written or read raw. A non-finite value means "not
     * stated", which is the same thing the rest of the app means by a missing weight.
     */
    private fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }

    private fun Double.finiteOr(fallback: Double): Double = takeIf { it.isFinite() } ?: fallback

    fun encode(entry: FoodEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("syncId", entry.syncId)
        put("loggedAt", entry.loggedAt)
        put("dayKey", entry.dayKey)
        put("meal", entry.meal.name)
        put("foodName", entry.foodName)
        put("canonicalName", entry.canonicalName ?: JSONObject.NULL)
        put("portionText", entry.portionText)
        put("grams", entry.grams?.finiteOrNull() ?: JSONObject.NULL)
        put("kcal", entry.kcal)
        put("kcalLow", entry.kcalLow)
        put("kcalHigh", entry.kcalHigh)
        put("confidence", entry.confidence.finiteOr(1.0))
        put("isLocal", entry.isLocal)
        put("webGrounded", entry.webGrounded)
        put("healthScore", entry.healthScore.finiteOr(0.0))
        put("source", entry.source.name)
        put("rawTranscript", entry.rawTranscript ?: JSONObject.NULL)
        put("notes", entry.notes ?: JSONObject.NULL)
        put("updatedAt", entry.updatedAt)
        put("deleted", entry.deleted)
    }

    fun decode(o: JSONObject): FoodEntry {
        val loggedAt = o.optLong("loggedAt")
        return FoodEntry(
            id = o.optLong("id", 0),
            syncId = o.optString("syncId"),
            loggedAt = loggedAt,
            dayKey = o.optString("dayKey").ifBlank { JakartaTime.dayKey(loggedAt) },
            meal = MealType.fromKey(o.optString("meal")),
            foodName = o.optString("foodName"),
            canonicalName = if (o.isNull("canonicalName")) null else o.optString("canonicalName"),
            portionText = o.optString("portionText"),
            // Absent and malformed both mean "not stated"; a non-numeric weight must not
            // become NaN and be carried into the next export, where it would throw.
            grams = if (o.isNull("grams")) null else o.optDouble("grams").finiteOrNull(),
            kcal = o.optInt("kcal", 0),
            kcalLow = o.optInt("kcalLow", o.optInt("kcal", 0)),
            kcalHigh = o.optInt("kcalHigh", o.optInt("kcal", 0)),
            confidence = o.optDouble("confidence", 1.0).finiteOr(1.0),
            isLocal = o.optBoolean("isLocal", false),
            // A peer on an older build sends no flag; its entries predate grounding.
            webGrounded = o.optBoolean("webGrounded", false),
            healthScore = o.optDouble("healthScore", 0.0).finiteOr(0.0),
            source = LogSource.fromKey(o.optString("source")),
            rawTranscript = if (o.isNull("rawTranscript")) null else o.optString("rawTranscript"),
            notes = if (o.isNull("notes")) null else o.optString("notes"),
            // A peer running an older build sends neither field. Treating such an
            // entry as its own first revision keeps merges sane: any local edit
            // (which bumps updatedAt) wins over it rather than being clobbered.
            updatedAt = o.optLong("updatedAt", loggedAt),
            deleted = o.optBoolean("deleted", false),
        )
    }
}
