package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.*
import org.junit.Test

class NoirProgressTest {
    private fun episode(id: String, modes: List<String> = emptyList(), review: String? = null) = EpisodeDetail(id, 1, id, null, 30, modes.size, null,
        watchEvents = modes.mapIndexed { index, mode -> EpisodeWatch("$id-$index", null, colorMode = mode) },
        reviews = review?.let { listOf(EpisodeReview("review", "Text", "date", it)) }.orEmpty())
    private fun title(main: List<EpisodeDetail>, special: List<EpisodeDetail> = emptyList()) = TitleDetail("title", MediaType.TV, "Noir", 2026,
        null, null, null, null, null, 30, LibraryStatus.WATCHING, null, null, emptyList(),
        listOf(SeasonDetail("main", 1, 10, 10, 2026, main), SeasonDetail("specials", 0, 1, 1, 2026, special)), emptyList(), tmdbId = SPIDER_NOIR_TMDB_ID)
    @Test fun specialsUnlockButDoNotEarnAndCoarseProgressIsNotEvidence() {
        val result = noirProgress(title(emptyList(), listOf(episode("special", listOf("bw")))))
        assertEquals(setOf("bw"), result.unlocked); assertTrue(result.earned.isEmpty())
    }
    @Test fun allStoredMainEpisodesMustHaveModeAndReviewsDoNotUnlock() {
        val result = noirProgress(title(listOf(episode("one", listOf("bw", "color")), episode("two", listOf("bw"), "color"))))
        assertEquals(setOf("bw", "color"), result.unlocked); assertEquals(setOf("bw"), result.earned); assertEquals("color", result.lastMode)
        assertTrue(noirProgress(title(listOf(episode("review-only", review = "bw")))).unlocked.isEmpty())
    }
    @Test fun foreignTitleHasNoNoirProgress() {
        assertTrue(noirProgress(title(listOf(episode("one", listOf("bw")))).copy(tmdbId = 42)).unlocked.isEmpty())
    }
    @Test fun transientSelectionResetsOnCloseUnlessPinnedAndNeverLeaksIntoAnotherTitle() {
        val preview = NoirPreview("noir", "bw")
        assertEquals("bw", effectiveNoirMode("noir", preview, emptyMap()))
        assertNull(effectiveNoirMode(null, preview, emptyMap()))
        assertEquals("color", effectiveNoirMode(null, preview, mapOf("noir" to "color")))
        assertNull(effectiveNoirMode("other", preview, mapOf("noir" to "bw")))
        assertNull(effectiveNoirMode(null, null, mapOf("noir" to "bw")))
        assertNull(effectiveNoirMode("noir", preview.copy(mode = null), mapOf("noir" to "bw")))
    }
}
