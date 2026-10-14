package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val SHARED_PAGE_SIZE = 100

/**
 * The anonymous half of a share-link read: plain REST calls made with the public apikey only
 * (no signed-in session or bearer). Each call carries the share token explicitly; there are
 * no session settings, table reads, or owner-cache writes in this path.
 */
interface SharedLibraryTransport {
    fun rpc(name: String, paramsJson: String): String

    companion object {
        /** Publishable keys are not JWTs: leave Authorization absent on anonymous RPCs. */
        fun anonymous(client: SupabaseRestClient): SharedLibraryTransport =
            object : SharedLibraryTransport {
                override fun rpc(name: String, paramsJson: String): String = client.rpc(name, paramsJson, null)
            }
    }
}

/**
 * Share-link management (`shared_access_keys`), per-link/per-friend scopes (`share_scopes`) and the
 * read-only open-a-shared-library path. Ports `createSharedKey`/`revokeSharedKey`/`listSharedKeys`/
 * `getShareScope`/`setShareScope` from `apps/web/src/lib/auth.ts`, `fetchSharedLibrary` from
 * `apps/web/src/lib/db.ts`, and the cap/expiry rules from `Profile.tsx`'s `SharingSection`.
 *
 * Stateless: nothing is cached, and every management call is filtered by the *current* session's
 * user id on top of the owner-only RLS. Opening a shared library needs no session at all.
 */
