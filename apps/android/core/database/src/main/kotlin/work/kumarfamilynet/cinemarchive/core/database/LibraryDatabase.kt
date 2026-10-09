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
        ViewingCompletionAliasEntity::class,
        OutboxEntity::class,
        TitleCastEntity::class,
        TitleCrewEntity::class,
        SeasonCastEntity::class,
        EpisodeCrewEntity::class,
        CinemaOutingEntity::class,
        VenueNoteEntity::class,
        ListEntity::class,
        ListItemEntity::class,
        TheaterInterestEntity::class,
        LegacyRestoreReceiptEntity::class,
        TicketOriginalEntity::class,
        TicketAssociationEntity::class,
        TicketIntentEntity::class,
    ],
    version = 20,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun titleDao(): TitleDao
    abstract fun titleReconcileDao(): TitleReconcileDao
    abstract fun seasonDao(): SeasonDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun episodeWatchEventDao(): EpisodeWatchEventDao
    abstract fun episodeRatingDao(): EpisodeRatingDao
    abstract fun episodeReviewDao(): EpisodeReviewDao
    abstract fun viewingDao(): ViewingDao
    abstract fun viewingCompletionAliasDao(): ViewingCompletionAliasDao
    abstract fun completionQueueDao(): CompletionQueueDao
    abstract fun outboxDao(): OutboxDao
    abstract fun titleCastDao(): TitleCastDao
    abstract fun titleCrewDao(): TitleCrewDao
    abstract fun personCreditsDao(): PersonCreditsDao
    abstract fun cinemaOutingDao(): CinemaOutingDao
    abstract fun venueNoteDao(): VenueNoteDao
    abstract fun listDao(): ListDao
    abstract fun listItemDao(): ListItemDao
    abstract fun theaterInterestDao(): TheaterInterestDao
    abstract fun legacyRestoreReceiptDao(): LegacyRestoreReceiptDao
    abstract fun ticketAttachmentDao(): TicketAttachmentDao

    companion object {
        const val LEGACY_DATABASE_NAME = "cinemarchive.db"

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

        /** Adds `legacy_restore_receipt` — see [LegacyRestoreReceiptEntity]. New table, same
         *  additive-migration rationale as MIGRATION_4_5. */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `legacy_restore_receipt` (`key` TEXT NOT NULL, `archiveId` TEXT NOT NULL, `kind` TEXT NOT NULL, `restoredAt` TEXT NOT NULL, PRIMARY KEY(`key`))",
                )
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episode_watch_events ADD COLUMN notes TEXT")
            }
        }

        /** Additive metadata only: retain every local watch, queued write, and recovery receipt. */
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE titles ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE titles ADD COLUMN studios TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE titles ADD COLUMN collectionId INTEGER")
                db.execSQL("ALTER TABLE titles ADD COLUMN collectionName TEXT")
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `season_cast` (`id` TEXT NOT NULL, `titleId` TEXT NOT NULL, `seasonId` TEXT NOT NULL, `tmdbPersonId` INTEGER NOT NULL, `name` TEXT NOT NULL, `characterName` TEXT, `castOrder` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`titleId`) REFERENCES `titles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`seasonId`) REFERENCES `seasons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_season_cast_titleId` ON `season_cast` (`titleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_season_cast_seasonId` ON `season_cast` (`seasonId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `episode_crew` (`id` TEXT NOT NULL, `titleId` TEXT NOT NULL, `episodeId` TEXT NOT NULL, `tmdbPersonId` INTEGER NOT NULL, `name` TEXT NOT NULL, `job` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`titleId`) REFERENCES `titles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`episodeId`) REFERENCES `episodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_crew_titleId` ON `episode_crew` (`titleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_crew_episodeId` ON `episode_crew` (`episodeId`)")
            }
        }

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE viewings ADD COLUMN updatedAt TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `viewing_completion_aliases` (`provisionalViewingId` TEXT NOT NULL, `canonicalViewingId` TEXT NOT NULL, `titleId` TEXT NOT NULL, `outingId` TEXT NOT NULL, `completionOperationId` TEXT NOT NULL, `canonicalViewingVersion` TEXT, PRIMARY KEY(`provisionalViewingId`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_viewing_completion_aliases_completionOperationId` ON `viewing_completion_aliases` (`completionOperationId`)")
            }
        }

        /** Keep legacy paths, queued writes and local completion identity proofs untouched. */
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `ticket_originals` (`projectId` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `attachmentId` TEXT NOT NULL, `outingId` TEXT NOT NULL, `descriptorJson` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`projectId`, `ownerId`, `attachmentId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `ticket_associations` (`projectId` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `outingId` TEXT NOT NULL, `attachmentId` TEXT, PRIMARY KEY(`projectId`, `ownerId`, `outingId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `ticket_intents` (`operationId` TEXT NOT NULL, `projectId` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `outingId` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `acknowledgedAt` INTEGER, PRIMARY KEY(`operationId`))")
            }
        }

        /** Additive catalog/owner fields: no histories, pending commands or identity receipts change. */
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("contentRating", "imdbId", "rtUrl", "customWatchUrl", "physicalMediaJson", "bechdelOutcome", "bechdelScore")
                    .forEach { db.execSQL("ALTER TABLE titles ADD COLUMN `$it` TEXT") }
                listOf("rtScore", "metacriticScore", "inHomeCollection", "awardsCount")
                    .forEach { db.execSQL("ALTER TABLE titles ADD COLUMN `$it` INTEGER") }
            }
        }

        /** Preserve backup graph fields without rewriting existing history or pending intents. */
        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE viewings ADD COLUMN companionsJson TEXT")
                db.execSQL("ALTER TABLE cinema_outings ADD COLUMN companionsJson TEXT")
                db.execSQL("ALTER TABLE venue_notes ADD COLUMN serverId TEXT")
                db.execSQL("ALTER TABLE venue_notes ADD COLUMN serverUpdatedAt TEXT")
                db.execSQL("ALTER TABLE venue_notes ADD COLUMN serverCreatedAt TEXT")
                db.execSQL("ALTER TABLE theater_interest ADD COLUMN serverUpdatedAt TEXT")
            }
        }

        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("title_cast", "season_cast").forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN profileUrl TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN episodeCount INTEGER")
                }
                db.execSQL("ALTER TABLE title_crew ADD COLUMN profileUrl TEXT")
                listOf("episode_watch_events", "episode_reviews").forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN colorMode TEXT")
                }
            }
        }

        /** [name] is the SQLite file name. Per-account runtimes pass an owner-derived name (see
         *  AccountRuntime) so two accounts never share a file; the legacy global
         *  `cinemarchive.db` ([LEGACY_DATABASE_NAME]) is never opened by an active runtime. */
        fun create(context: Context, name: String): LibraryDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            name,
        )
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20)
            // Safety net for any future version bump that ships without its own explicit
            // Migration — see MIGRATION_4_5's kdoc for why bumps should add one instead of
            // relying on this now that real user data lives locally.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

        /**
         * Opens the database [name] (in the app's databases directory — a disposable working copy of the legacy
         * global `cinemarchive.db`, see the data module's `LegacyArchive`) with the same
         * migration list as [create] but WITHOUT destructive fallback: opening a recovery copy
         * must never wipe the very data being recovered, so an unmigratable or newer file
         * throws on first use instead. The caller owns the file and must close the instance.
         *
         * The path is routed through a [android.content.ContextWrapper] rather than passed to
         * Room as a bare name, because `Context.getDatabasePath` only honors absolute paths
         * that start with the platform separator (which fails for Windows host paths under
         * Robolectric).
         */
        fun createForRecovery(context: Context, name: String): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20)
                .build()
    }
}
