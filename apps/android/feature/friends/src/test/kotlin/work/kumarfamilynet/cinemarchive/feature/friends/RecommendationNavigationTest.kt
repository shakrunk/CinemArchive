package work.kumarfamilynet.cinemarchive.feature.friends

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.data.Recommendation
import work.kumarfamilynet.cinemarchive.data.RecommendationStatus

class RecommendationNavigationTest {
    private val recommendation = Recommendation(
        "recommendation", "sender", null, null, 42, MediaType.MOVIE, "Film", 2026,
        "https://example.com/poster.jpg", null, null, RecommendationStatus.UNREAD, "2026-10-13",
    )

    @Test fun `owned recommendation uses catalog id and type then opens local title`() = runTest {
        val reads = mutableListOf<String>()
        val destination = recommendationNavigation(recommendation, { true }, { reads += it }) { id, type ->
            assertEquals(42, id)
            assertEquals(MediaType.MOVIE, type)
            "local-title"
        }
        assertEquals(listOf("recommendation"), reads)
        assertEquals(RecommendationNavigation.Owned("local-title"), destination)
    }

    @Test fun `unowned recommendation preselections retain metadata despite read receipt failure`() = runTest {
        val destination = recommendationNavigation(recommendation, { true }, { error("offline") }) { _, _ -> null }
            as RecommendationNavigation.Add
        assertEquals(42, destination.title.tmdbId)
        assertEquals("Film", destination.title.title)
        assertEquals(2026, destination.title.year)
        assertEquals(recommendation.posterUrl, destination.title.posterUrl)
        assertEquals(MediaType.MOVIE, destination.title.type)
    }

    @Test fun `identity changes during receipt or lookup cannot navigate`() = runTest {
        var active = true
        assertNull(recommendationNavigation(recommendation, { active }, { active = false }) { _, _ ->
            fail("Must not read next owner's library")
            null
        })
        active = true
        assertNull(recommendationNavigation(recommendation, { active }, {}) { _, _ ->
            active = false
            "old-title"
        })
        assertNull(recommendationNavigation(recommendation, { false }, { fail("Inactive write") }) { _, _ -> null })
    }

    @Test fun `already read recommendation does not send another receipt`() = runTest {
        assertNotNull(recommendationNavigation(recommendation.copy(status = RecommendationStatus.READ), { true }, {
            fail("Already read")
        }) { _, _ -> null })
    }

    @Test fun `unknown media type cannot become a movie`() = runTest {
        assertNull(recommendationNavigation(recommendation.copy(type = null), { true }, { fail("Unknown type") }) { _, _ -> null })
    }

    @Test fun `cancellation propagates without navigation`() = runTest {
        try {
            recommendationNavigation(recommendation, { true }, { throw CancellationException() }) { _, _ ->
                fail("Cancelled lookup")
                null
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun `watch links permit web destinations only`() {
        assertEquals("https://example.com/watch?a=1", recommendationWatchUrl("https://example.com/watch?a=1"))
        listOf(null, "javascript:alert(1)", "intent://watch", "file:///private", "https://user@example.com", "https://").forEach {
            assertNull(recommendationWatchUrl(it))
        }
    }
}
