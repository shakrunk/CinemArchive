package work.kumarfamilynet.cinemarchive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.LibraryBackupRepository
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen
import work.kumarfamilynet.cinemarchive.feature.settings.LibraryBackupSection

/** Real CreateDocument contract and ContentResolver file writing, with the chooser result injected
 * through Android's ActivityResultRegistry so no external DocumentsUI or service is required. */
@RunWith(AndroidJUnit4::class)
class LibraryBackupScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LibraryDatabase
    private lateinit var destination: File
    private val active = mutableStateOf(true)
    private val owner = TicketOwnerScope("https://backup.invalid", "10000000-0000-4000-8000-000000000001")
    private val title = "20000000-0000-4000-8000-000000000001"
    private val registry = object : ActivityResultRegistry() {
        @Volatile var request: Int? = null
        @Volatile var intent: Intent? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            intent = contract.createIntent(context, input); request = requestCode
        }
    }
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        destination = File(context.cacheDir, "backup-${UUID.randomUUID()}.json")
        db.titleDao().upsertAll(listOf(TitleEntity(title, 42, "MOVIE", "Offline export film", 2026, null, emptyList(), null, null,
            null, 90, null, "WATCHLIST", null, "Saved locally", "2026-10-09T00:00:00Z", "2026-10-09T00:00:00Z")))
        db.outboxDao().enqueue(OutboxEntity("pending", "title", title, "fixture", "{}", 1))
    }
    @After fun cleanup() { db.close(); destination.delete() }
    private fun show() {
        val source = LibraryBackupRepository(db, owner, { active.value }, {})
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }) { MaterialTheme { if (active.value) LibraryBackupSection(source) } } }
    }
    private fun launch() {
        compose.onNodeWithText("Export JSON").performClick()
        compose.waitUntil(10_000) { registry.request != null }
    }
    private fun result(uri: Uri?) = compose.runOnIdle {
        registry.dispatchResult(checkNotNull(registry.request), if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, Intent().setData(uri))
    }

    @Test fun chosenDocumentContainsCurrentLocalGraphAndQueueIsUnchanged() {
        show(); launch()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, registry.intent!!.action)
        assertEquals("application/json", registry.intent!!.type)
        assertTrue(registry.intent!!.getStringExtra(Intent.EXTRA_TITLE)!!.startsWith("cinemarchive-"))
        result(Uri.fromFile(destination))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Exported 1 titles and 0 outings.").fetchSemanticsNodes().isNotEmpty() }
        val document = JSONObject(destination.readText())
        assertEquals(1, document.getInt("version"))
        assertEquals("Saved locally", document.getJSONArray("titles").getJSONObject(0).getString("notes"))
        runBlocking { assertEquals(1, db.outboxDao().getPending().size) }
    }

    @Test fun cancelledPickerAndAccountSwitchNeverReportSuccessOrWriteOldAccount() {
        show(); launch(); result(null)
        compose.onNodeWithText("Export cancelled. Your library has not changed.").assertExists()
        registry.request = null; launch()
        compose.runOnIdle { active.value = false }
        result(Uri.fromFile(destination))
        compose.waitForIdle()
        assertFalse(destination.exists())
        compose.onNodeWithText("Exported 1 titles and 0 outings.").assertDoesNotExist()
    }

    @Test fun unrelatedHistorySaveRetainsTwoCompanionsWithTheSameName() {
        val viewing = Viewing("event", "2026-10-01", 4.0, "Original", null, listOf("Sam", "Sam"))
        val detail = TitleDetail(title, MediaType.MOVIE, "History companion film", 2026, null, null, null, null, null, 90,
            LibraryStatus.WATCHED, null, null, emptyList(), emptyList(), listOf(viewing))
        var saved: ViewingDraft? = null
        compose.setContent { MaterialTheme { TitleDetailScreen(detail, {}, viewingOwnerId = owner.ownerId,
            onPrepareViewing = { ViewingDraft(viewing.id, viewing.date, viewing.rating, viewing.notes, viewing.venue, viewing.companions, "captured") },
            onSaveViewing = { draft, _ -> saved = draft }) } }
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Edit viewing"))
        compose.onNodeWithText("Edit viewing").performClick()
        compose.onNodeWithText("Notes").performScrollTo().performTextInput(" changed")
        compose.onNodeWithText("Save viewing").performScrollTo().performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(listOf("Sam", "Sam"), saved!!.companions)
        assertEquals("captured", saved!!.openingContext)
    }
}
