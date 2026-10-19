package work.kumarfamilynet.cinemarchive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.MediaEpisode
import work.kumarfamilynet.cinemarchive.core.model.MediaSeason

/** Ports the web app's apps/web/src/lib/episodeSchedule.test.ts planning cases. */
class EpisodeScheduleTest {
    private val titleId = "6f1c2a4e-3b5d-4c7e-9f10-2a3b4c5d6e7f"
    private val today = "2026-10-18"
    private val day = SCHEDULE_CHECK_INTERVAL_MS

    private fun stored(number: Int, vararg dates: String?, count: Int = dates.size, updatedAt: String? = "2026-01-01T00:00:00Z") =
        StoredSeason("s$number", number, count, updatedAt, dates.mapIndexed { index, date -> StoredEpisode("s$number-e${index + 1}", index + 1, "Episode ${index + 1}", date) })
    private fun schedule(vararg seasons: Pair<Int, Int>, status: String? = "Returning Series", next: Int? = null) =
        TvSchedule(status, next, seasons.map { (number, count) -> ScheduleSeason(number, count) })
    private fun remote(number: Int, vararg dates: String?) =
        MediaSeason(number, dates.size, 2026, dates.mapIndexed { index, date -> MediaEpisode(index + 1, "Episode ${index + 1}", date, 45) })

    @Test fun picksTrackedNonDroppedSeriesNotCheckedInTheLastDayOldestFirst() {
        val titles = listOf(
            ScheduleCandidate("a", 1, "TV", "WATCHING"), ScheduleCandidate("b", 2, "TV", "WATCHED"),
            ScheduleCandidate("c", 3, "TV", "DROPPED"), ScheduleCandidate("d", 4, "MOVIE", "WATCHLIST"),
            ScheduleCandidate("e", 5, "TV", "WATCHLIST"), ScheduleCandidate("f", 0, "TV", "WATCHING"),
        )
        val now = 10 * day
        val due = seriesDueForScheduleCheck(titles, mapOf("a" to now - day + 1, "b" to now - 2 * day, "e" to now - 3 * day), now)
        assertEquals(listOf("e", "b"), due.map { it.id })
        // Never checked sorts first.
        assertEquals(listOf("a"), seriesDueForScheduleCheck(titles, mapOf("b" to now - 2 * day, "e" to now - 3 * day), now, limit = 1).map { it.id })
    }

    @Test fun fetchesANewlyAnnouncedSeason() {
        assertEquals(listOf(2), seasonsToCheck(listOf(stored(1, "2025-01-01")), schedule(1 to 1, 2 to 8), today))
    }

    @Test fun fetchesAStoredSeasonTmdbNowCountsMoreEpisodesForEvenOnceTheShowEnded() {
        assertEquals(listOf(1), seasonsToCheck(listOf(stored(1, "2025-01-01")), schedule(1 to 2, status = "Ended"), today))
    }

    @Test fun rechecksUndatedOrUpcomingEpisodesOnlyWhileTheShowIsRunning() {
        val seasons = listOf(stored(1, "2025-01-01"), stored(2, "2026-10-18", null))
        assertEquals(listOf(2), seasonsToCheck(seasons, schedule(1 to 1, 2 to 2), today))
        assertEquals(emptyList<Int>(), seasonsToCheck(seasons, schedule(1 to 1, 2 to 2, status = "Canceled"), today))
        assertEquals(listOf(2, 3), seasonsToCheck(seasons, schedule(1 to 1, 2 to 2, next = 3), today))
    }

    @Test fun skipsFinishedSeasonsSpecialsTmdbAddsAndCoarseCountSeasons() {
        val seasons = listOf(stored(1, "2025-01-01"), StoredSeason("coarse", 2, 10, null, emptyList()))
        assertEquals(emptyList<Int>(), seasonsToCheck(seasons, schedule(0 to 3, 1 to 1, 2 to 12, next = 2), today))
    }

