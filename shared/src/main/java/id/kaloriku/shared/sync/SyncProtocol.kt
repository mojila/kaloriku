package id.kaloriku.shared.sync

import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire protocol between the phone and the watch over Wear OS DataLayer.
 *
 * Both sides encode/decode with [SyncCodec]. Messages are routed by [path]; unknown
 * paths are ignored so the two apps can be updated independently.
 *
 * Entries carry a stable [FoodEntry.syncId] so the two databases can be merged
 * without relying on the per-device auto-increment row ids.
 */
sealed interface SyncMessage {
    val path: String

    /** Phone -> watch: today's entries plus the recent list, so the watch can render offline. */
    data class RecentUpdate(
        val entries: List<FoodEntry>,
        val todayTotal: Int,
        val target: Int,
    ) : SyncMessage {
        override val path: String get() = PATH_RECENT
    }

    /** Phone <-> watch: "send me your current state and anything you have not delivered". */
    data object RequestSync : SyncMessage {
        override val path: String get() = PATH_REQUEST
    }

    /** Watch -> phone: entries logged on the watch, to be merged into the phone DB. */
    data class NewEntries(val entries: List<FoodEntry>, val source: LogSource) : SyncMessage {
        override val path: String get() = PATH_NEW_ENTRIES
    }

    /** Phone -> watch: the sync ids that are now safely stored on the phone. */
    data class Ack(val syncIds: List<String>) : SyncMessage {
        override val path: String get() = PATH_ACK
    }

    companion object {
        const val PATH_RECENT = "/kaloriku/recent"
        const val PATH_REQUEST = "/kaloriku/request"
        const val PATH_NEW_ENTRIES = "/kaloriku/new-entries"
        const val PATH_ACK = "/kaloriku/ack"
        const val ALL_PATHS = "$PATH_RECENT,$PATH_REQUEST,$PATH_NEW_ENTRIES,$PATH_ACK"
    }
}

/** JSON codec for [SyncMessage]. Pure and unit-testable. */
object SyncCodec {

    fun encode(message: SyncMessage): ByteArray = when (message) {
        is SyncMessage.RecentUpdate -> JSONObject().apply {
            put("kind", "recent")
            put("todayTotal", message.todayTotal)
            put("target", message.target)
            put("entries", encodeEntries(message.entries))
        }
        is SyncMessage.RequestSync -> JSONObject().put("kind", "request")
        is SyncMessage.NewEntries -> JSONObject().apply {
            put("kind", "new")
            put("source", message.source.name)
            put("entries", encodeEntries(message.entries))
        }
        is SyncMessage.Ack -> JSONObject().apply {
            put("kind", "ack")
            put("count", message.syncIds.size)
            put("syncIds", JSONArray().apply { message.syncIds.forEach { put(it) } })
        }
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(path: String, bytes: ByteArray): SyncMessage? {
        val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        val kind = json.optString("kind").ifBlank {
            when (path) {
                SyncMessage.PATH_RECENT -> "recent"
                SyncMessage.PATH_REQUEST -> "request"
                SyncMessage.PATH_NEW_ENTRIES -> "new"
                SyncMessage.PATH_ACK -> "ack"
                else -> ""
            }
        }
        return when (kind) {
            "recent" -> SyncMessage.RecentUpdate(
                entries = decodeEntries(json.optJSONArray("entries")),
                todayTotal = json.optInt("todayTotal", 0),
                target = json.optInt("target", 0),
            )
            "request" -> SyncMessage.RequestSync
            "new" -> SyncMessage.NewEntries(
                entries = decodeEntries(json.optJSONArray("entries")),
                source = LogSource.fromKey(json.optString("source")),
            )
            "ack" -> SyncMessage.Ack(decodeStrings(json.optJSONArray("syncIds")))
            else -> null
        }
    }

    private fun encodeEntries(entries: List<FoodEntry>): JSONArray = JSONArray().apply {
        entries.forEach { put(FoodEntryJson.encode(it)) }
    }

    private fun decodeEntries(array: JSONArray?): List<FoodEntry> {
        if (array == null) return emptyList()
        val out = mutableListOf<FoodEntry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out += FoodEntryJson.decode(o)
        }
        return out
    }

    private fun decodeStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) out += value
        }
        return out
    }
}
