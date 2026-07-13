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
        ViewingEntity::class,
        OutboxEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun titleDao(): TitleDao
    abstract fun seasonDao(): SeasonDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun episodeWatchEventDao(): EpisodeWatchEventDao
    abstract fun episodeRatingDao(): EpisodeRatingDao
    abstract fun viewingDao(): ViewingDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        fun create(context: Context): LibraryDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            "cinemarchive.db",
        ).build()
    }
}
