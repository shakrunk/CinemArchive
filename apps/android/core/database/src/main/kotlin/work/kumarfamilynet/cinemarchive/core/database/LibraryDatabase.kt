package work.kumarfamilynet.cinemarchive.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TitleEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        EpisodeWatchEventEntity::class,
        EpisodeRatingEntity::class,
        EpisodeReviewEntity::class,
        ViewingEntity::class,
        OutboxEntity::class,
        TitleCastEntity::class,
        TitleCrewEntity::class,
        CinemaOutingEntity::class,
        VenueNoteEntity::class,
        ListEntity::class,
        ListItemEntity::class,
        TheaterInterestEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun titleDao(): TitleDao
    abstract fun seasonDao(): SeasonDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun episodeWatchEventDao(): EpisodeWatchEventDao
    abstract fun episodeRatingDao(): EpisodeRatingDao
    abstract fun episodeReviewDao(): EpisodeReviewDao
    abstract fun viewingDao(): ViewingDao
    abstract fun outboxDao(): OutboxDao
    abstract fun titleCastDao(): TitleCastDao
    abstract fun titleCrewDao(): TitleCrewDao
    abstract fun cinemaOutingDao(): CinemaOutingDao
    abstract fun venueNoteDao(): VenueNoteDao
    abstract fun listDao(): ListDao
    abstract fun listItemDao(): ListItemDao
    abstract fun theaterInterestDao(): TheaterInterestDao

    companion object {
        /** Adds titles.releaseDate (see Entities.kt's TitleEntity kdoc). A real ALTER TABLE,
         *  not destructive fallback, because real synced user data now lives in this table —
         *  wiping it on every schema bump forces a full re-sync from Supabase before the
         *  Library/Up Next/Ledger tabs show anything again, which briefly looked like data
         *  loss when this column was added (docs/superpowers/plans — see git history around
         *  the "Up Next" UX pass this shipped with). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE titles ADD COLUMN releaseDate TEXT")
            }
        }

        /** Splits `cinema_outings.seat` into the auditorium/row/seats trio (issue #221).
         *  The old column stays and keeps its value — the clients fall back to it, and
         *  parsing free text like "Row F, seats 12 and 13" into columns would be guesswork
         *  on real data (see supabase/migrations/20260803000000_outing_seat_details.sql).
         *  `seats` is a `List<String>` through [Converters], i.e. a delimited TEXT column,
         *  so it defaults to the empty string rather than SQL NULL. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cinema_outings ADD COLUMN auditorium TEXT")
                db.execSQL("ALTER TABLE cinema_outings ADD COLUMN seatRow TEXT")
                db.execSQL("ALTER TABLE cinema_outings ADD COLUMN seats TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Adds episodes.synopsis/stillUrl (see Entities.kt's EpisodeEntity kdoc), backing the
         *  Title detail screen's season selector/episode cards. Same real-data rationale as
         *  MIGRATION_4_5/MIGRATION_5_6 — additive ALTER TABLEs, not a destructive fallback. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN synopsis TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN stillUrl TEXT")
            }
        }

        /** Adds the `venue_notes` table (issue #214) — per-venue parking/transit notes, keyed
         *  on the venue string itself (see [VenueNoteEntity]'s kdoc). New table, so no ALTER
         *  needed; same additive-migration rationale as MIGRATION_4_5. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `venue_notes` (`venue` TEXT NOT NULL, `notes` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`venue`))",
                )
            }
        }

        /** Adds the captured-ticket trio to `cinema_outings` (issue #219) — image path, decoded
         *  barcode payload, and decoded barcode format, stored alongside each other per
         *  docs/superpowers/plans/2026-08-19-android-ticket-capture.md §4. Additive ALTER
         *  TABLEs, same real-data rationale as MIGRATION_4_5. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cinema_outings ADD COLUMN ticketImagePath TEXT")
                db.execSQL("ALTER TABLE cinema_outings ADD COLUMN ticketBarcodePayload TEXT")
                db.execSQL("ALTER TABLE cinema_outings ADD COLUMN ticketBarcodeFormat TEXT")
            }
        }

        /** Adds the Lists feature's two tables (supabase/migrations/20260821000000_lists.sql) —
         *  a real CREATE TABLE, not destructive fallback, same rationale as every prior
         *  migration in this file. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `list_items` (`id` TEXT NOT NULL, `listId` TEXT NOT NULL, `titleId` TEXT NOT NULL, `position` INTEGER, `addedAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`listId`) REFERENCES `lists`(`id`) ON DELETE CASCADE, FOREIGN KEY(`titleId`) REFERENCES `titles`(`id`) ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_list_items_listId` ON `list_items` (`listId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_list_items_titleId` ON `list_items` (`titleId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_list_items_listId_titleId` ON `list_items` (`listId`, `titleId`)")
            }
        }

        /** Adds the `theater_interest` table (issue #205) — see [TheaterInterestEntity]'s
         *  kdoc. New table, same additive-migration rationale as MIGRATION_4_5. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `theater_interest` (`titleId` TEXT NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`titleId`))",
                )
            }
        }

        fun create(context: Context): LibraryDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            "cinemarchive.db",
        )
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            // Safety net for any future version bump that ships without its own explicit
            // Migration — see MIGRATION_4_5's kdoc for why bumps should add one instead of
            // relying on this now that real user data lives locally.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
