package work.kumarfamilynet.cinemarchive.feature.library

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.CatalogExtrasSource

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogExtrasStateTest {
    private val movie = CatalogExtrasKey(42, MediaType.MOVIE, "US")
    private val tv = movie.copy(type = MediaType.TV)
    private fun video(name: String) = CatalogVideo("abcdefghijk", name, "Trailer", true)

    @Test fun failedSectionRetriesIndependentlyAndEmptyIsNotError() = runTest {
        var videoCalls = 0
        var providerCalls = 0
        val source = object : CatalogExtrasSource {
            override suspend fun videos(key: CatalogExtrasKey): List<CatalogVideo> {
                if (++videoCalls == 1) error("secret transport diagnostic")
                return listOf(video("Recovered"))
            }
            override suspend fun providers(key: CatalogExtrasKey): CatalogProviders? { providerCalls++; return null }
        }
        val controller = CatalogExtrasController(source, backgroundScope)
        controller.select(movie)
        runCurrent()
        assertEquals(CatalogLoad.Failed, controller.state.value.videos)
        assertEquals(CatalogLoad.Ready<CatalogProviders?>(null), controller.state.value.providers)
        controller.retryVideos()
        runCurrent()
        assertEquals("Recovered", (controller.state.value.videos as CatalogLoad.Ready).value.single().name)
        assertEquals(1, providerCalls)
        controller.select(movie)
        runCurrent()
        assertEquals(2, videoCalls)
    }

    @Test fun delayedOldResponseCannotOverwriteMovieTvOrRegionSwitch() = runTest {
        val old = CompletableDeferred<Unit>()
        val source = object : CatalogExtrasSource {
            override suspend fun videos(key: CatalogExtrasKey): List<CatalogVideo> {
                if (key == movie) withContext(NonCancellable) { old.await() }
                return listOf(video("${key.type}/${key.region}"))
            }
            override suspend fun providers(key: CatalogExtrasKey): CatalogProviders? = null
        }
        val controller = CatalogExtrasController(source, backgroundScope)
        controller.select(movie)
        runCurrent()
        controller.select(tv)
        assertEquals(CatalogLoad.Loading, controller.state.value.videos)
        runCurrent()
        controller.select(tv.copy(region = "GB"))
        runCurrent()
        old.complete(Unit)
        runCurrent()
        assertEquals("TV/GB", (controller.state.value.videos as CatalogLoad.Ready).value.single().name)
    }

    @Test fun missingIdentityClearsResultsAndDoesNotFetch() = runTest {
        var calls = 0
        val source = object : CatalogExtrasSource {
            override suspend fun videos(key: CatalogExtrasKey): List<CatalogVideo> { calls++; return listOf(video("Old")) }
            override suspend fun providers(key: CatalogExtrasKey): CatalogProviders? = null
        }
        val controller = CatalogExtrasController(source, backgroundScope)
        controller.select(movie)
        runCurrent()
        controller.select(null)
        controller.retryVideos()
        runCurrent()
        assertNull(controller.state.value.key)
        assertEquals(CatalogLoad.Loading, controller.state.value.videos)
        assertEquals(1, calls)
    }

    @Test fun cancelledRuntimeCannotPublishLateReply() = runTest {
        val release = CompletableDeferred<Unit>()
        val runtimeJob = Job()
        val runtime = CoroutineScope(coroutineContext + runtimeJob)
        val source = object : CatalogExtrasSource {
            override suspend fun videos(key: CatalogExtrasKey): List<CatalogVideo> {
                withContext(NonCancellable) { release.await() }
                return listOf(video("Ended account"))
            }
            override suspend fun providers(key: CatalogExtrasKey): CatalogProviders? = null
        }
        val controller = CatalogExtrasController(source, runtime)
        controller.select(movie)
        runCurrent()
        runtimeJob.cancel()
        release.complete(Unit)
        runCurrent()
        assertEquals(CatalogLoad.Loading, controller.state.value.videos)
    }
}
