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
                "title" -> if (entry.operation == "insert") insertTitle(payload) else pushTitleUpdate(payload)
                "episode_watch_event" -> upsertWatchEvent(payload)
                "episode_rating" -> upsertRating(payload)
                "episode_review" -> upsertReview(payload)
                "episode_metadata" -> patchEpisodeMetadata(payload)
                "viewing" -> if (entry.operation == "update") patchViewing(payload) else upsertViewing(payload)
                "cinema_outing" -> upsertOuting(payload)
                else -> PushResult.Retry("Unknown entity type ${entry.entityType}")
            }
        } catch (e: Exception) {
            PushResult.Retry(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * A brand-new title and everything hanging off it, inserted parent-first so each child's
     * foreign key already resolves: title, then seasons/episodes/cast/crew/viewing.
     *
     * Every statement is an id-keyed upsert, which is what makes a partial failure safe to
     * retry: the outbox re-pushes the whole entry (see `LibraryRepository.addTitle` for why
     * it's one entry), and any insert that already landed simply merges onto itself. The one
     * genuinely unrecoverable case is a duplicate `unique_user_tmdb` — that's guarded before
     * the write, in `addTitle`.
     */
    private fun insertTitle(payload: JSONObject): PushResult {
        val session = sessionProvider()
        val titleId = payload.getString("id")
        val userId = session.userId
        val title = JSONObject()
            .put("id", titleId)
            .put("user_id", userId)
            .put("tmdb_id", payload.getInt("tmdbId"))
            // Postgres's media_type/watch_status enums are lowercase; Room stores the
            // MediaType.name/LibraryStatus.name spelling — same conversion every other
            // boundary crossing in this file applies.
            .put("type", payload.getString("type").lowercase())
            .put("title", payload.getString("title"))
            .put("year", payload.getInt("year"))
            .putNullable("release_date", payload, "releaseDate")
            .putNullable("director", payload, "director")
            .put("genres", payload.getJSONArray("genres"))
            .putNullable("poster_url", payload, "posterUrl")
            .putNullable("backdrop_url", payload, "backdropUrl")
            .putNullable("synopsis", payload, "synopsis")
            .putNullable("runtime", payload, "runtime")
            .putNullable("network", payload, "network")
            .put("status", payload.getString("status").lowercase())
            .putNullable("rating", payload, "rating")
            .putNullable("notes", payload, "notes")
            .putNullable("original_language", payload, "originalLanguage")
            .putNullable("content_rating", payload, "contentRating")
            .putNullable("imdb_id", payload, "imdbId")
            .putNullable("imdb_rating", payload, "imdbRating")
            .putNullable("rt_score", payload, "rtScore")
            .putNullable("metacritic_score", payload, "metacriticScore")
            .put("studios", payload.getJSONArray("studios"))
            .putNullable("collection_id", payload, "collectionId")
            .putNullable("collection_name", payload, "collectionName")
            .put("added_at", payload.getString("addedAt"))
            .put("updated_at", payload.getString("updatedAt"))
        client.upsert("titles", session.accessToken, title.toString())

        payload.rows("seasons") { season ->
            JSONObject()
                .put("id", season.getString("id"))
                .put("title_id", titleId)
                .put("user_id", userId)
                .put("season_number", season.getInt("seasonNumber"))
                .put("episode_count", season.getInt("episodeCount"))
                .put("episodes_watched", season.getInt("episodesWatched"))
                .putNullable("air_year", season, "airYear")
        }?.let { client.upsert("seasons", session.accessToken, it.toString()) }

        payload.rows("episodes") { episode ->
            JSONObject()
                .put("id", episode.getString("id"))
                .put("title_id", titleId)
                .put("user_id", userId)
                .put("season_number", episode.getInt("seasonNumber"))
                .put("episode_number", episode.getInt("episodeNumber"))
                .putNullable("episode_name", episode, "episodeName")
                .putNullable("air_date", episode, "airDate")
                .putNullable("runtime", episode, "runtime")
                .putNullable("synopsis", episode, "synopsis")
                .putNullable("still_url", episode, "stillUrl")
        }?.let { client.upsert("episodes", session.accessToken, it.toString()) }

        payload.rows("cast") { member ->
            JSONObject()
                .put("id", member.getString("id"))
                .put("title_id", titleId)
                .put("user_id", userId)
                .put("tmdb_person_id", member.getInt("tmdbPersonId"))
                .put("name", member.getString("name"))
                .putNullable("character_name", member, "characterName")
                .put("cast_order", member.getInt("castOrder"))
        }?.let { client.upsert("title_cast", session.accessToken, it.toString()) }

        payload.rows("crew") { member ->
            JSONObject()
                .put("id", member.getString("id"))
                .put("title_id", titleId)
                .put("user_id", userId)
                .put("tmdb_person_id", member.getInt("tmdbPersonId"))
                .put("name", member.getString("name"))
                .put("job", member.getString("job"))
                .putNullable("department", member, "department")
        }?.let { client.upsert("title_crew", session.accessToken, it.toString()) }

        payload.optJSONObject("viewing")?.let { viewing ->
            val body = JSONObject()
                .put("id", viewing.getString("id"))
                .put("title_id", titleId)
                .put("user_id", userId)
                .putNullable("viewed_at", viewing, "date")
                .putNullable("rating", viewing, "rating")
                .putNullable("notes", viewing, "notes")
            client.upsert("viewings", session.accessToken, body.toString())
        }
        return PushResult.Success
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

    /**
     * Partial update for the TMDB fields [LibraryRepository.backfillEpisodeMetadata] fetches —
     * same unconditional PATCH-by-id shape as [patchViewing]. Unconditional, not last-write-wins
     * like [pushTitleUpdate]: synopsis/stillUrl have no other client-side writer to race
     * against, so there's nothing to arbitrate — a losing write here just means the next open
     * asks TMDB again.
     */
    private fun patchEpisodeMetadata(payload: JSONObject): PushResult {
        val session = sessionProvider()
        val id = payload.getString("id")
        val body = JSONObject()
            .putNullable("synopsis", payload, "synopsis")
            .putNullable("still_url", payload, "stillUrl")
        client.patchWithFilter("episodes", "id=eq.$id", session.accessToken, body.toString())
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
            .put("auditorium", payload.opt("auditorium").takeUnless { it == JSONObject.NULL })
            .put("seat_row", payload.opt("seatRow").takeUnless { it == JSONObject.NULL })
            .put("seats", payload.getJSONArray("seats"))
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

/** Maps a payload's nested array into a PostgREST bulk-insert body, or null when there's
 *  nothing to send — an empty array would be a pointless round trip. */
private fun JSONObject.rows(key: String, map: (JSONObject) -> JSONObject): JSONArray? {
    val source = optJSONArray(key) ?: return null
    if (source.length() == 0) return null
    val out = JSONArray()
    for (i in 0 until source.length()) out.put(map(source.getJSONObject(i)))
    return out
}
