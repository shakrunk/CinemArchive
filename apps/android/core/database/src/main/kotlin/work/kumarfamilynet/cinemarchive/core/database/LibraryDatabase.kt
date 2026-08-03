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
    ],
    version = 7,
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

        fun create(context: Context): LibraryDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            "cinemarchive.db",
        )
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            // Safety net for any future version bump that ships without its own explicit
            // Migration — see MIGRATION_4_5's kdoc for why bumps should add one instead of
            // relying on this now that real user data lives locally.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
