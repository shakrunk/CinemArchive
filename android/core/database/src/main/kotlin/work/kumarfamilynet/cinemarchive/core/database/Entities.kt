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
    // Mirrors schema.sql's titles.imdb_rating/original_language — the two remaining
    // fields the Ledger Second Opinions/In Translation widgets need (see
    // docs/android-contracts/ledger.md §2). Nullable since most fixture/real titles won't
    // have every badge score populated (schema.sql's own comment on rt_score/metacritic_score).
    val imdbRating: Double? = null,
    val originalLanguage: String? = null,
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

/** Independent review log — deliberately not 1:1 with watch events or ratings. */
@Entity(
    tableName = "episode_reviews",
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
data class EpisodeReviewEntity(
    @PrimaryKey val id: String,
    val episodeId: String,
    val reviewText: String,
    val reviewedAt: String,
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
    // Mirrors the `companions`/`outing_id` columns schema.sql adds to `viewings` for cinema
    // outings — companion display names only (schema.sql's jsonb shape also carries an
    // optional friendUserId, not needed locally since Android has no friend graph yet).
    // Feeds the Ledger "At the Movies" widget (docs/android-contracts/ledger.md §2/§3).
    val companions: List<String> = emptyList(),
    val outingId: String? = null,
)

/**
 * Local mirror of `title_cast` (schema.sql) — top-billed cast per title, used only by the
 * Ledger Ensemble widget's "leading cast" tally (cast order < 5). Not surfaced on the Title
 * detail screen yet (title-detail.md's TitleDetail model deliberately deferred cast/crew —
 * see its kdoc — this table exists for Ledger only, ahead of that).
 */
@Entity(
    tableName = "title_cast",
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
data class TitleCastEntity(
    @PrimaryKey val id: String,
    val titleId: String,
    val tmdbPersonId: Int,
    val name: String,
    val characterName: String?,
    val castOrder: Int,
)

/**
 * Local mirror of `title_crew` (schema.sql) — used by the Ledger Auteurs widget, which
 * tallies by crew rows where `job == "Director"` (ledger.md §2).
 */
@Entity(
    tableName = "title_crew",
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
data class TitleCrewEntity(
    @PrimaryKey val id: String,
    val titleId: String,
    val tmdbPersonId: Int,
    val name: String,
    val job: String,
    val department: String?,
)

/**
 * Local mirror of the two owner-private `cinema_outings` columns the Ledger "At the Movies"
 * widget reads (`format`, `ticketPrice`; see ledger.md §3) — everything else that widget
 * needs (trip counts, venues, companions, year trend) lives on [ViewingEntity]. Android has
 * no friend/shared-viewer mode yet (unlike the web app), so the "degrades for non-owner
 * viewers" behavior ledger.md §3 documents isn't reachable here today — this mirrors the
 * owner's own view only.
 */
@Entity(
    tableName = "cinema_outings",
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
data class CinemaOutingEntity(
    @PrimaryKey val id: String,
    val titleId: String,
    val format: String?,
    val ticketPrice: Double?,
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
