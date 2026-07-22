package work.kumarfamilynet.cinemarchive.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    version = 4,
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
        fun create(context: Context): LibraryDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            "cinemarchive.db",
        )
            // Pre-distribution: no real user data exists yet, only DevFixtureSeed's
            // re-seed-on-empty dev fixtures, so a hand-authored migration would preserve
            // data nobody has. Revisit once the app ships real user data.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
