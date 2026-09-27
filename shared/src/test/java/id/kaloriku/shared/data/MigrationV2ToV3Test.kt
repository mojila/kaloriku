package id.kaloriku.shared.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract check for the v2 -> v3 migration and the exported schema.
 *
 * Room validates the real database against the exported JSON at runtime, so the
 * JSON is the source of truth. These tests assert that the committed schema version
 * matches the [KaloriKuDatabase] entity version and that the two new bookkeeping
 * columns exist and are NOT NULL, and that the migration source adds them with a
 * DEFAULT and back-fills `updatedAt` from `loggedAt`.
 *
 * The directory is found relative to the working directory of the test JVM, which
 * Gradle sets to the module directory (`shared/`).
 */
class MigrationV2ToV3Test {

    private fun schemaDir(): File {
        val candidates = listOf(
            File("schemas/id.kaloriku.shared.data.KaloriKuDatabase"),
            File("shared/schemas/id.kaloriku.shared.data.KaloriKuDatabase"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("schema directory not found from ${File(".").absolutePath}")
    }

    private fun schema(version: Int): JSONObject =
        JSONObject(File(schemaDir(), "$version.json").readText()).getJSONObject("database")

    private fun migrationSource(): File =
        File("src/main/java/id/kaloriku/shared/data/KaloriKuDatabase.kt")
            .takeIf { it.exists() }
            ?: File("shared/src/main/java/id/kaloriku/shared/data/KaloriKuDatabase.kt")

    @Test
    fun `schema 3 exists and is version 3 with the new columns NOT NULL`() {
        val db = schema(3)
        assertEquals(3, db.getInt("version"))

        val entity = db.getJSONArray("entities").getJSONObject(0)
        assertEquals("food_entries", entity.getString("tableName"))

        val fields = entity.getJSONArray("fields")
        val byName = (0 until fields.length()).associate { i ->
            fields.getJSONObject(i).getString("columnName") to fields.getJSONObject(i)
        }

        val updatedAt = byName["updatedAt"] ?: error("updatedAt missing from 3.json")
        val deleted = byName["deleted"] ?: error("deleted missing from 3.json")

        assertEquals("INTEGER", updatedAt.getString("affinity"))
        assertTrue("updatedAt must be NOT NULL", updatedAt.getBoolean("notNull"))
        assertEquals("INTEGER", deleted.getString("affinity"))
        assertTrue("deleted must be NOT NULL", deleted.getBoolean("notNull"))
    }

    @Test
    fun `schema 2 does not yet contain the edit bookkeeping columns`() {
        val columns = (0 until schema(2).getJSONArray("entities").getJSONObject(0)
            .getJSONArray("fields").length())
            .map { schema(2).getJSONArray("entities").getJSONObject(0).getJSONArray("fields").getJSONObject(it).getString("columnName") }
        assertTrue("v2 predates editing", "updatedAt" !in columns)
        assertTrue("v2 predates tombstones", "deleted" !in columns)
        assertTrue("v2 predates web grounding", "webGrounded" !in columns)
    }

    @Test
    fun `schema 4 adds webGrounded NOT NULL and keeps the v3 columns`() {
        val db = schema(4)
        assertEquals(4, db.getInt("version"))

        val entity = db.getJSONArray("entities").getJSONObject(0)
        assertEquals("food_entries", entity.getString("tableName"))
        val fields = entity.getJSONArray("fields")
        val byName = (0 until fields.length()).associate { i ->
            fields.getJSONObject(i).getString("columnName") to fields.getJSONObject(i)
        }

        val webGrounded = byName["webGrounded"] ?: error("webGrounded missing from 4.json")
        assertEquals("INTEGER", webGrounded.getString("affinity"))
        assertTrue("webGrounded must be NOT NULL", webGrounded.getBoolean("notNull"))
        assertTrue("v4 keeps the edit bookkeeping", "updatedAt" in byName && "deleted" in byName)
    }

    @Test
    fun `the migration adds NOT NULL DEFAULT 0 columns and back-fills updatedAt`() {
        // Read the migration source directly: it is the only place the ALTER + UPDATE
        // SQL lives, and the migrate() body cannot run without an Android SQLite DB.
        val source = migrationSource()
        assertTrue("migration source must exist", source.exists())
        val text = source.readText().replace(Regex("\\s+"), " ")

        assertTrue(
            "updatedAt must be added NOT NULL DEFAULT 0",
            text.contains("ALTER TABLE food_entries ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"),
        )
        assertTrue(
            "deleted must be added NOT NULL DEFAULT 0",
            text.contains("ALTER TABLE food_entries ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0"),
        )
        assertTrue(
            "webGrounded must be added NOT NULL DEFAULT 0",
            text.contains("ALTER TABLE food_entries ADD COLUMN webGrounded INTEGER NOT NULL DEFAULT 0"),
        )
        assertTrue(
            "existing rows must be back-filled from loggedAt",
            text.contains("UPDATE food_entries SET updatedAt = loggedAt WHERE updatedAt = 0"),
        )
        assertTrue(
            "a botched migration must not brick the app",
            text.contains("fallbackToDestructiveMigration(dropAllTables = true)"),
        )
    }

    @Test
    fun `schema 1 predates sync identity`() {
        val fields = schema(1).getJSONArray("entities").getJSONObject(0).getJSONArray("fields")
        val columns = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        assertTrue("v1 predates the sync identity", "syncId" !in columns)
        assertTrue("v1 predates the pending-delivery flag", "pendingSync" !in columns)
    }

    @Test
    fun `the v1 to v2 migration back-fills a unique syncId before creating the index`() {
        val source = migrationSource()
        val text = source.readText().replace(Regex("\\s+"), " ")

        assertTrue(
            "syncId must be added NOT NULL with a temporary default",
            text.contains("ALTER TABLE food_entries ADD COLUMN syncId TEXT NOT NULL DEFAULT ''"),
        )
        assertTrue(
            "pendingSync must be added NOT NULL DEFAULT 0",
            text.contains("ALTER TABLE food_entries ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 0"),
        )
        assertTrue(
            "existing rows must be given a unique syncId derived from the primary key",
            text.contains("UPDATE food_entries SET syncId = 'legacy-' || id || '-' || loggedAt"),
        )
        assertTrue(
            "the unique index must be created after the back-fill",
            text.contains("CREATE UNIQUE INDEX IF NOT EXISTS index_food_entries_syncId"),
        )
        // The index creation must come after the UPDATE, or the second row would collide
        // on the shared '' default and the migration would abort.
        assertTrue(
            "index creation must follow the back-fill",
            text.indexOf("UPDATE food_entries SET syncId") < text.indexOf("CREATE UNIQUE INDEX"),
        )
    }

    @Test
    fun `every released schema version has a registered migration`() {
        val text = migrationSource().readText().replace(Regex("\\s+"), " ")
        assertTrue(
            "the v1 -> v2 migration must be registered on the builder",
            text.contains("addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)"),
        )
    }

    @Test
    fun `migration identity hash in the latest schema matches the committed master table row`() {
        val db = schema(4)
        val hash = db.getString("identityHash")
        val setup = db.getJSONArray("setupQueries").let { arr ->
            (0 until arr.length()).joinToString("\n") { arr.getString(it) }
        }
        assertTrue("room_master_table must record the identity hash", setup.contains(hash))
    }
}
