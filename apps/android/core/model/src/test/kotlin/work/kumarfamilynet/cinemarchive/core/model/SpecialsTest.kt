package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialsTest {
    private fun season(number: Int, vararg airDates: String?) = SeasonDetail(
        id = "s$number",
        seasonNumber = number,
        episodeCount = airDates.size,
        episodesWatched = 0,
        airYear = null,
        episodes = airDates.mapIndexed { i, airDate ->
            EpisodeDetail(
                id = "s$number-e${i + 1}",
                episodeNumber = i + 1,
                episodeName = null,
                airDate = airDate,
                runtime = null,
                watchCount = 0,
                latestRating = null,
            )
        },
    )

    @Test
    fun `season 0 is the specials season`() {
        assertTrue(season(0).isSpecials)
        assertFalse(season(1).isSpecials)
        assertEquals(listOf(1, 2), listOf(season(0), season(1), season(2)).mainSeasons().map { it.seasonNumber })
    }

    @Test
    fun `specials sort after the main seasons`() {
        val ordered = listOf(season(2), season(0), season(1)).orderedForDisplay()
        assertEquals(listOf(1, 2, 0), ordered.map { it.seasonNumber })
    }

    @Test
    fun `labels distinguish specials`() {
        assertEquals("Specials", seasonLabel(0))
        assertEquals("Season 3", seasonLabel(3))
        assertEquals("SP", seasonShortLabel(0))
        assertEquals("S3", seasonShortLabel(3))
    }

    @Test
    fun `an upcoming special is not the next scheduled episode`() {
        val seasons = listOf(season(0, "2099-01-01"), season(1, "2026-01-01"))
        assertNull(seasons.nextScheduledEpisode(LocalDate.of(2026, 9, 25)))
    }
}
