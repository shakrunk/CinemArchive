package work.kumarfamilynet.cinemarchive

import java.net.URI
import java.net.URLDecoder
import work.kumarfamilynet.cinemarchive.data.AuthIdentity

/** Owner navigation only. Share/auth links are dispatched before this parser. */
internal data class IncomingNavigation(
    val view: String?,
    val titleId: String?,
    val listId: String?,
    val add: Boolean,
) {
    companion object {
        private val views = setOf("discover", "library", "upnext", "ledger", "lists", "profile", "friends", "calendar")

        fun parse(input: String): IncomingNavigation? = runCatching {
            val uri = URI(input)
            if (uri.scheme != "https" || uri.host != "cinemarchive.kumarfamilynet.work" ||
                uri.userInfo != null || uri.port !in setOf(-1, 443)) return@runCatching null
            // URLSearchParams.get uses the first occurrence and decodes both keys and values.
            val params = linkedMapOf<String, String>()
            uri.rawQuery.orEmpty().split('&').forEach { pair ->
                val parts = pair.split('=', limit = 2)
                params.putIfAbsent(URLDecoder.decode(parts[0], "UTF-8"),
                    URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8"))
            }
            // Never reinterpret an external archive as the signed-in owner's archive.
            if (!params["share"].isNullOrEmpty() || !params["friend"].isNullOrEmpty()) return@runCatching null
            if (params.keys.none { it in setOf("view", "title", "list", "add") }) return@runCatching null
            IncomingNavigation(params["view"]?.takeIf { it in views },
                params["title"]?.takeIf(String::isNotEmpty),
                params["list"]?.takeIf(String::isNotEmpty), params["add"] == "1")
        }.getOrNull()
    }
}

/** A signed-out link is deferred; a signed-in link cannot follow an account/generation swap. */
internal data class PendingNavigation(
    val destination: IncomingNavigation,
    val identity: AuthIdentity?,
    val sequence: Long,
) {
    fun canOpen(current: AuthIdentity): Boolean = identity == null || identity == current
}
