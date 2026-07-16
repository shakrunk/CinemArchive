package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetConfig
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetId
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetWidth

/**
 * Live verification of [SupabaseRemoteMutationWriter] and [SupabaseLedgerLayoutWriter] against
 * a real, non-production Supabase project (`cinemarchive-android-test`) — not a mock, not a
 * fake server. This is what finally answers docs/android-implementation-status.md's open
 * question: does last-write-wins conflict resolution and `user_prefs.ledger_layout` sync
 * actually work end-to-end? Two independent password sign-ins for the same pre-created test
 * user stand in for "two devices" (RLS is per-`auth.uid()`, not per-session, so one user with
 * two sessions is the correct simulation, not a shortcut).
 *
 * Requires four environment variables (never hardcoded, never committed):
 * `ANDROID_SUPABASE_TEST_URL`, `ANDROID_SUPABASE_TEST_ANON_KEY`,
 * `ANDROID_SUPABASE_TEST_EMAIL`, `ANDROID_SUPABASE_TEST_PASSWORD`. Skips (not fails) via
 * [assumeTrue] when they're absent, so this never breaks a build for a developer or CI run
 * without test-project credentials configured — see the class kdoc on
 * [SupabaseRemoteMutationWriter] for why obtaining *some* session here is a different concern
 * from the app's real passkey sign-in flow.
 *
 * Timestamps used here are all real-clock-relative (`Instant.now()` +/- an offset), not
 * arbitrary fixed dates — `schema.sql`'s `update_updated_at()` trigger unconditionally
 * overwrites `updated_at` to the *server's* `now()` on every successful update (matching
 * docs/android-sync-contract.md §2.3's "never the device's clock" rule), so a fixed past date
 * as an "incoming" value would always lose against a freshly-inserted row's real insert-time
 * timestamp — that was this test's first bug, caught by running it live rather than mocking
 * the server. [tmdbId] is randomized per run for the same reason a fixed one broke repeat
 * runs: `titles` has a `unique (user_id, tmdb_id, type)` constraint, so a second fixture
 * title with the same tmdb_id collides with the first run's leftover row.
 */
class SupabaseRemoteMutationWriterLiveTest {

    private val url = System.getenv("ANDROID_SUPABASE_TEST_URL")
    private val anonKey = System.getenv("ANDROID_SUPABASE_TEST_ANON_KEY")
    private val email = System.getenv("ANDROID_SUPABASE_TEST_EMAIL")
    private val password = System.getenv("ANDROID_SUPABASE_TEST_PASSWORD")

    private fun requireEnv() {
        assumeTrue(
            "Skipping live Supabase test — set ANDROID_SUPABASE_TEST_URL/ANON_KEY/EMAIL/PASSWORD to run it",
            url != null && anonKey != null && email != null && password != null,
        )
    }

    @Test
    fun `two sessions racing a title update resolve by last-write-wins`() = runTest {
        requireEnv()
        val client = SupabaseRestClient(url!!, anonKey!!)
        val deviceA = client.signInWithPassword(email!!, password!!)
        val deviceB = client.signInWithPassword(email, password)
        assertEquals("both sign-ins are the same underlying user", deviceA.userId, deviceB.userId)

        val titleId = createTestTitle(client, deviceA)
        val writerA = SupabaseRemoteMutationWriter(client) { deviceA }
        val writerB = SupabaseRemoteMutationWriter(client) { deviceB }

        // Device A's incoming timestamp must be after the row's real insert-time `now()` for
        // its write to be accepted; device B's must be *before* whatever the server actually
        // commits for device A's write (also a real `now()`, via the trigger) — see class
        // kdoc. A fixed past date would satisfy neither.
        val resultA = writerA.push(titleOutboxEntry(titleId, "watching", Instant.now().plusSeconds(60).toString()))
        assertTrue("device A's write (clearly-future incoming timestamp) should succeed", resultA is PushResult.Success)

        // Device B's incoming timestamp predates "now" by an hour — necessarily older than
        // whatever real server clock value device A's write just committed.
        val resultB = writerB.push(titleOutboxEntry(titleId, "dropped", Instant.now().minusSeconds(3600).toString()))
        assertTrue("device B's stale write must be reported as Conflict, not Success", resultB is PushResult.Conflict)
        val serverPayload = (resultB as PushResult.Conflict).serverPayload
        assertEquals("the server's winning row is device A's write, not device B's", "watching", serverPayload.getString("status"))

        // Confirm directly against the database, independent of the writer's own read-back.
        val current = JSONArray(client.get("titles", "id=eq.$titleId&select=status,updated_at", deviceA.accessToken))
        assertEquals("watching", current.getJSONObject(0).getString("status"))

        cleanupTestTitle(client, deviceA, titleId)
    }

