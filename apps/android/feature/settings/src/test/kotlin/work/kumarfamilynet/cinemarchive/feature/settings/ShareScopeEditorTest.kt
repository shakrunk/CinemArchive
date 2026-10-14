package work.kumarfamilynet.cinemarchive.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.data.ShareScope
import work.kumarfamilynet.cinemarchive.data.ShareScopeTarget

@OptIn(ExperimentalCoroutinesApi::class)
class ShareScopeEditorTest {
    private val friend = ShareScopeTarget.Friend("friend-a")

    @Test fun existingScopeRetainsUnavailableGenresAndCombinesDimensions() = runTest {
        val source = FakeSource(ShareScope(listOf("Retired genre"), listOf("watching")))
        val controller = ShareScopeEditorController(source, friend, this)
        advanceUntilIdle()
        assertTrue(controller.state.value.custom)
        controller.toggleGenre("Drama"); controller.toggleStatus("watched"); controller.save()
        advanceUntilIdle()
        assertEquals(friend, source.saved.single().first)
        assertEquals(ShareScope(listOf("Retired genre", "Drama"), listOf("watching", "watched")), source.value)
        assertTrue(controller.state.value.saved)
    }

    @Test fun emptyDimensionsAreNullAndFullLibraryUsesNullDeletion() = runTest {
        val source = FakeSource()
        val custom = ShareScopeEditorController(source, friend, this)
        advanceUntilIdle()
        custom.setCustom(true); custom.toggleGenre("Drama"); custom.toggleGenre("Drama")
        custom.toggleStatus("watched"); custom.toggleStatus("watched"); custom.save()
        advanceUntilIdle()
        assertEquals(ShareScope(null, null), source.saved.single().second)
        val full = ShareScopeEditorController(source, friend, this)
        advanceUntilIdle(); full.setCustom(false); full.save(); advanceUntilIdle()
        assertNull(source.saved.last().second)
    }

    @Test fun cancelDoesNotWriteAndReopeningLoadsSavedScope() = runTest {
        val source = FakeSource(ShareScope(listOf("Drama"), null))
        val first = ShareScopeEditorController(source, friend, this)
        advanceUntilIdle(); first.toggleGenre("Horror"); first.dispose(); first.save(); advanceUntilIdle()
        assertTrue(source.saved.isEmpty())
        val reopened = ShareScopeEditorController(source, friend, this)
        advanceUntilIdle()
        assertEquals(setOf("Drama"), reopened.state.value.genres)
    }

    @Test fun loadFailureNeverEnablesSaveAndCanRetryWithoutExposingBackendBody() = runTest {
        val source = FakeSource().apply { failLoad = true }
        val controller = ShareScopeEditorController(source, friend, this)
        advanceUntilIdle(); controller.save(); advanceUntilIdle()
        assertFalse(controller.state.value.loaded)
        assertFalse(controller.state.value.error!!.contains("secret"))
        assertTrue(source.saved.isEmpty())
        source.failLoad = false; controller.reload(); advanceUntilIdle()
        assertTrue(controller.state.value.loaded)
        assertNull(controller.state.value.error)
    }

    @Test fun failedSaveKeepsDraftAndRetryUsesSameTargetAndSelections() = runTest {
        val source = FakeSource().apply { failSave = true }
        val controller = ShareScopeEditorController(source, friend, this)
        advanceUntilIdle(); controller.setCustom(true); controller.toggleStatus("watched")
        controller.save(); advanceUntilIdle()
        assertEquals(setOf("watched"), controller.state.value.statuses)
        assertFalse(controller.state.value.saved)
        assertFalse(controller.state.value.error!!.contains("secret"))
        source.failSave = false; controller.save(); advanceUntilIdle()
        assertEquals(source.saved[0], source.saved[1])
        assertTrue(controller.state.value.saved)
    }

    @Test fun saveCapturesDraftAndSuppressesDuplicateClicksAndEdits() = runTest {
        val done = CompletableDeferred<Unit>()
        val source = FakeSource().apply { saveWait = done }
        val controller = ShareScopeEditorController(source, friend, this)
        advanceUntilIdle(); controller.setCustom(true); controller.toggleGenre("Drama")
        controller.save(); controller.save(); controller.toggleGenre("Horror"); runCurrent()
        assertEquals(1, source.saved.size)
        done.complete(Unit); advanceUntilIdle()
        assertEquals(ShareScope(listOf("Drama"), null), source.value)
        controller.save(); advanceUntilIdle()
        assertEquals(1, source.saved.size)
    }

    @Test fun disposedEditorDiscardsEvenUncancellableRead() = runTest {
        val pending = CompletableDeferred<ShareScope?>()
        val source = FakeSource().apply { loadWait = pending }
        val controller = ShareScopeEditorController(source, friend, this)
        runCurrent(); controller.dispose()
        pending.complete(ShareScope(listOf("Old account"), null)); advanceUntilIdle()
        assertFalse(controller.state.value.loaded)
        assertTrue(controller.state.value.genres.isEmpty())
    }

    @Test fun inactiveOwnerCannotWriteOrPublishLateSaveSuccess() = runTest {
        val done = CompletableDeferred<Unit>()
        val source = FakeSource().apply { saveWait = done }
        val controller = ShareScopeEditorController(source, friend, this)
        advanceUntilIdle(); controller.save(); runCurrent()
        source.active = false; done.complete(Unit); advanceUntilIdle()
        assertFalse(controller.state.value.saved)
        controller.reload(); controller.save(); advanceUntilIdle()
        assertEquals(1, source.saved.size)
    }

    @Test fun linkTargetUsesIdenticalSelectionSemantics() = runTest {
        val source = FakeSource(ShareScope(emptyList(), emptyList()))
        val target = ShareScopeTarget.Link("link-a")
        val controller = ShareScopeEditorController(source, target, this)
        advanceUntilIdle(); controller.save(); advanceUntilIdle()
        assertEquals(target to ShareScope(null, null), source.saved.single())
    }

    private class FakeSource(var value: ShareScope? = null) : ShareScopeEditorSource {
        var active = true
        var failLoad = false
        var failSave = false
        var loadWait: CompletableDeferred<ShareScope?>? = null
        var saveWait: CompletableDeferred<Unit>? = null
        val saved = mutableListOf<Pair<ShareScopeTarget, ShareScope?>>()
        override fun isActive() = active
        override suspend fun load(target: ShareScopeTarget): ShareScope? {
            check(!failLoad) { "secret backend body" }
            return loadWait?.let { withContext(NonCancellable) { it.await() } } ?: value
        }
        override suspend fun save(target: ShareScopeTarget, value: ShareScope?) {
            saved += target to value
            saveWait?.let { withContext(NonCancellable) { it.await() } }
            check(!failSave) { "secret backend body" }
            this.value = value
        }
    }
}
