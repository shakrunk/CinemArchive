package work.kumarfamilynet.cinemarchive.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * Local mirror of the `titles` table (see schema.sql / docs/android-contracts/title-detail.md).
 * A read-only subset for now — cast/crew/physical-media/badge-score columns are deferred
 * until the network sync layer (docs/android-sync-contract.md) actually populates them.
 */
@Entity(tableName = "titles")
data class TitleEntity(
    @PrimaryKey val id: String,
    val tmdbId: Int,
    val type: String, // MediaType.name
    val title: String,
    val year: Int?,
    val director: String?,
    val genres: List<String>,
    val posterUrl: String?,
    val backdropUrl: String?,
    val synopsis: String?,
    val runtime: Int?,
    val network: String?,
    val status: String, // LibraryStatus.name
    val rating: Double?,
    val notes: String?,
    val addedAt: String,
    val updatedAt: String,
)

@Entity(
    tableName = "seasons",
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["id"],
            childColumns = ["titleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("titleId")],
)
data class SeasonEntity(
    @PrimaryKey val id: String,
    val titleId: String,
    val seasonNumber: Int,
    val episodeCount: Int,
    val episodesWatched: Int,
    val airYear: Int?,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["id"],
            childColumns = ["titleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["id"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("titleId"), Index("seasonId")],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val titleId: String,
    val seasonId: String,
    val episodeNumber: Int,
    val episodeName: String?,
    val airDate: String?,
    val runtime: Int?,
)

/** Independent watch log — see docs/android-contracts/episode-tracking.md §1. */
@Entity(
    tableName = "episode_watch_events",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("episodeId")],
)
data class EpisodeWatchEventEntity(
    @PrimaryKey val id: String,
    val episodeId: String,
    val watchedAt: String?, // null = watched before joining the platform
)

/** Independent rating log — deliberately not 1:1 with watch events. */
@Entity(
    tableName = "episode_ratings",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("episodeId")],
)
data class EpisodeRatingEntity(
    @PrimaryKey val id: String,
    val episodeId: String,
    val rating: Double,
    val ratedAt: String,
)

/** Re-watch timeline entry — see docs/android-contracts/title-detail.md §1 (Viewings). */
@Entity(
    tableName = "viewings",
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["id"],
            childColumns = ["titleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("titleId")],
)
data class ViewingEntity(
    @PrimaryKey val id: String,
    val titleId: String,
    val date: String?,
    val rating: Double?,
    val notes: String?,
    val venue: String?,
)

class Converters {
    @TypeConverter
    fun fromGenres(value: String?): List<String> =
        value?.split(DELIMITER)?.filter { it.isNotBlank() } ?: emptyList()

    @TypeConverter
    fun toGenres(genres: List<String>): String = genres.joinToString(DELIMITER)

    private companion object {
        const val DELIMITER = "|"
    }
}
