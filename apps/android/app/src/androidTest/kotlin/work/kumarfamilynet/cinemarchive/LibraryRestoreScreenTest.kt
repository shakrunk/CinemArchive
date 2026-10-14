package work.kumarfamilynet.cinemarchive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.settings.LibraryRestoreSection

/** Uses the installed OpenDocument contract and ContentResolver with a deterministic picker
 * result. Real Room graph/queue admission is exercised; no remote service is contacted. */
@RunWith(AndroidJUnit4::class)
class LibraryRestoreScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LibraryDatabase
    private lateinit var input: File
    private val active = mutableStateOf(true)
    private var fail = false
    private val owner = TicketOwnerScope("https://backup.invalid", "10000000-0000-4000-8000-000000000001")
    private val registry = object : ActivityResultRegistry() {
        @Volatile var request: Int? = null
        @Volatile var intent: Intent? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            intent = contract.createIntent(context, input); request = requestCode
        }
    }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        input = File(context.cacheDir, "restore-${UUID.randomUUID()}.json")
        input.writeText("""{"version":1,"titles":[{"id":"old","tmdbId":42,"type":"movie","title":"Restored film","year":2026,"status":"watched","addedAt":"2026-10-09T00:00:00Z","genres":[],"tags":[],"viewings":[{"id":"watch","date":"2026-10-01","notes":"My history","companions":[{"name":"Sam","friendUserId":"10000000-0000-4000-8000-000000000002"}]}]}],"outings":[]}""")
    }
    @After fun cleanup() { db.close(); input.delete() }
    private fun show() {
        val dao = object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) { check(!fail) { "Disk full" }; db.outboxDao().enqueue(entry) }
        }
        val box = MutationOutbox(dao, object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline fixture") },
            TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        val source = BackupImportRepository(db, box, owner, { active.value }, {}, { it() })
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }) { MaterialTheme { if (active.value) Column(Modifier.verticalScroll(rememberScrollState())) { LibraryRestoreSection(source) } } } }
    }
    private fun pick() {
        compose.onNodeWithText("Choose JSON file").performScrollTo().performClick()
        compose.waitUntil(10_000) { registry.request != null }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, registry.intent!!.action)
        compose.runOnIdle { registry.dispatchResult(checkNotNull(registry.request), Activity.RESULT_OK, Intent().setData(Uri.fromFile(input))) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Save new titles").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun selectedArchiveNeedsConfirmationThenPersistsWholeGraphAndExactQueue() {
        show(); pick()
        runBlocking { assertEquals(0, db.titleDao().count()) }
        compose.onNodeWithText("Save new titles").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Saved 1 titles on this device; 0 skipped. They will sync when connected.").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            assertEquals(1, db.titleDao().count())
            val entry = db.outboxDao().getPending().single()
            assertEquals("import_graph_v1", entry.operation)
            val title = db.titleDao().getById(entry.entityId)!!
            assertEquals("Restored film", title.title)
            assertTrue(entry.payloadJson.contains("My history"))
        }
        compose.onNodeWithText("Review removal of Restored film").assertDoesNotExist()
        registry.request = null; pick()
        compose.onNodeWithText("0 new titles and 0 outings ready. 1 existing or duplicate titles and 0 associated outings skipped.").assertExists()
        compose.onNodeWithText("Save new titles").assertIsNotEnabled()
    }
    @Test fun storageFailureReportsNoSuccessAndChosenFileCanBeRetried() {
        show(); pick(); fail = true
        compose.onNodeWithText("Save new titles").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Disk full", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { assertEquals(0, db.titleDao().count()); assertTrue(db.outboxDao().getPending().isEmpty()) }
        fail = false; registry.request = null; pick()
        compose.onNodeWithText("Save new titles").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Saved 1 titles on this device; 0 skipped. They will sync when connected.").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun accountSwitchClearsPreviewAndCannotAdmitOldAccountFile() {
        show(); pick()
        compose.runOnIdle { active.value = false }; compose.waitForIdle()
        compose.onNodeWithText("Save new titles").assertDoesNotExist()
        runBlocking { assertEquals(0, db.titleDao().count()); assertTrue(db.outboxDao().getPending().isEmpty()) }
    }

    @Test fun pickerResultArrivingAfterAccountSwitchCannotOpenOrAdmitFile() {
        show()
        compose.onNodeWithText("Choose JSON file").performScrollTo().performClick()
        compose.waitUntil(10_000) { registry.request != null }
        compose.runOnIdle {
            active.value = false
            registry.dispatchResult(checkNotNull(registry.request), Activity.RESULT_OK, Intent().setData(Uri.fromFile(input)))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Save new titles").assertDoesNotExist()
        runBlocking { assertEquals(0, db.titleDao().count()); assertTrue(db.outboxDao().getPending().isEmpty()) }
    }
}
