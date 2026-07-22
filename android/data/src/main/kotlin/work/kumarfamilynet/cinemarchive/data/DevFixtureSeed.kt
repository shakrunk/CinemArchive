package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventEntity
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCastEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewEntity
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
                    imdbRating = 4.4,
                    originalLanguage = "en",
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
                    // Matches docs/android-contracts/fixtures/title-detail.json verbatim.
                    imdbRating = 4.65,
                    originalLanguage = "en",
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
                    imdbRating = 4.35,
                    originalLanguage = "en",
                ),
            )
        )

        val inceptionId = "3f2b6b1a-8b3e-4a0c-9c1a-1a2b3c4d5e6f"
        val breakingBadId = "7c1d2e3f-4a5b-6c7d-8e9f-0a1b2c3d4e5f"
        val fightClubId = "9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d"

        // Feeds the Ledger Ensemble widget (cast order < 5 counts as "leading",
        // docs/android-contracts/ledger.md §2). Breaking Bad's rows match
        // docs/android-contracts/fixtures/title-detail.json verbatim.
        database.titleCastDao().upsertAll(
            listOf(
                TitleCastEntity(
                    id = "f1a2b3c4-0001-4a0c-9c1a-1a2b3c4d5e01",
                    titleId = inceptionId,
                    tmdbPersonId = 6193,
                    name = "Leonardo DiCaprio",
                    characterName = "Cobb",
                    castOrder = 0,
                ),
                TitleCastEntity(
                    id = "f1a2b3c4-0002-4a0c-9c1a-1a2b3c4d5e02",
                    titleId = inceptionId,
                    tmdbPersonId = 24045,
                    name = "Joseph Gordon-Levitt",
                    characterName = "Arthur",
                    castOrder = 1,
                ),
                TitleCastEntity(
                    id = "f1a2b3c4-0003-4a0c-9c1a-1a2b3c4d5e03",
                    titleId = inceptionId,
                    tmdbPersonId = 2524,
                    name = "Tom Hardy",
                    characterName = "Eames",
                    castOrder = 2,
                ),
                TitleCastEntity(
                    id = "f1a2b3c4-0004-4a0c-9c1a-1a2b3c4d5e04",
                    titleId = breakingBadId,
                    tmdbPersonId = 17419,
                    name = "Bryan Cranston",
                    characterName = "Walter White",
                    castOrder = 0,
                ),
                TitleCastEntity(
                    id = "f1a2b3c4-0005-4a0c-9c1a-1a2b3c4d5e05",
                    titleId = breakingBadId,
                    tmdbPersonId = 84497,
                    name = "Aaron Paul",
                    characterName = "Jesse Pinkman",
                    castOrder = 1,
                ),
                TitleCastEntity(
                    id = "f1a2b3c4-0006-4a0c-9c1a-1a2b3c4d5e06",
                    titleId = fightClubId,
                    tmdbPersonId = 287,
                    name = "Brad Pitt",
                    characterName = "Tyler Durden",
                    castOrder = 0,
                ),
                TitleCastEntity(
                    id = "f1a2b3c4-0007-4a0c-9c1a-1a2b3c4d5e07",
                    titleId = fightClubId,
                    tmdbPersonId = 819,
                    name = "Edward Norton",
                    characterName = "The Narrator",
                    castOrder = 1,
                ),
            )
        )

        // Feeds the Ledger Auteurs widget (job == "Director" only, ledger.md §2). Breaking
        // Bad intentionally has no "Director" crew row here, matching
        // docs/android-contracts/fixtures/title-detail.json, which only lists Vince
        // Gilligan as "Creator" — Auteurs should not count it.
        database.titleCrewDao().upsertAll(
            listOf(
                TitleCrewEntity(
                    id = "e1a2b3c4-0001-4a0c-9c1a-1a2b3c4d5e01",
                    titleId = inceptionId,
                    tmdbPersonId = 525,
                    name = "Christopher Nolan",
                    job = "Director",
                    department = "Directing",
                ),
                TitleCrewEntity(
                    id = "e1a2b3c4-0002-4a0c-9c1a-1a2b3c4d5e02",
                    titleId = breakingBadId,
                    tmdbPersonId = 66633,
                    name = "Vince Gilligan",
                    job = "Creator",
                    department = "Writing",
                ),
                TitleCrewEntity(
                    id = "e1a2b3c4-0003-4a0c-9c1a-1a2b3c4d5e03",
                    titleId = fightClubId,
                    tmdbPersonId = 7467,
                    name = "David Fincher",
                    job = "Director",
                    department = "Directing",
                ),
            )
        )

        val seasonId = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
        database.seasonDao().upsertAll(
            listOf(
                SeasonEntity(
                    id = seasonId,
                    titleId = breakingBadId,
                    seasonNumber = 1,
                    episodeCount = 7,
                    episodesWatched = 1,
                    airYear = 2008,
                ),
            )
        )

        val episodeId = "b2c3d4e5-f6a7-8b9c-0d1e-2f3a4b5c6d7e"
        val secondEpisodeId = "a9b8c7d6-e5f4-3a2b-1c0d-9e8f7a6b5c4d"
        database.episodeDao().upsertAll(
            listOf(
                EpisodeEntity(
                    id = episodeId,
                    titleId = breakingBadId,
                    seasonId = seasonId,
                    episodeNumber = 1,
                    episodeName = "Pilot",
                    airDate = "2008-01-20",
                    runtime = 58,
                ),
                EpisodeEntity(
                    id = secondEpisodeId,
                    titleId = breakingBadId,
                    seasonId = seasonId,
                    episodeNumber = 2,
                    episodeName = "Cat's in the Bag...",
                    airDate = "2008-01-27",
                    runtime = 48,
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

        val cinemaOutingId = "d1e2f3a4-b5c6-7d8e-9f0a-1b2c3d4e5f60"
        val scheduledOutingId = "a4b5c6d7-e8f9-0a1b-2c3d-4e5f60718293"
        // Anchored to "now" (not a fixed date) so a freshly-installed app always shows an
        // active marquee entry — plain string literals elsewhere in this file are fine since
        // they're historical, but a scheduled outing needs to still be in the future whenever
        // this seed actually runs.
        val scheduledShowtime = Instant.now().plusSeconds(3 * 24 * 60 * 60) // 3 days out
        val scheduledEndsAt = scheduledShowtime.plusSeconds((20 + 139) * 60L) // +20min previews, Fight Club's 139min runtime
        val nowIso = Instant.now().toString()
        database.cinemaOutingDao().upsertAll(
            listOf(
                // Completed trip — owner-private fields for the Ledger "At the Movies"
                // widget's degraded (format/spend) half; see CinemaOutingEntity kdoc.
                CinemaOutingEntity(
                    id = cinemaOutingId,
                    titleId = inceptionId,
                    showtime = "2026-01-15T19:00:00Z",
                    runtimeMinutes = 148,
                    endsAt = "2026-01-15T22:08:00Z",
                    venue = "AMC Lincoln Square",
                    companions = listOf("Sam", "Jordan"),
                    format = "IMAX",
                    ticketPrice = 24.50,
                    status = "COMPLETED",
                    previousStatus = "WATCHLIST",
                    completedViewingId = "c1d2e3f4-a5b6-7c8d-9e0f-1a2b3c4d5e60",
                    createdAt = "2026-01-14T10:00:00Z",
                    updatedAt = "2026-01-15T22:08:00Z",
                ),
                // Scheduled trip on the watchlisted Fight Club — populates "On the Marquee"
                // on first launch and gives phase testing something already in place, on
                // top of whatever's scheduled by hand through the UI.
                CinemaOutingEntity(
                    id = scheduledOutingId,
                    titleId = fightClubId,
                    showtime = scheduledShowtime.toString(),
                    previewsMinutes = 20,
                    runtimeMinutes = 139,
                    endsAt = scheduledEndsAt.toString(),
                    venue = "Alamo Drafthouse",
                    companions = listOf("Alex"),
                    format = "SEVENTY_MM",
                    ticketPrice = 18.00,
                    seat = "H12",
                    status = "SCHEDULED",
                    createdAt = nowIso,
                    updatedAt = nowIso,
                ),
            )
        )

        database.viewingDao().upsertAll(
            listOf(
                ViewingEntity(
                    id = "e5f6a7b8-c9d0-1e2f-3a4b-5c6d7e8f9a0b",
                    titleId = breakingBadId,
                    date = "2026-03-02",
                    rating = null,
                    notes = "Started the rewatch.",
                    venue = null,
                ),
                // Cinema trip, linked to cinemaOutingId — the "At the Movies" widget's
                // trip-count/venue/companion data (available to any viewer) plus,
                // owner-only, the outing's format/spend.
                ViewingEntity(
                    id = "c1d2e3f4-a5b6-7c8d-9e0f-1a2b3c4d5e60",
                    titleId = inceptionId,
                    date = "2026-01-15",
                    rating = 4.5,
                    notes = "Opening day.",
                    venue = "AMC Lincoln Square",
                    companions = listOf("Sam", "Jordan"),
                    outingId = cinemaOutingId,
                ),
            )
        )
    }
}
