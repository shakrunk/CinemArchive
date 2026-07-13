package work.kumarfamilynet.cinemarchive.data

import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventEntity
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity

/**
 * Populates Room with the same fixture titles documented in the JSON fixtures under
 * docs/android-contracts/fixtures, so the read-only Library/Title-detail spine has
 * something to render before the network sync layer (docs/android-sync-contract.md) exists.
 *
 * Temporary: delete this once `sync_library_changes` is implemented and wired up — it exists
 * only to make Phase 1's UI demonstrable against real-shaped data in the meantime.
 */
object DevFixtureSeed {
    suspend fun seedIfEmpty(database: LibraryDatabase) {
        if (database.titleDao().count() > 0) return

        database.titleDao().upsertAll(
            listOf(
                TitleEntity(
                    id = "3f2b6b1a-8b3e-4a0c-9c1a-1a2b3c4d5e6f",
                    tmdbId = 27205,
                    type = "MOVIE",
                    title = "Inception",
                    year = 2010,
                    director = "Christopher Nolan",
                    genres = listOf("Action", "Science Fiction", "Thriller"),
                    posterUrl = "https://image.tmdb.org/t/p/w500/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg",
                    backdropUrl = null,
                    synopsis = "A thief who steals corporate secrets through dream-sharing technology.",
                    runtime = 148,
                    network = null,
                    status = "WATCHED",
                    rating = 4.5,
                    notes = null,
                    addedAt = "2026-01-15",
                    updatedAt = "2026-01-15T09:12:00.000Z",
                ),
                TitleEntity(
                    id = "7c1d2e3f-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
                    tmdbId = 1396,
                    type = "TV",
                    title = "Breaking Bad",
                    year = 2008,
                    director = null,
                    genres = listOf("Drama", "Crime"),
                    posterUrl = "https://image.tmdb.org/t/p/w500/ggFHVNu6YYI5L9pCfOacjizRGt.jpg",
                    backdropUrl = "https://image.tmdb.org/t/p/original/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg",
                    synopsis = "A high school chemistry teacher turned methamphetamine manufacturer.",
                    runtime = null,
                    network = "AMC",
                    status = "WATCHING",
                    rating = null,
                    notes = null,
                    addedAt = "2026-03-02",
                    updatedAt = "2026-06-20T14:30:00.000Z",
                ),
                TitleEntity(
                    id = "9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d",
                    tmdbId = 550,
                    type = "MOVIE",
                    title = "Fight Club",
                    year = 1999,
                    director = "David Fincher",
                    genres = listOf("Drama"),
                    posterUrl = null,
                    backdropUrl = null,
                    synopsis = null,
                    runtime = 139,
                    network = null,
                    status = "WATCHLIST",
                    rating = null,
                    notes = null,
                    addedAt = "2026-07-01",
                    updatedAt = "2026-07-01T20:05:00.000Z",
                ),
            )
        )

        val seasonId = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
        database.seasonDao().upsertAll(
            listOf(
                SeasonEntity(
                    id = seasonId,
                    titleId = "7c1d2e3f-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
                    seasonNumber = 1,
                    episodeCount = 7,
                    episodesWatched = 1,
                    airYear = 2008,
                ),
            )
        )

        val episodeId = "b2c3d4e5-f6a7-8b9c-0d1e-2f3a4b5c6d7e"
        database.episodeDao().upsertAll(
            listOf(
                EpisodeEntity(
                    id = episodeId,
                    titleId = "7c1d2e3f-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
                    seasonId = seasonId,
                    episodeNumber = 1,
                    episodeName = "Pilot",
                    airDate = "2008-01-20",
                    runtime = 58,
                ),
            )
        )

        database.episodeWatchEventDao().upsertAll(
            listOf(
                EpisodeWatchEventEntity(
                    id = "c3d4e5f6-a7b8-9c0d-1e2f-3a4b5c6d7e8f",
                    episodeId = episodeId,
                    watchedAt = "2026-03-02",
                ),
                EpisodeWatchEventEntity(
                    id = "f6a7b8c9-d0e1-2f3a-4b5c-6d7e8f9a0b1c",
                    episodeId = episodeId,
                    watchedAt = "2026-06-10",
                ),
            )
        )

        database.episodeRatingDao().upsertAll(
            listOf(
                EpisodeRatingEntity(
                    id = "d4e5f6a7-b8c9-0d1e-2f3a-4b5c6d7e8f9a",
                    episodeId = episodeId,
                    rating = 4.5,
                    ratedAt = "2026-03-02T21:00:00.000Z",
                ),
            )
        )

        database.viewingDao().upsertAll(
            listOf(
                ViewingEntity(
                    id = "e5f6a7b8-c9d0-1e2f-3a4b-5c6d7e8f9a0b",
                    titleId = "7c1d2e3f-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
                    date = "2026-03-02",
                    rating = null,
                    notes = "Started the rewatch.",
                    venue = null,
                ),
            )
        )
    }
}
