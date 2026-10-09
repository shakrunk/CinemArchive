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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.settings.ImportSyncRoute

/** Real CSV picker -> provider resolution -> Room journal; all HTTP is intercepted locally. */
@RunWith(AndroidJUnit4::class)
class ProviderImportScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LibraryDatabase
    private lateinit var file: File
    private val active = mutableStateOf(true)
    private var fail = false
    private val admissions = AtomicInteger()
    private val owner = TicketOwnerScope("https://provider.invalid", "10000000-0000-4000-8000-000000000001")
    private val registry = object : ActivityResultRegistry() {
        var request: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, contract.createIntent(context, input).action)
            request = requestCode
        }
    }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        file = File(context.cacheDir, "provider-${UUID.randomUUID()}.csv")
        file.writeText("Name,Year,Rating,Watched Date\nProvider film,2026,4,2026-01-02\nProvider film,2026,4,2026-01-01\n")
    }
    @After fun close() { db.close(); file.delete() }
    private fun show() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val body = when {
                url.encodedPath.endsWith("/integration_connections") -> "[]"
                url.queryParameter("action") == "search" && url.queryParameter("type") == "movie" ->
                    """{"results":[{"id":42,"title":"Provider film","release_date":"2026-01-01"}]}"""
                url.queryParameter("action") == "search" -> """{"results":[]}"""
                url.queryParameter("action") == "details" -> """{"id":42,"title":"Provider film","release_date":"2026-01-01","runtime":90,"genres":[]}"""
                else -> error("Unexpected fixture request: ${url.encodedPath}")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = SupabaseRestClient(owner.projectId, "anon", http)
        val auth = AuthRepository(object : SessionStore {
            override fun read() = SupabaseSession("token", owner.ownerId)
            override fun write(session: SupabaseSession) = Unit
            override fun clear() = Unit
        }, client)
        val discover = DiscoverRepository(client, auth)
        val dao = object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) {
                admissions.incrementAndGet()
                check(!fail) { "Disk full" }; db.outboxDao().enqueue(entry)
            }
        }
        val box = MutationOutbox(dao, object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline") },
            TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        val library = LibraryRepository(db.titleDao(), db.seasonDao(), db.episodeDao(), db.episodeWatchEventDao(),
            db.episodeRatingDao(), db.episodeReviewDao(), db.viewingDao(), db.cinemaOutingDao(), db.titleCastDao(),
            db.titleCrewDao(), db.theaterInterestDao(), box, discover, db.personCreditsDao(), mutationOwnerId = owner.ownerId)
        val services = SyncServices.create(library, discover, auth, client, "fixture",
            ProviderImportAdmission(db, box, owner, { active.value }),
            ProviderMergeRepository(db, box, owner, { active.value }, ProviderMergeTransport(client, auth, owner, { active.value }), {}, { it() }),
            { active.value }, {})
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }) { MaterialTheme { ImportSyncRoute(services, {}) } } }
    }
    private fun launchPicker() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Import CSV"))
        compose.onNodeWithText("Import CSV").performClick()
        compose.waitUntil(10_000) { registry.request != null }
    }
    private fun deliver() = compose.runOnIdle {
        registry.dispatchResult(checkNotNull(registry.request), Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
    }
    private fun showImportReport() {
        compose.waitUntil(15_000) { admissions.get() > 0 }
        // Import CSV is at the bottom. The result is a newly inserted lazy item near
        // the top, so it has no semantics node until we scroll it into composition.
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
    }
    @Test fun csvQueuesOneAtomicTitleWithEveryDateAndProviderIdentity() {
        show(); launchPicker(); deliver(); showImportReport()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Added 1", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            assertEquals(1, db.titleDao().count())
            val entry = db.outboxDao().getPending().single()
            val body = JSONObject(entry.payloadJson).getJSONObject("libraryImport")
            assertEquals(2, body.getJSONObject("title").getJSONArray("viewings").length())
            assertEquals("letterboxd", body.getJSONArray("providerLinks").getJSONObject(0).getString("provider"))
            assertEquals("external_title_links", body.getJSONArray("operations").getJSONObject(body.getJSONArray("operations").length() - 1).getString("table"))
        }
    }
    @Test fun storageFailureIsReportedAsUnsavedRatherThanUnmatched() {
        fail = true; show(); launchPicker(); deliver(); showImportReport()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("not saved", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("couldn't match", substring = true)).assertDoesNotExist()
        runBlocking { assertEquals(0, db.titleDao().count()); assertTrue(db.outboxDao().getPending().isEmpty()) }
    }
    @Test fun csvMergesExistingTitleOptimisticallyWithOneProviderRequest() {
        val id = "10000000-0000-4000-8000-000000000042"
        runBlocking { db.titleDao().upsertAll(listOf(TitleEntity(id, 42, "MOVIE", "Provider film", 2026,
            null, emptyList(), null, null, null, 90, null, "WATCHLIST", null, null,
            "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"))) }
        show(); launchPicker(); deliver(); showImportReport()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("updated 1", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            assertEquals(1, db.titleDao().count()); assertEquals("WATCHED", db.titleDao().getById(id)!!.status)
            assertEquals(4.0, db.titleDao().getById(id)!!.rating!!, 0.0)
            val entry = db.outboxDao().getPending().single()
            assertEquals("provider_merge", entry.entityType)
            val body = JSONObject(entry.payloadJson)
            assertEquals(id, body.getString("titleId")); assertEquals(2, body.getJSONArray("viewings").length())
            assertEquals("letterboxd", body.getJSONObject("link").getString("provider"))
        }
    }
    @Test fun lateCsvPickerResultCannotImportIntoEndedAccount() {
        show(); launchPicker()
        compose.runOnIdle { active.value = false }; deliver(); compose.waitForIdle()
        assertEquals(0, admissions.get())
        runBlocking { assertEquals(0, db.titleDao().count()); assertTrue(db.outboxDao().getPending().isEmpty()) }
    }
}
