package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/**
 * The real `RemoteMutationWriter`, hitting Supabase's PostgREST API directly via
 * [SupabaseRestClient]. [sessionProvider] is a seam: how the caller obtains a session
 * (passkey sign-in for real users; a test sign-in for verification) is entirely separate
 * from what this writer does with it — see docs/android-implementation-status.md's Phase 3
 * Ledger section for why that distinction matters, and why this class isn't wired into
 * [work.kumarfamilynet.cinemarchive.CinemArchiveApplication] yet (no real session exists in
 * the app until the passkey sign-in flow is built).
 *
 * Implements docs/android-sync-contract.md §4.2's contract: client-generated ids upsert
 * rather than duplicate for the four append-only entity types, and `title` updates are
 * conditional (`updated_at=lt.<incoming>`) so a losing write can never clobber a newer one —
 * last-write-wins is enforced by the database via the filter itself, not by client logic.
 */
class SupabaseRemoteMutationWriter(
    private val client: SupabaseRestClient,
    private val sessionProvider: () -> SupabaseSession,
) : RemoteMutationWriter {
    override suspend fun push(entry: OutboxEntity): PushResult {
        val payload = JSONObject(entry.payloadJson)
        return try {
            when (entry.entityType) {
                "title" -> pushTitleUpdate(payload)
                "episode_watch_event" -> upsertWatchEvent(payload)
                "episode_rating" -> upsertRating(payload)
                "episode_review" -> upsertReview(payload)
                "viewing" -> upsertViewing(payload)
                else -> PushResult.Retry("Unknown entity type ${entry.entityType}")
            }
        } catch (e: Exception) {
            PushResult.Retry(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The one conflict-capable write — see class kdoc. A 0-row PATCH result means the
     *  server's current `updated_at` is already >= ours, so we fetch and return it as the
     *  server-authoritative payload rather than treating this as a retryable failure. */
    private fun pushTitleUpdate(payload: JSONObject): PushResult {
        val session = sessionProvider()
        val id = payload.getString("id")
        val updatedAt = payload.getString("updatedAt")
        val body = JSONObject().put("status", payload.getString("status")).put("updated_at", updatedAt)
        val filter = "id=eq.$id&updated_at=lt.$updatedAt"
        val updated = JSONArray(client.patchWithFilter("titles", filter, session.accessToken, body.toString()))
        if (updated.length() > 0) return PushResult.Success

        val current = JSONArray(client.get("titles", "id=eq.$id&select=status,updated_at", session.accessToken))
        if (current.length() == 0) return PushResult.Retry("Title $id not found or not owned by this session")
        val currentRow = current.getJSONObject(0)
        return PushResult.Conflict(
            JSONObject()
                .put("id", id)
                .put("status", currentRow.getString("status"))
                .put("updatedAt", currentRow.getString("updated_at")),
        )
    }

    private fun upsertWatchEvent(payload: JSONObject): PushResult {
        val session = sessionProvider()
        val body = JSONObject()
            .put("id", payload.getString("id"))
            .put("episode_id", payload.getString("episodeId"))
            .put("user_id", session.userId)
            .put("watched_at", payload.opt("watchedAt").takeUnless { it == JSONObject.NULL })
        client.upsert("episode_watch_events", session.accessToken, body.toString())
        return PushResult.Success
    }

    private fun upsertRating(payload: JSONObject): PushResult {
        val session = sessionProvider()
        val body = JSONObject()
            .put("id", payload.getString("id"))
            .put("episode_id", payload.getString("episodeId"))
            .put("user_id", session.userId)
            .put("rating", payload.getDouble("rating"))
            .put("rated_at", payload.getString("ratedAt"))
        client.upsert("episode_ratings", session.accessToken, body.toString())
        return PushResult.Success
    }

    private fun upsertReview(payload: JSONObject): PushResult {
        val session = sessionProvider()
        val body = JSONObject()
            .put("id", payload.getString("id"))
            .put("episode_id", payload.getString("episodeId"))
            .put("user_id", session.userId)
            .put("review_text", payload.getString("reviewText"))
            .put("reviewed_at", payload.getString("reviewedAt"))
        client.upsert("episode_reviews", session.accessToken, body.toString())
        return PushResult.Success
    }

    private fun upsertViewing(payload: JSONObject): PushResult {
        val session = sessionProvider()
        val body = JSONObject()
            .put("id", payload.getString("id"))
            .put("title_id", payload.getString("titleId"))
            .put("user_id", session.userId)
            .put("viewed_at", payload.opt("date").takeUnless { it == JSONObject.NULL })
        client.upsert("viewings", session.accessToken, body.toString())
        return PushResult.Success
    }
}
