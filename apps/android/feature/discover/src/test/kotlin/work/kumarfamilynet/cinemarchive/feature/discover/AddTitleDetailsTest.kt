package work.kumarfamilynet.cinemarchive.feature.discover

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType

class AddTitleDetailsTest {
    private val snapshot = MediaSearchResult(42, "Snapshot title", 2026, MediaType.MOVIE, "poster", "synopsis")

    @Test fun unavailableMetadataKeepsExactSnapshotAndNoInventedMetadata() = runTest {
        val details = loadAddTitleDetails(snapshot) { throw IOException("Endpoint unavailable") }
        assertEquals(snapshot.tmdbId, details.tmdbId)
        assertEquals(snapshot.type, details.type)
        assertEquals(snapshot.title, details.title)
        assertEquals(snapshot.year, details.year)
        assertEquals(snapshot.posterUrl, details.posterUrl)
        assertEquals(snapshot.synopsis, details.synopsis)
        assertTrue(details.genres.isEmpty())
        assertTrue(details.seasons.isEmpty())
        assertNull(details.runtime)
        assertNull(details.director)
    }

    @Test fun tvSnapshotRemainsTvWithUnknownYearAndNoInventedSeasons() = runTest {
        val details = loadAddTitleDetails(snapshot.copy(type = MediaType.TV, year = null, posterUrl = null)) { error("Unavailable") }
        assertEquals(MediaType.TV, details.type)
        assertNull(details.year)
        assertNull(details.posterUrl)
        assertTrue(details.seasons.isEmpty())
    }

    @Test fun successfulHydrationPreservesRichMetadata() = runTest {
        val rich = loadAddTitleDetails(snapshot) { error("seed") }.copy(
            title = "Detailed title", genres = listOf("Drama"), director = "Director", runtime = 120,
        )
        assertSame(rich, loadAddTitleDetails(snapshot) { rich })
    }

    @Test fun cancelledMetadataNeverProducesFallback() = runTest {
        try {
            loadAddTitleDetails(snapshot) { throw CancellationException("Account disposed") }
            fail("Must propagate cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun lateNonCooperativeFetchCannotPublishToCancelledSelection() = runTest {
        var published = false
        val job = launch {
            val ownerJob = coroutineContext
            loadAddTitleDetails(snapshot) {
                withContext(NonCancellable) {
                    ownerJob.cancel()
                    throw IOException("Late network failure")
                }
            }
            published = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(published)
    }
}