    @Test fun addsANewSeasonWithItsEpisodesUnderCatalogIds() {
        val additions = catalogAdditions(titleId, listOf(stored(1, "2025-01-01")), listOf(remote(2, "2026-11-01", null)), fillCoarse = false)
        val season = additions.single()
        assertEquals(catalogSeasonId(titleId, 2), season.newSeasonId); assertNull(season.storedSeasonId); assertNull(season.grownCount)
        assertEquals(listOf(catalogEpisodeId(titleId, 2, 1), catalogEpisodeId(titleId, 2, 2)), season.episodes.map { it.first })
        assertEquals(emptyList<EpisodeScheduleChange>(), episodeScheduleChanges(listOf(stored(1, "2025-01-01")), listOf(remote(2, "2026-11-01"))))
    }

    @Test fun movesAChangedAirDateAndAppendsANewlyListedEpisodeWithoutResendingStoredRows() {
        val seasons = listOf(stored(1, "2026-10-01", null))
        val tmdb = listOf(remote(1, "2026-10-01", "2026-10-25", "2026-11-01"))
        assertEquals(listOf(EpisodeScheduleChange("s1-e2", "2026-10-25", null)), episodeScheduleChanges(seasons, tmdb))
        val season = catalogAdditions(titleId, seasons, tmdb, fillCoarse = false).single()
        assertEquals("s1", season.storedSeasonId); assertNull(season.newSeasonId)
        assertEquals(listOf(3), season.episodes.map { it.second.episodeNumber })
        assertEquals(3, season.grownCount); assertEquals("2026-01-01T00:00:00Z", season.expectedUpdatedAt)
    }

    @Test fun isIdempotentASecondPassOverItsOwnResultChangesNothing() {
        val tmdb = listOf(remote(1, "2026-10-01", "2026-10-25", "2026-11-01"))
        val after = listOf(stored(1, "2026-10-01", "2026-10-25", "2026-11-01"))
        assertEquals(emptyList<EpisodeScheduleChange>(), episodeScheduleChanges(after, tmdb))
        assertEquals(emptyList<SeasonAddition>(), catalogAdditions(titleId, after, tmdb, fillCoarse = false))
    }

    @Test fun neverAddsRowsToACoarseCountSeasonOutsideAManualRefresh() {
        val coarse = listOf(StoredSeason("coarse", 1, 10, "2026-01-01T00:00:00Z", emptyList()))
        val tmdb = listOf(remote(1, "2025-01-01", "2025-01-08"))
        assertEquals(emptyList<SeasonAddition>(), catalogAdditions(titleId, coarse, tmdb, fillCoarse = false))
        val filled = catalogAdditions(titleId, coarse, tmdb, fillCoarse = true).single()
        // Two rows never shrink the stored count of ten: its watched progress may depend on it.
        assertEquals(2, filled.episodes.size); assertNull(filled.grownCount)
    }

    @Test fun keepsNaturalParentsForNewSpecialsAndSkipsEmptySeasons() {
        val additions = catalogAdditions(titleId, emptyList(), listOf(remote(0, null), remote(1)), fillCoarse = true)
        val specials = additions.single()
        assertEquals(0, specials.season.seasonNumber); assertNull(specials.newSeasonId); assertNull(specials.storedSeasonId)
        assertEquals(catalogEpisodeId(titleId, 0, 1), specials.episodes.single().first)
    }

    @Test fun ignoresBlankProviderValuesAndUnguardedLocalSeasonCounts() {
        val seasons = listOf(stored(1, "2026-10-01", updatedAt = null))
        val tmdb = listOf(MediaSeason(1, 2, 2026, listOf(MediaEpisode(1, "", "", null), MediaEpisode(2, "New", "2026-11-01", null))))
        assertEquals(emptyList<EpisodeScheduleChange>(), episodeScheduleChanges(seasons, tmdb))
        val season = catalogAdditions(titleId, seasons, tmdb, fillCoarse = false).single()
        assertNull(season.grownCount); assertNull(season.expectedUpdatedAt)
        assertTrue(season.episodes.single().first == catalogEpisodeId(titleId, 1, 2))
    }
}
