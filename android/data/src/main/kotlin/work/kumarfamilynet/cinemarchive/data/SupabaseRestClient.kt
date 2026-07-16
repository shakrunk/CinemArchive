package work.kumarfamilynet.cinemarchive.data

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class SupabaseSession(val accessToken: String, val userId: String)

/**
 * Minimal REST + Auth client for Supabase's PostgREST and GoTrue endpoints, built on the
 * platform's `HttpURLConnection` rather than the full Supabase Kotlin SDK — deliberately
 * narrow (no realtime/storage/broader auth surface) since the outbox's push semantics only
 * need password sign-in, PATCH-with-filter (the last-write-wins conditional update), GET,
 * and POST/upsert. See [SupabaseRemoteMutationWriter]'s kdoc for why a *session* here is a
 * separate concern from the app's real (passkey) sign-in flow.
 */
class SupabaseRestClient(
    private val baseUrl: String,
    private val anonKey: String,
) {
    /** Signs in with email/password (GoTrue's password grant). No refresh-token handling or
     *  persistence — this client is scoped to the outbox's push path, not a general session
     *  manager (see docs/android-implementation-status.md's Phase 3 Ledger section). */
    fun signInWithPassword(email: String, password: String): SupabaseSession {
        val body = JSONObject().put("email", email).put("password", password).toString()
        val response = request("POST", "$baseUrl/auth/v1/token?grant_type=password", accessToken = null, body, prefer = null)
        val json = JSONObject(response)
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
    fun patchWithFilter(table: String, filter: String, accessToken: String, bodyJson: String): String =
        request("PATCH", "$baseUrl/rest/v1/$table?$filter", accessToken, bodyJson, prefer = "return=representation")

    fun get(table: String, filter: String, accessToken: String): String =
        request("GET", "$baseUrl/rest/v1/$table?$filter", accessToken, body = null, prefer = null)

    /** POST with upsert semantics (`on_conflict` + merge-duplicates) — the shape every
     *  append-only entity type needs per docs/android-sync-contract.md §4.2 (client-generated
     *  id, retries upsert rather than duplicate). */
    fun upsert(table: String, accessToken: String, bodyJson: String, onConflict: String = "id"): String =
        request(
            "POST",
            "$baseUrl/rest/v1/$table?on_conflict=$onConflict",
            accessToken,
            bodyJson,
            prefer = "resolution=merge-duplicates,return=representation",
        )

    private fun request(method: String, url: String, accessToken: String?, body: String?, prefer: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            if (method == "PATCH") {
                // HttpURLConnection.setRequestMethod() only allow-lists GET/POST/HEAD/
                // OPTIONS/PUT/DELETE/TRACE — PATCH isn't in that list on either the JDK or
                // Android's implementation. `method` is a plain protected field on the
                // standard java.net.HttpURLConnection class itself (not an
                // implementation-specific internal), so setting it directly via reflection
                // is the well-established portable workaround, not a hack tied to one JVM.
                val methodField = HttpURLConnection::class.java.getDeclaredField("method")
                methodField.isAccessible = true
                methodField.set(connection, "PATCH")
            } else {
                connection.requestMethod = method
            }
            connection.setRequestProperty("apikey", anonKey)
            accessToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.setRequestProperty("Content-Type", "application/json")
            prefer?.let { connection.setRequestProperty("Prefer", it) }
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            if (body != null || method == "POST" || method == "PATCH") {
                connection.doOutput = true
                connection.outputStream.use { it.write((body ?: "{}").toByteArray(StandardCharsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            check(status in 200..299) { "$method $url failed: HTTP $status $responseBody" }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }
}
