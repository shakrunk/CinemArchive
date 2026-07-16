package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
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

        val earlier = "2026-01-01T00:00:00.000Z"
        val later = "2026-01-01T00:00:05.000Z"

        // Device A writes first with the later timestamp.
        val resultA = writerA.push(titleOutboxEntry(titleId, "watching", later))
        assertTrue("device A's write (later timestamp) should succeed", resultA is PushResult.Success)

        // Device B's write is now stale relative to what's stored — must lose, not clobber.
        val resultB = writerB.push(titleOutboxEntry(titleId, "dropped", earlier))
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

        val first = writer.push(titleOutboxEntry(titleId, "watching", "2026-01-02T00:00:00.000Z"))
        assertTrue(first is PushResult.Success)
        val second = writer.push(titleOutboxEntry(titleId, "watched", "2026-01-02T00:00:10.000Z"))
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

    private fun createTestTitle(client: SupabaseRestClient, session: SupabaseSession): String {
        val id = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("id", id)
            .put("user_id", session.userId)
            .put("tmdb_id", 1)
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
        // at least reset status so repeated runs start from a known state.
        client.upsert(
            "titles",
            session.accessToken,
            JSONObject().put("id", titleId).put("user_id", session.userId).put("tmdb_id", 1)
                .put("type", "movie").put("title", "Live sync spike fixture").put("year", 2026)
                .put("status", "watchlist").toString(),
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
