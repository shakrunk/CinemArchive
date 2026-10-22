package work.kumarfamilynet.cinemarchive.data

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

// Deterministic IDs for season/episode rows discovered from TMDB after a title was added (a
// newly announced season, episodes appended to an airing one). Mirrors the web app's
// apps/web/src/lib/catalogRowIds.ts byte for byte.
//
// Rows are unique per (title, season[, episode]) number, and the library command RPC rejects an
// insert whose natural key already exists under a different id. Two clients discovering the same
// new episode therefore must agree on its id: derive it (RFC 4122 v5, SHA-1) from the title id
// and the row's numbers instead of generating a random one.

internal fun catalogSeasonId(titleId: String, seasonNumber: Int): String = uuidV5(titleId, "season:$seasonNumber")

internal fun catalogEpisodeId(titleId: String, seasonNumber: Int, episodeNumber: Int): String =
    uuidV5(titleId, "episode:$seasonNumber:$episodeNumber")

private fun uuidV5(namespace: String, name: String): String {
    val ns = UUID.fromString(namespace)
    val digest = MessageDigest.getInstance("SHA-1").apply {
        update(ByteBuffer.allocate(16).putLong(ns.mostSignificantBits).putLong(ns.leastSignificantBits).array())
        update(name.toByteArray(Charsets.UTF_8))
    }.digest()
    digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
    digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
    val bytes = ByteBuffer.wrap(digest, 0, 16)
    return UUID(bytes.long, bytes.long).toString()
}
