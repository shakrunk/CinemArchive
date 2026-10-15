package work.kumarfamilynet.cinemarchive.data

import java.io.IOException
import org.json.JSONObject

/** Shown when the request never got a usable answer from the network. */
const val OFFLINE_MESSAGE = "Can't reach CinemArchive. Check your connection and try again."

private const val SESSION_EXPIRED_MESSAGE = "Your session has expired. Sign in again to continue."
private const val SERVER_TROUBLE_MESSAGE = "CinemArchive is having trouble right now. Try again in a moment."
private const val RATE_LIMITED_MESSAGE = "Too many requests. Wait a moment and try again."
private const val BAD_RESPONSE_MESSAGE = "Got an unexpected response from the server. Try again in a moment."

/** Postgres SQLSTATE for a deliberate `raise exception` — the only server `message` authored for people. */
private const val POSTGRES_RAISE_EXCEPTION = "P0001"

/**
 * Turns any failure into text safe to show a person. Raw exception messages (socket errors like
 * "Software caused connection abort", Postgres text, URLs embedded in [SupabaseHttpException]
 * messages) are never surfaced; the cause is classified instead and [fallback] describes the
 * action that failed when nothing more specific is known. Messages authored by this app on
 * [IllegalStateException]/[IllegalArgumentException] (e.g. "Not signed in.") are kept.
 */
fun Throwable.userMessage(fallback: String): String = when (this) {
    is SupabaseHttpException -> httpMessage(fallback)
    is IOException -> OFFLINE_MESSAGE
    is org.json.JSONException -> BAD_RESPONSE_MESSAGE
    is IllegalStateException, is IllegalArgumentException ->
        message?.takeIf { it.isNotBlank() && looksAuthoredForPeople(it) } ?: fallback
    else -> fallback
}

private fun SupabaseHttpException.httpMessage(fallback: String): String {
    if (postgresCode == POSTGRES_RAISE_EXCEPTION) {
        serverMessage()?.let { return it }
    }
    return when {
        status == 401 -> SESSION_EXPIRED_MESSAGE
        status == 408 || status == 502 || status == 503 || status == 504 -> OFFLINE_MESSAGE
        status == 429 -> RATE_LIMITED_MESSAGE
        status >= 500 -> SERVER_TROUBLE_MESSAGE
        else -> fallback
    }
}

private fun SupabaseHttpException.serverMessage(): String? =
    runCatching { JSONObject(responseBody).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }

/** Guards against developer-facing text (HTTP verbs, URLs) leaking through app-authored exceptions. */
private fun looksAuthoredForPeople(message: String): Boolean =
    !message.contains("http", ignoreCase = true) &&
        listOf("GET ", "POST ", "PATCH ", "PUT ", "DELETE ").none { message.startsWith(it) }

/**
 * Call first in a broad `catch (e: Exception)` inside a coroutine: cancellation (leaving a screen,
 * a superseded request) is control flow, not a failure to report, and must keep propagating.
 */
fun Throwable.rethrowIfCancelled() {
    if (this is kotlinx.coroutines.CancellationException) throw this
}
