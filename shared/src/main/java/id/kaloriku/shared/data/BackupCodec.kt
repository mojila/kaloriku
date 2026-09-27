package id.kaloriku.shared.data

import id.kaloriku.shared.domain.AppSettings
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.sync.FoodEntryJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-facing backup of the food log, as a self-contained JSON document.
 *
 * The format is intentionally explicit and versioned:
 *  - `format` names the document so a file that is not ours can be rejected clearly.
 *  - `version` lets a future build migrate an older file instead of guessing.
 *  - `entries` carries **tombstones too**. A backup that dropped deleted rows would
 *    restore an entry the user had deleted the moment the watch re-synced its copy.
 *
 * [createdAt] and [appVersion] are informational: they make a file identifiable in a
 * file manager without opening it, and are never used to make a restore decision.
 */
data class BackupFile(
    val entries: List<FoodEntry>,
    val settings: AppSettings,
    val createdAt: Long,
    val appVersion: String,
) {
    /** Entries the user would actually see; tombstones are excluded from the count. */
    val liveEntryCount: Int get() = entries.count { !it.deleted }
}

/** Why a backup document could not be read, so the UI can say something useful. */
sealed interface BackupError {
    /** The bytes were not valid JSON, or not shaped like a backup at all. */
    data object NotABackup : BackupError

    /** A backup from a newer app version than this build understands. */
    data class UnsupportedVersion(val found: Int) : BackupError

    /** Valid JSON, but with no usable entries. */
    data object Empty : BackupError
}

/** Result of parsing a backup document. */
sealed interface BackupParseResult {
    data class Success(val file: BackupFile) : BackupParseResult
    data class Failure(val error: BackupError) : BackupParseResult
}

/**
 * Encodes and decodes the backup document. Pure: no file or Android access, so the
 * whole format is unit-testable and the caller decides where the bytes live.
 */
object BackupCodec {

    /** Bumped only when the document shape changes incompatibly. */
    const val FORMAT_VERSION = 1

    const val FORMAT_NAME = "kaloriku-backup"

    fun encode(file: BackupFile): String = JSONObject().apply {
        put("format", FORMAT_NAME)
        put("version", FORMAT_VERSION)
        put("createdAt", file.createdAt)
        put("appVersion", file.appVersion)
        put(
            "settings",
            JSONObject().apply {
                put("dailyTargetKcal", file.settings.dailyTargetKcal)
            },
        )
        put(
            "entries",
            JSONArray().apply { file.entries.forEach { put(FoodEntryJson.encode(it)) } },
        )
    }.toString()

    /**
     * Parses a backup document.
     *
     * Never throws: a malformed file is reported as a [BackupParseResult.Failure] so the
     * UI can explain what went wrong instead of crashing on the user's data.
     *
     * Entries with a blank `syncId` are dropped rather than accepted. The syncId is the
     * identity the merge is keyed on, so an entry without one could never be
     * deduplicated and would be duplicated on every subsequent restore.
     */
    fun decode(raw: String): BackupParseResult {
        val json = runCatching { JSONObject(raw.trim()) }.getOrNull()
            ?: return BackupParseResult.Failure(BackupError.NotABackup)

        if (json.optString("format") != FORMAT_NAME) {
            return BackupParseResult.Failure(BackupError.NotABackup)
        }

        val version = json.optInt("version", 0)
        if (version > FORMAT_VERSION) {
            return BackupParseResult.Failure(BackupError.UnsupportedVersion(version))
        }

        val array = json.optJSONArray("entries")
            ?: return BackupParseResult.Failure(BackupError.NotABackup)

        val entries = buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val entry = runCatching { FoodEntryJson.decode(o) }.getOrNull() ?: continue
                // A row with no identity cannot be merged; keeping it would create a
                // duplicate on every restore, so it is rejected here instead.
                if (entry.syncId.isBlank()) continue
                if (entry.foodName.isBlank()) continue
                add(entry)
            }
        }

        if (entries.isEmpty()) {
            return BackupParseResult.Failure(BackupError.Empty)
        }

        val settingsJson = json.optJSONObject("settings")
        val target = settingsJson?.optInt("dailyTargetKcal", AppSettings.DEFAULT_TARGET)
            ?: AppSettings.DEFAULT_TARGET

        return BackupParseResult.Success(
            BackupFile(
                entries = entries,
                settings = AppSettings(dailyTargetKcal = target.coerceIn(500, 6000)),
                createdAt = json.optLong("createdAt", 0L),
                appVersion = json.optString("appVersion"),
            ),
        )
    }
}