    @Test
    fun `a strictly later write after a conflict succeeds normally`() = runTest {
        requireEnv()
        val client = SupabaseRestClient(url!!, anonKey!!)
        val session = client.signInWithPassword(email!!, password!!)
        val titleId = createTestTitle(client, session)
        val writer = SupabaseRemoteMutationWriter(client) { session }

        val first = writer.push(titleOutboxEntry(titleId, "watching", Instant.now().plusSeconds(60).toString()))
        assertTrue(first is PushResult.Success)
        val second = writer.push(titleOutboxEntry(titleId, "watched", Instant.now().plusSeconds(120).toString()))
        assertTrue("a later write after an earlier success must also succeed", second is PushResult.Success)

        val current = JSONArray(client.get("titles", "id=eq.$titleId&select=status", session.accessToken))
        assertEquals("watched", current.getJSONObject(0).getString("status"))

        cleanupTestTitle(client, session, titleId)
    }

    @Test
    fun `ledger layout upserts and reads back correctly`() = runTest {
        requireEnv()
        val client = SupabaseRestClient(url!!, anonKey!!)
        val session = client.signInWithPassword(email!!, password!!)
        val layoutWriter = SupabaseLedgerLayoutWriter(client)

        val widgets = listOf(
            LedgerWidgetConfig(id = "widget-decades-0", panel = LedgerWidgetId.DECADES, width = LedgerWidgetWidth.SM),
            LedgerWidgetConfig(id = "widget-genres-1", panel = LedgerWidgetId.GENRES, width = LedgerWidgetWidth.FULL),
        )
        layoutWriter.upsertLayout(session, widgets)

        val stored = JSONArray(
            client.get("user_prefs", "user_id=eq.${session.userId}&select=ledger_layout", session.accessToken),
        )
        val layoutJson = stored.getJSONObject(0).getJSONArray("ledger_layout")
        assertEquals(2, layoutJson.length())
        assertEquals("decades", layoutJson.getJSONObject(0).getString("panel"))
        assertEquals("sm", layoutJson.getJSONObject(0).getString("width"))
    }

    /** A fresh random tmdb_id per call — `titles` has a `unique (user_id, tmdb_id, type)`
     *  constraint, so a fixed value collides with any prior run's (or this run's other test
     *  method's) leftover fixture row, as a real 23505 error caught the first time this test
     *  used a hardcoded `tmdb_id = 1`. */
    private fun createTestTitle(client: SupabaseRestClient, session: SupabaseSession): String {
        val id = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("id", id)
            .put("user_id", session.userId)
            .put("tmdb_id", Random.nextInt(1_000_000, Int.MAX_VALUE))
            .put("type", "movie")
            .put("title", "Live sync spike fixture")
            .put("year", 2026)
            .put("status", "watchlist")
        client.upsert("titles", session.accessToken, body.toString())
        return id
    }

    private fun cleanupTestTitle(client: SupabaseRestClient, session: SupabaseSession, titleId: String) {
        // Best-effort: uses the same upsert path since the client has no delete() — leaving a
        // handful of fixture titles behind in the isolated test project is harmless, but we
        // at least reset status so repeated runs start from a known state. tmdb_id/type/year
        // aren't touched by this PATCH-shaped update (no unique-constraint fields sent).
        client.patchWithFilter(
            "titles",
            "id=eq.$titleId",
            session.accessToken,
            JSONObject().put("status", "watchlist").toString(),
        )
    }

    private fun titleOutboxEntry(titleId: String, status: String, updatedAt: String) = OutboxEntity(
        id = UUID.randomUUID().toString(),
        entityType = "title",
        entityId = titleId,
        operation = "update",
        payloadJson = JSONObject().put("id", titleId).put("status", status).put("updatedAt", updatedAt).toString(),
        createdAt = 0L,
    )
}
