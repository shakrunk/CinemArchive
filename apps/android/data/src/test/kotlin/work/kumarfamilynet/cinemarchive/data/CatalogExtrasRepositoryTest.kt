package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.*

class CatalogExtrasRepositoryTest {
    @Test fun videosUseWebRankingStableTiesAndFourItemLimit() {
        fun video(key: String, type: String, official: Boolean, site: String = "YouTube") =
            """{"key":"$key","name":"$key","site":"$site","type":"$type","official":$official}"""
        val json = """{"results":[${listOf(
            video("teaser00001", "Teaser", false), video("trailer0001", "Trailer", false),
            video("official001", "Teaser", true), video("official002", "Trailer", true),
            video("official003", "Trailer", true), video("ignored0001", "Clip", true),
            video("ignored0002", "Trailer", true, "Vimeo"),
        ).joinToString(",")}]}"""
        assertEquals(listOf("official002", "official003", "official001", "trailer0001"), parseCatalogVideos(json).map { it.key })
        assertEquals("https://www.youtube.com/watch?v=official002", parseCatalogVideos(json).first().watchUrl)
    }

    @Test fun malformedVideoKeysCannotBecomeExternalUrls() {
        val result = parseCatalogVideos("""{"results":[{"site":"YouTube","type":"Trailer","key":"../?v=evil"},{"site":"YouTube","type":"Teaser","key":"abc_DEF-12","name":""},{"site":"YouTube","type":"Trailer","key":"abc_DEF-123","name":""}]}""")
        assertEquals(1, result.size)
        assertEquals("Trailer", result.single().name)
        assertEquals("https://i.ytimg.com/vi/abc_DEF-123/hqdefault.jpg", result.single().thumbnailUrl)
    }

    @Test fun providersSelectRegionThenUsAndCombineOnlyStreamingGroups() {
        val json = """{"results":{"GB":{"link":"https://www.themoviedb.org/movie/42/watch?locale=GB",
            "flatrate":[{"provider_id":1,"provider_name":"Paid","logo_path":"/paid.jpg"}],
            "free":[{"provider_id":2,"provider_name":"Free","logo_path":null}],
            "ads":[{"provider_id":3,"provider_name":"Ads"}],
            "rent":[{"provider_id":4,"provider_name":"Rental"}],"buy":[{"provider_id":5,"provider_name":"Purchase"}]},
            "US":{"buy":[{"provider_id":6,"provider_name":"US store"}]}}}"""
        val gb = parseCatalogProviders(json, "GB")!!
        assertEquals(listOf("Paid", "Free", "Ads"), gb.stream.map { it.name })
        assertEquals(listOf("Rental"), gb.rent.map { it.name })
        assertEquals(listOf("Purchase"), gb.buy.map { it.name })
        assertEquals("https://image.tmdb.org/t/p/w92/paid.jpg", gb.stream.first().logoUrl)
        assertNull(gb.stream[1].logoUrl)
        assertEquals("US store", parseCatalogProviders(json, "CA")!!.buy.single().name)
        assertEquals("GB", catalogWatchRegion("en-GB"))
        assertEquals("US", catalogWatchRegion("fr"))
        assertEquals("US", catalogWatchRegion(""))
    }

    @Test fun emptySelectedRegionDoesNotFallBackAndUnsafeLinksAreOmitted() {
        assertTrue(parseCatalogProviders("""{"results":{"GB":{},"US":{"rent":[{"provider_id":1,"provider_name":"US"}]}}}""", "GB")!!.isEmpty)
        assertNull(parseCatalogProviders("""{"results":{"CA":{}}}""", "GB"))
        for (link in listOf("javascript:alert(1)", "file:///x", "https://user:password@example.com/", "//evil.example")) {
            assertNull(parseCatalogProviders("""{"results":{"US":{"link":"$link"}}}""", "US")!!.link)
        }
        assertNull(parseCatalogProviders("""{"results":{"US":{"free":[{"provider_id":1,"provider_name":"Safe name","logo_path":"//evil.example/p.jpg"}]}}}""", "US")!!.stream.single().logoUrl)
    }

    @Test fun movieAndTvRequestsKeepSameNumericIdDistinct() = runBlocking {
        val queries = mutableListOf<String>()
        val repository = CatalogExtrasRepository(SessionSource { SupabaseSession("fake-token", "owner") }) { query, token ->
            assertEquals("fake-token", token)
            queries += query
            """{"results":{}}"""
        }
        repository.providers(CatalogExtrasKey(42, MediaType.MOVIE, "US"))
        repository.providers(CatalogExtrasKey(42, MediaType.TV, "US"))
        assertEquals(listOf("action=watch_providers&id=42&type=movie", "action=watch_providers&id=42&type=tv"), queries)
    }

    @Test fun sessionEndingDuringResponseCannotPublishResultOrUseNewAccount() = runBlocking {
        var active = true
        val repository = CatalogExtrasRepository(SessionSource { if (active) SupabaseSession("A-token", "A") else null }) { _, _ ->
            active = false // The runtime's generation-fenced source remains closed even on Aâ†’Bâ†’A.
            """{"results":[]}"""
        }
        val result = runCatching { repository.videos(CatalogExtrasKey(42, MediaType.MOVIE, "US")) }
        assertEquals("This sign-in has ended", result.exceptionOrNull()?.message)
        assertTrue(runCatching { repository.videos(CatalogExtrasKey(42, MediaType.MOVIE, "US")) }.isFailure)
    }

    @Test fun cancellationAndMalformedResponsesRemainFailures() = runBlocking {
        val session = SessionSource { SupabaseSession("token", "owner") }
        val cancelled = CatalogExtrasRepository(session) { _, _ -> throw CancellationException("cancelled") }
        assertTrue(runCatching { cancelled.videos(CatalogExtrasKey(1, MediaType.TV, "US")) }.exceptionOrNull() is CancellationException)
        val malformed = CatalogExtrasRepository(session) { _, _ -> "not-json" }
        assertTrue(runCatching { malformed.providers(CatalogExtrasKey(1, MediaType.TV, "US")) }.isFailure)
    }
}