class SharingRepository(
    private val client: SupabaseRestClient,
    private val sessionProvider: () -> SupabaseSession?,
    private val sharedTransport: SharedLibraryTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(
        client: SupabaseRestClient,
        sessionProvider: () -> SupabaseSession?,
    ) : this(client, sessionProvider, SharedLibraryTransport.anonymous(client))

    private fun requireSession(): SupabaseSession = sessionProvider() ?: error("Not signed in.")

    /** All of the signed-in user's links (active and revoked), newest first. */
    suspend fun listSharedKeys(): List<SharedAccessKey> = withContext(Dispatchers.IO) {
        val session = requireSession()
        fetchKeys(session)
    }

    private fun fetchKeys(session: SupabaseSession): List<SharedAccessKey> = parseSharedKeys(
        client.get("shared_access_keys", "select=*&user_id=eq.${session.userId}&order=created_at.desc", session.accessToken),
    )

    /**
     * Creates a link. Throws [ShareLinkLimitException] — before inserting — when the user already
     * has [SharingRules.MAX_ACTIVE_LINKS] active links (the database enforces no cap itself; this is
     * the web UI's rule). `user_id` is explicit and owner-fenced, independent of the server default.
     */
    suspend fun createSharedKey(label: String?, expiry: ShareExpiry = ShareExpiry.NEVER): SharedAccessKey =
        withContext(Dispatchers.IO) {
            val session = requireSession()
            if (SharingRules.atCap(fetchKeys(session))) {
                throw ShareLinkLimitException(
                    "Limit of ${SharingRules.MAX_ACTIVE_LINKS} active links reached — revoke one to create another.",
                )
            }
            val body = JSONObject().put("user_id", session.userId)
            label?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("label", it) }
            SharingRules.expiresAtIso(expiry, clock())?.let { body.put("expires_at", it) }
            // PostgREST returns the inserted row (with the server-generated token) as a one-element array.
            parseSharedKeys(client.insert("shared_access_keys", session.accessToken, body.toString())).first()
        }

    /** Soft-revokes (`is_active = false`); the row stays listed as revoked, like web. */
    suspend fun revokeSharedKey(id: String) {
        withContext(Dispatchers.IO) {
            val session = requireSession()
            val rows = JSONArray(
                client.patchWithFilter(
                    "shared_access_keys",
                    "id=eq.$id&user_id=eq.${session.userId}",
                    session.accessToken,
                    JSONObject().put("is_active", false).toString(),
                ),
            )
            check(rows.length() > 0) { "Share link not found." }
        }
    }

    /** The narrowing for [target], or null when no row exists — which means *unrestricted*. */
    suspend fun getShareScope(target: ShareScopeTarget): ShareScope? = withContext(Dispatchers.IO) {
        val session = requireSession()
        val (column, value) = scopeColumn(target)
        parseShareScope(
            client.get(
                "share_scopes",
                "select=allowed_genres,allowed_statuses&owner_user_id=eq.${session.userId}&$column=eq.$value&limit=1",
                session.accessToken,
            ),
        )
    }

    /**
     * Sets (or, with a null/unrestricted [scope], clears) the narrowing for [target]. "Unrestricted"
     * is always represented by *no row*, never an allow-everything row — so a null or all-null scope
     * deletes. Links upsert on `shared_key_id`; friends on `owner_user_id,friend_user_id` (the real
     * unique constraints).
     */
    suspend fun setShareScope(target: ShareScopeTarget, scope: ShareScope?) {
        withContext(Dispatchers.IO) {
            val session = requireSession()
            val (column, value) = scopeColumn(target)
            if (scope == null || scope.isUnrestricted) {
                client.delete("share_scopes", "owner_user_id=eq.${session.userId}&$column=eq.$value", session.accessToken)
                return@withContext
            }
            val body = JSONObject()
                .put("owner_user_id", session.userId)
                .put(column, value)
                .put("allowed_genres", scope.allowedGenres?.let { JSONArray(it) } ?: JSONObject.NULL)
                .put("allowed_statuses", scope.allowedStatuses?.let { JSONArray(it) } ?: JSONObject.NULL)
                .put("updated_at", java.time.Instant.ofEpochMilli(clock()).toString())
                .toString()
            val onConflict = if (target is ShareScopeTarget.Link) "shared_key_id" else "owner_user_id,friend_user_id"
            client.upsert("share_scopes", session.accessToken, body, onConflict)
        }
    }

    private fun scopeColumn(target: ShareScopeTarget): Pair<String, String> = when (target) {
        is ShareScopeTarget.Link -> "shared_key_id" to target.sharedKeyId
        is ShareScopeTarget.Friend -> "friend_user_id" to target.friendUserId
    }

    /** Fetch the complete scoped graph through a stateless RPC, without touching Room.
     * Offset advances by response row count, not the de-duplicated count. A valid empty
     * scope succeeds; any failed page fails the entire load rather than returning a subset.
     */
    suspend fun fetchSharedLibrary(token: String): SharedLibrary = withContext(Dispatchers.IO) {
        require(token.isNotBlank() && token.length <= 512) { "A valid share token is required." }
        var owner: String? = null
        var ledgerLayout: String? = null
        var offset = 0
        val titles = linkedMapOf<String, SharedLibraryTitle>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val body = try {
                sharedTransport.rpc("get_shared_library", JSONObject()
                    .put("p_token", token).put("p_offset", offset).put("p_limit", SHARED_PAGE_SIZE).toString())
            } catch (error: SupabaseHttpException) {
                if (error.postgresCode == "42501") throw SharedLinkUnavailableException()
                throw error
            }
            val page = JSONObject(body)
            val pageOwner = page.getString("ownerUserId")
            check(pageOwner.isNotBlank()) { "The shared library response has no owner." }
            check(page.has("ledgerLayout") && (page.isNull("ledgerLayout") || page.optJSONArray("ledgerLayout") != null)) {
                "The shared library response has an invalid Ledger layout."
            }
            if (owner == null) {
                owner = pageOwner
                ledgerLayout = page.optJSONArray("ledgerLayout")?.toString()
            } else {
                check(owner == pageOwner) { "The shared library owner changed while loading. Please retry." }
            }
            val rawRows = page.getJSONArray("titles")
            check(rawRows.length() <= SHARED_PAGE_SIZE) { "The shared library exceeded the requested page size." }
            val rows = parseSharedTitles(rawRows.toString(), pageOwner)
            val newIds = rows.count { it.id !in titles }
            rows.forEach { titles[it.id] = it }
            if (!page.getBoolean("hasMore")) break
            check(rows.isNotEmpty() && newIds > 0) { "The shared library returned a repeated or empty continuation page. Please retry." }
            offset = Math.addExact(offset, rows.size)
        }
        SharedLibrary(checkNotNull(owner), titles.values.toList(), ledgerLayout)
    }
}
