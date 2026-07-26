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
                "viewing" -> if (entry.operation == "update") patchViewing(payload) else upsertViewing(payload)
                "cinema_outing" -> upsertOuting(payload)
                else -> PushResult.Retry("Unknown entity type ${entry.entityType}")
            }
        } catch (e: Exception) {
            PushResult.Retry(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The one conflict-capable write — see class kdoc. A 0-row PATCH result means the
     *  server's current `updated_at` is already >= ours, so we fetch and return it as the
     *  server-authoritative payload rather than treating this as a retryable failure.
     *
     *  Sends only the columns the payload actually carries: `updateTitleStatus` and
     *  `updateTitleRating` each enqueue their own field alone, so assuming either is present
     *  would make the other's push throw and requeue forever. */
    private fun pushTitleUpdate(payload: JSONObject): PushResult {
        val session = sessionProvider()
        val id = payload.getString("id")
        val updatedAt = payload.getString("updatedAt")
        val body = JSONObject().put("updated_at", updatedAt)
        // payload.status is LibraryStatus.name (uppercase); titles.status is the lowercase
        // watch_status enum — same conversion the cinema_outing upsert below already does.
        if (payload.has("status")) body.put("status", payload.getString("status").lowercase())
        if (payload.has("rating")) body.putNullable("rating", payload, "rating")
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

    /**
     * In-place edit of an existing viewing — the post-show sheet's rating and notes actions,
     * which each enqueue only the one field they changed and no `titleId`. The append-only
     * [upsertViewing] path can't serve them: it requires a full row, so these payloads threw
     * on the missing key and requeued forever instead of ever reaching the server.
     *
     * Unconditional, unlike [pushTitleUpdate]: a viewing has no client-side `updatedAt` to
     * arbitrate on (the column exists server-side but is trigger-maintained), and the web app
     * has no competing writer for these two fields.
     */
    private fun patchViewing(payload: JSONObject): PushResult {
        val session = sessionProvider()
        val id = payload.getString("id")
        val body = JSONObject()
        if (payload.has("rating")) body.putNullable("rating", payload, "rating")
        if (payload.has("notes")) body.putNullable("notes", payload, "notes")
        if (body.length() == 0) return PushResult.Success
        client.patchWithFilter("viewings", "id=eq.$id", session.accessToken, body.toString())
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

    /** `cinema_outings` is always a full-row upsert, not the conditional last-write-wins
     *  update [pushTitleUpdate] does: Android is the only writer of its own outings today
     *  (no cross-device concurrency to arbitrate), so there's no conflict case to detect yet
     *  — see docs/superpowers/plans/2026-07-21-android-cinema-outings.md §3. */
    private fun upsertOuting(payload: JSONObject): PushResult {
        val session = sessionProvider()
        val body = JSONObject()
            .put("id", payload.getString("id"))
            .put("title_id", payload.getString("titleId"))
            .put("user_id", session.userId)
            .put("showtime", payload.getString("showtime"))
            .put("previews_minutes", payload.getInt("previewsMinutes"))
            .put("runtime_minutes", payload.getInt("runtimeMinutes"))
            .put("ends_at", payload.getString("endsAt"))
            .put("venue", payload.opt("venue").takeUnless { it == JSONObject.NULL })
            .put("companions", payload.getJSONArray("companions"))
            .put("format", payload.opt("format").takeUnless { it == JSONObject.NULL })
            .put("ticket_price", payload.opt("ticketPrice").takeUnless { it == JSONObject.NULL })
            .put("seat", payload.opt("seat").takeUnless { it == JSONObject.NULL })
            .put("booking_ref", payload.opt("bookingRef").takeUnless { it == JSONObject.NULL })
            .put("notes", payload.opt("notes").takeUnless { it == JSONObject.NULL })
            .put("status", payload.getString("status").lowercase())
            .put("previous_status", payload.opt("previousStatus").takeUnless { it == JSONObject.NULL })
            .put("completed_viewing_id", payload.opt("completedViewingId").takeUnless { it == JSONObject.NULL })
            .put("follow_up_dismissed_at", payload.opt("followUpDismissedAt").takeUnless { it == JSONObject.NULL })
            .put("created_at", payload.getString("createdAt"))
            .put("updated_at", payload.getString("updatedAt"))
        client.upsert("cinema_outings", session.accessToken, body.toString())
        return PushResult.Success
    }
}

/** Copies [key] from [source] under a (usually snake_case) [column], preserving an explicit
 *  JSON null. `JSONObject.put(key, null)` would drop the key entirely, which PostgREST reads
 *  as "leave this column alone" rather than "set it to null". */
private fun JSONObject.putNullable(column: String, source: JSONObject, key: String): JSONObject =
    put(column, source.opt(key).takeUnless { it == null || it == JSONObject.NULL } ?: JSONObject.NULL)
