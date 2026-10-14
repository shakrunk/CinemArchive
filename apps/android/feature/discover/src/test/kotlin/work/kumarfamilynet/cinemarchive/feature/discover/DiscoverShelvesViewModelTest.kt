package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.lifecycle.ViewModelStore
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.DiscoverLibrary
import work.kumarfamilynet.cinemarchive.data.DiscoverLibraryTitle

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverShelvesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val initial = DiscoverLibrary(listOf(DiscoverLibraryTitle("movie", 42, "First title", MediaType.MOVIE),
        DiscoverLibraryTitle("series", 84, "Second title", MediaType.TV)), listOf(LibraryPerson(7, "Alex"), LibraryPerson(8, "Alex")))
    private val library = MutableStateFlow(initial)
    private fun title(id: Int, type: MediaType = MediaType.MOVIE) = TrendingTitle(id, "Result $id $type", 2026, type, null, null)
    private fun model(
        recommendations: suspend (Int, MediaType) -> List<TrendingTitle> = { _, _ -> emptyList() },
        person: suspend (Int) -> List<TrendingTitle> = { emptyList() },
    ) = DiscoverShelvesViewModel(library, recommendations, person).also { store.put("shelves", it) }
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun firstLibraryTitleAndFirstCastDefaultWhilePickersDriveIndependentRequests() = runTest(dispatcher) {
        val seeds = mutableListOf<Pair<Int, MediaType>>()
        val people = mutableListOf<Int>()
        val model = model({ id, type -> seeds += id to type; listOf(title(id + 100)) }, { people += it; listOf(title(it)) })
        runCurrent()
        assertTrue(seeds.isEmpty()); assertTrue(people.isEmpty())
        model.setVisible(true); runCurrent()
        assertEquals(listOf(42 to MediaType.MOVIE), seeds)
        assertEquals(listOf(7), people)
        model.selectTitle("series"); runCurrent()
        assertEquals(listOf(42 to MediaType.MOVIE, 84 to MediaType.TV), seeds)
        assertEquals(listOf(7), people)
        model.selectPerson(8); runCurrent()
        assertEquals(listOf(7, 8), people)
        model.selectPerson(999); model.selectTitle("absent"); runCurrent()
        assertEquals(2, people.size); assertEquals(2, seeds.size)
    }

    @Test fun typeAndNewOwnershipAreAppliedLocallyWithoutCollapsingMovieTvIdentity() = runTest(dispatcher) {
        var calls = 0
        val model = model({ _, _ -> calls++; listOf(title(42), title(42, MediaType.TV), title(99), title(99)) })
        model.setVisible(true); runCurrent()
        var state = model.state.value
        assertEquals(listOf(title(42, MediaType.TV), title(99)), state.visible(state.recommendations, TypeFilter.ALL))
        assertEquals(listOf(title(42, MediaType.TV)), state.visible(state.recommendations, TypeFilter.TV))
        assertEquals(listOf(title(99)), state.visible(state.recommendations, TypeFilter.MOVIE))
        library.value = initial.copy(titles = initial.titles + DiscoverLibraryTitle("new", 99, "New ownership", MediaType.MOVIE))
        runCurrent(); state = model.state.value
        assertEquals(listOf(title(42, MediaType.TV)), state.visible(state.recommendations, TypeFilter.ALL))
        assertEquals(1, calls)
    }

    @Test fun lateOldSeedResponseCannotPublishOverNewSeedOrRestoreHiddenResults() = runTest(dispatcher) {
        lateinit var old: Continuation<List<TrendingTitle>>
        val model = model({ id, _ -> if (id == 42) suspendCoroutine { old = it } else listOf(title(100)) })
        model.setVisible(true); runCurrent()
        model.selectTitle("series"); runCurrent()
        old.resume(listOf(title(999))); runCurrent()
        assertEquals(listOf(title(100)), model.state.value.recommendations.titles)
        model.selectTitle("movie"); runCurrent()
        model.setVisible(false)
        old.resume(listOf(title(888))); runCurrent()
        assertTrue(model.state.value.recommendations.titles.isEmpty())
        model.setVisible(true); runCurrent()
        old.resume(listOf(title(777))); runCurrent()
        assertEquals(listOf(title(777)), model.state.value.recommendations.titles)
    }

    @Test fun lateOldPersonFailureCannotReplaceNewPersonsFilmography() = runTest(dispatcher) {
        lateinit var old: Continuation<List<TrendingTitle>>
        val model = model(person = { if (it == 7) suspendCoroutine { old = it } else listOf(title(88)) })
        model.setVisible(true); runCurrent()
        model.selectPerson(8)
        assertTrue(model.state.value.starring.titles.isEmpty())
        runCurrent()
        old.resumeWithException(IllegalStateException("Old private response")); runCurrent()
        assertEquals(listOf(title(88)), model.state.value.starring.titles)
        assertNull(model.state.value.starring.error)
    }

    @Test fun errorsRetryIndependentlyAndNeverExposeRawResponses() = runTest(dispatcher) {
        var recommendationCalls = 0
        var personCalls = 0
        val model = model({ _, _ -> if (++recommendationCalls == 1) error("private token"); listOf(title(100)) },
            { if (++personCalls == 1) error("secret URL"); listOf(title(101)) })
        model.setVisible(true); runCurrent()
        assertFalse(model.state.value.recommendations.error!!.contains("private"))
        assertFalse(model.state.value.starring.error!!.contains("secret"))
        model.retryRecommendations(); runCurrent()
        assertNull(model.state.value.recommendations.error)
        assertNotNull(model.state.value.starring.error)
        assertEquals(1, personCalls)
        model.retryStarring(); runCurrent()
        assertEquals(listOf(title(101)), model.state.value.starring.titles)
        assertEquals(2, recommendationCalls)
    }

    @Test fun removalFallsBackToRemainingOptionsAndEmptyLibraryClearsBothShelves() = runTest(dispatcher) {
        val model = model({ id, _ -> listOf(title(id + 100)) }, { listOf(title(it)) })
        model.setVisible(true); runCurrent()
        library.value = DiscoverLibrary(listOf(initial.titles[1]), listOf(initial.cast[1])); runCurrent()
        assertEquals("series", model.state.value.selectedTitleId)
        assertEquals(8, model.state.value.selectedPersonId)
        assertEquals(listOf(title(184)), model.state.value.recommendations.titles)
        library.value = DiscoverLibrary(); runCurrent()
        assertNull(model.state.value.selectedTitleId); assertNull(model.state.value.selectedPersonId)
        assertTrue(model.state.value.recommendations.titles.isEmpty()); assertTrue(model.state.value.starring.titles.isEmpty())
    }

    @Test fun manualTitleDoesNotRequestInvalidIdAndNoCastDoesNotRequestPerson() = runTest(dispatcher) {
        library.value = DiscoverLibrary(listOf(DiscoverLibraryTitle("manual", 0, "Manual title", MediaType.MOVIE)))
        val model = model({ _, _ -> error("must not request") }, { error("must not request") })
        model.setVisible(true); runCurrent(); model.retryRecommendations(); model.retryStarring(); runCurrent()
        assertEquals("manual", model.state.value.selectedTitleId)
        assertFalse(model.state.value.recommendations.loading); assertNull(model.state.value.recommendations.error)
        assertTrue(model.state.value.starring.titles.isEmpty()); assertNull(model.state.value.starring.error)
    }

    @Test fun clearingAccountViewModelStoreRejectsLateNetworkResults() = runTest(dispatcher) {
        lateinit var recommendation: Continuation<List<TrendingTitle>>
        lateinit var person: Continuation<List<TrendingTitle>>
        val model = model({ _, _ -> suspendCoroutine { recommendation = it } }, { suspendCoroutine { person = it } })
        model.setVisible(true); runCurrent(); store.clear()
        recommendation.resume(listOf(title(999))); person.resume(listOf(title(888))); runCurrent()
        assertTrue(model.state.value.recommendations.titles.isEmpty())
        assertTrue(model.state.value.starring.titles.isEmpty())
    }
}
