package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeScheduleTest {
    private val today = LocalDate.of(2026, 9, 25)

    private fun episode(number: Int, airDate: String? = null) = EpisodeDetail(
        id = "ep-$number-$airDate",
        episodeNumber = number,
        episodeName = null,
        airDate = airDate,
        runtime = null,
        watchCount = 0,
        latestRating = null,
    )

    private fun season(number: Int, vararg episodes: EpisodeDetail) = SeasonDetail(
        id = "s$number",
        seasonNumber = number,
        episodeCount = episodes.size,
        episodesWatched = 0,
        airYear = null,
        episodes = episodes.toList(),
    )

    @Test
    fun `isUnaired is true only for a known air date after today`() {
        assertTrue(episode(1, "2026-10-01").isUnaired(today))
        assertFalse(episode(1, "2026-09-25").isUnaired(today))
        assertFalse(episode(1, "2026-09-01").isUnaired(today))
        assertFalse(episode(1).isUnaired(today))
    }

    @Test
    fun `nextScheduledEpisode returns null when nothing is scheduled after today`() {
        val seasons = listOf(season(1, episode(1, "2026-09-18"), episode(2)))
        assertNull(seasons.nextScheduledEpisode(today))
    }

    @Test
    fun `nextScheduledEpisode finds the earliest future episode in season-episode order`() {
        val s2e3 = episode(3, "2026-10-02")
        val seasons = listOf(
            season(3, episode(1, "2027-01-01")),
            season(2, episode(4, "2026-10-09"), s2e3, episode(2), episode(1, "2026-09-25")),
        )
        val next = seasons.nextScheduledEpisode(today)
        assertEquals(2, next?.season?.seasonNumber)
        assertEquals(s2e3, next?.episode)
    }
}
