package work.kumarfamilynet.cinemarchive.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class SupabaseSession(val accessToken: String, val userId: String)

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Minimal REST + Auth client for Supabase's PostgREST and GoTrue endpoints, built on OkHttp
 * — not the full Supabase Kotlin SDK — deliberately narrow (no realtime/storage/broader auth
 * surface) since the outbox's push semantics only need password sign-in, PATCH-with-filter
 * (the last-write-wins conditional update), GET, and POST/upsert. See
 * [SupabaseRemoteMutationWriter]'s kdoc for why a *session* here is a separate concern from
 * the app's real (passkey) sign-in flow.
 *
 * OkHttp, not `java.net.HttpURLConnection`, specifically because `HttpURLConnection` cannot
 * reliably send PATCH: it isn't in `setRequestMethod()`'s allow-list, and the usual
 * reflection workaround (setting the protected `method` field directly) proved unreliable
 * against a real HTTPS endpoint in practice — it produced a request the server rejected
 * differently than an equivalent `curl -X PATCH` call, for reasons that didn't resolve after
 * multiple JDK-internals-level fixes (JPMS `--add-opens`, `HttpsURLConnectionImpl` delegate
 * indirection). `java.net.http.HttpClient` (which supports PATCH natively) isn't an option
 * either — it doesn't exist in Android's SDK at all, at any API level. OkHttp is the
 * industry-standard answer to exactly this gap, not a broader networking-stack decision for
 * the app.
 */
class SupabaseRestClient(
    private val baseUrl: String,
    private val anonKey: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    /** Signs in with email/password (GoTrue's password grant). No refresh-token handling or
     *  persistence — this client is scoped to the outbox's push path, not a general session
     *  manager (see docs/android-implementation-status.md's Phase 3 Ledger section). */
    fun signInWithPassword(email: String, password: String): SupabaseSession {
        val body = JSONObject().put("email", email).put("password", password).toString()
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=password")
            .header("apikey", anonKey)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val json = JSONObject(execute(request))
        return SupabaseSession(
            accessToken = json.getString("access_token"),
            userId = json.getJSONObject("user").getString("id"),
        )
    }

    /** PATCH with a PostgREST filter query string (e.g. `"id=eq.<id>&updated_at=lt.<iso>"`) —
     *  the conditional-update mechanism the last-write-wins contract needs. Returns the
     *  updated rows as a raw JSON array string; an empty array means the filter matched
     *  nothing (the conflict case: the server's current row is already >= the incoming
     *  value). */
    fun patchWithFilter(table: String, filter: String, accessToken: String, bodyJson: String): String {
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/$table?$filter")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "return=representation")
            .patch(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    fun get(table: String, filter: String, accessToken: String): String {
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/$table?$filter")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return execute(request)
    }

    /** POST with upsert semantics (`on_conflict` + merge-duplicates) — the shape every
     *  append-only entity type needs per docs/android-sync-contract.md §4.2 (client-generated
     *  id, retries upsert rather than duplicate). */
    fun upsert(table: String, accessToken: String, bodyJson: String, onConflict: String = "id"): String {
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/$table?on_conflict=$onConflict")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "resolution=merge-duplicates,return=representation")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    private fun execute(request: Request): String {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            check(response.isSuccessful) { "${request.method} ${request.url} failed: HTTP ${response.code} $body" }
            return body
        }
    }
}
