package id.kaloriku.shared.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import id.kaloriku.shared.BuildConfig

@Database(entities = [FoodEntryEntity::class], version = 4, exportSchema = true)
abstract class KaloriKuDatabase : RoomDatabase() {
    abstract fun foodEntryDao(): FoodEntryDao

    companion object {
        @Volatile
        private var instance: KaloriKuDatabase? = null

        /**
         * Adds the sync identity and the pending-delivery flag. Rows written before the
         * phone<->watch sync existed have no stable identity yet, so each one is given a
         * unique value derived from its primary key (unique by construction). They are
         * treated as already delivered (`pendingSync = 0`), because there was no peer to
         * deliver them to.
         *
         * The unique index must be created *after* the back-fill: adding the column with
         * a shared default would otherwise violate it on the second row.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food_entries ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE food_entries ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE food_entries SET syncId = 'legacy-' || id || '-' || loggedAt")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_food_entries_syncId " +
                        "ON food_entries (syncId)",
                )
            }
        }

        /**
         * Adds the edit/delete bookkeeping columns. Existing rows are treated as the
         * first revision of themselves and as live, which is the correct reading of
         * data written before editing existed.
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE food_entries ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE food_entries ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("UPDATE food_entries SET updatedAt = loggedAt WHERE updatedAt = 0")
            }
        }

        /**
         * Adds the web-grounding flag. Rows written before web grounding existed were
         * never grounded, so 0 is the correct back-fill.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE food_entries ADD COLUMN webGrounded INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun get(
            context: Context,
            // Only debug builds may fall back to dropping tables. In a release
            // build a missing migration must fail loudly instead of silently
            // destroying the user's log, which would otherwise look exactly like
            // the data loss caused by an uninstall.
            allowDestructiveFallback: Boolean = BuildConfig.DEBUG,
        ): KaloriKuDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KaloriKuDatabase::class.java,
                    "kaloriku.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .apply {
                        // Last-resort safety net only: every released schema version
                        // has a real migration above, so this must never fire.
                        if (allowDestructiveFallback) {
                            fallbackToDestructiveMigration(dropAllTables = true)
                        }
                    }
                    .build()
                    .also { instance = it }
            }
    }
}
