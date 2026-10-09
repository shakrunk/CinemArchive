package work.kumarfamilynet.cinemarchive.data

import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity

/**
 * Pure mapping of ONE already-admitted `LibraryBackupCodec.CopyPlan` title row to
 * (a) the Room draft and (b) the exact canonical `apply_library_command` `titles` insert operation.
 *
 * Foundation only (docs/android-contracts/library-backup.md → "Restore mapping"): no persistence,
 * no operation ids, no chunking and no restore activation. Identity (`row.id`) is taken from the plan
 * as-is and the admission timestamp is supplied by the caller and frozen, so the same inputs always
 * produce the same bytes. A value the target cannot hold with its original meaning is NEVER coerced:
 * it is left out of BOTH outputs and reported with a path (the archive still holds it).
 */
object BackupRestoreMapper {
    enum class Kind {
        /** Valid archive data that a later slice maps (seasons, viewings, cast, crew, outings, lists). */
        DEFERRED,
        /** Opaque or unrecognised data that is never written to the library. */
        UNMAPPED,
        /** The target cannot represent the value with its original meaning; value left out. */
        LOSSY,
        /** The title cannot be delivered at all; no entity and no operation are produced. */
        REJECTED,
    }

    data class Issue(val path: String, val kind: Kind, val message: String)

    class TitleMapping(
        val entity: TitleEntity?,
        /** `{table:"titles",action:"insert",key:{id},values:{…}}` or null when [issues] contains a REJECTED. */
        operation: JSONObject?,
        val issues: List<Issue>,
    ) {
        private val frozenOperation = operation?.let { detached(it) as JSONObject }
        /** Immutable canonical UTF-8 JSON source for future queue admission; do not use JSONObject.toString(). */
        val operationJson: String? = frozenOperation?.let(LibraryBackupCodec::encodeJsonValue)
        /** Detached inspection copy; mutations cannot alter the canonical operation or another caller's copy. */
        val operation: JSONObject? get() = frozenOperation?.let { detached(it) as JSONObject }
    }

    class PlanMapping(val titles: List<TitleMapping>, val issues: List<Issue>, val copyReport: LibraryBackupCodec.Report)

    private val DEFERRED_KEYS = setOf("seasons", "viewings", "cast", "crew")
    private val MAPPED_KEYS = setOf(
        "id", "tmdbId", "type", "title", "year", "director", "genres", "posterUrl", "backdropUrl", "synopsis",
        "runtime", "network", "status", "rating", "notes", "tags", "addedAt", "releaseDate", "originalLanguage",
        "contentRating", "imdbId", "rtUrl", "customWatchUrl", "inHomeCollection", "physicalMedia", "collectionId",
        "collectionName", "imdbRating", "rtScore", "metacriticScore", "awardsCount", "bechdelOutcome",
        "bechdelScore", "studios",
    )

    /** Maps every title of [plan] and reports the plan's deferred outings, lists and local-only data. */
    fun mapPlan(document: LibraryBackupCodec.BackupDocument, plan: LibraryBackupCodec.CopyPlan, admittedAt: String): PlanMapping {
        val doc = ArrayList<Issue>()
        if (plan.outings.isNotEmpty()) doc.add(Issue("outings", Kind.DEFERRED, "${plan.outings.size} outing(s) are not restored by this mapper; completed outings need a reviewed restore contract."))
        if (plan.lists.isNotEmpty()) doc.add(Issue("lists", Kind.DEFERRED, "${plan.lists.size} list(s) are not restored by this mapper."))
        document.localOnly?.let { local ->
            keysOf(local).forEach { doc.add(Issue("localOnly.$it", Kind.UNMAPPED, "Private archive data remains in the original document; not restored by this mapper.")) }
        }
        keysOf(document.extra).forEach { doc.add(Issue(it, Kind.UNMAPPED, "Unknown top-level archive data remains in the original document; not restored by this mapper.")) }
        return PlanMapping(plan.titles.mapIndexed { i, row -> mapTitle(row, i, admittedAt) }, doc, plan.report)
    }

    /**
     * @param admittedAt frozen admission instant (ISO-8601 with offset/Z); becomes `updatedAt` and the
     *   fallback `addedAt`. Never read from a clock here.
     */
    fun mapTitle(row: JSONObject, index: Int, admittedAt: String): TitleMapping {
        val base = "titles[$index]"
        val issues = ArrayList<Issue>()
        fun reject(path: String, message: String): TitleMapping {
            issues.add(Issue(path, Kind.REJECTED, message))
            return TitleMapping(null, null, issues)
        }
        require(parseInstant(admittedAt)) { "admittedAt must be an ISO-8601 instant with offset" }

        val id = row.opt("id") as? String
        if (id == null || !isUuid(id)) return reject("$base.id", "Plan identity is not a UUID; the server key is a uuid.")
        val tmdbId = intOf(row.opt("tmdbId"))?.takeIf { it > 0 }
            ?: return reject("$base.tmdbId", "tmdbId must be a positive integer.")
        val type = (row.opt("type") as? String)?.takeIf { it == "movie" || it == "tv" }
            ?: return reject("$base.type", "type must be movie or tv.")
        val status = (row.opt("status") as? String)?.takeIf { it in STATUSES }
            ?: return reject("$base.status", "status must be watched, watchlist, watching or dropped; it is never defaulted.")
        val title = row.opt("title") as? String
        if (title == null || title.isBlank() || '\u0000' in title) return reject("$base.title", "title must be non-blank text without NUL.")
        // titles.year is NOT NULL on the server: inventing a year would falsify the record.
        val year = intOf(row.opt("year"))
            ?: return reject("$base.year", "An integer year is required by the server and is not invented; zero means unknown.")

        val values = JSONObject()
        values.put("tmdb_id", tmdbId).put("type", type).put("title", title).put("year", year).put("status", status)

        fun text(key: String, column: String): String? {
            val v = row.opt(key)
            if (isNull(v)) return null
            if (v !is String || '\u0000' in v) { lossy(issues, "$base.$key", "Expected text without NUL; left unmapped."); return null }
            values.put(column, v)
            return v
        }
        fun integer(key: String, column: String, range: IntRange): Int? {
            val v = row.opt(key)
            if (isNull(v)) return null
            val n = intOf(v)
            if (n == null || n !in range) { lossy(issues, "$base.$key", "Expected an integer in $range; left unmapped."); return null }
            values.put(column, n)
            return n
        }
        fun decimal1(key: String, column: String, min: Double, max: Double): Double? {
            val v = row.opt(key)
            if (isNull(v)) return null
            val d = oneDecimal(v)
            if (d == null || d.toDouble() < min || d.toDouble() > max) {
                lossy(issues, "$base.$key", "Expected a number in $min..$max with at most one decimal (numeric(3,1)); left unmapped.")
                return null
            }
            values.put(column, d)
            return d.toDouble()
        }
        fun strings(key: String, column: String): List<String> {
            val v = row.opt(key)
            if (isNull(v)) return emptyList()
            val list = ArrayList<String>()
            if (v is JSONArray) for (i in 0 until v.length()) {
                val s = v.opt(i) as? String
                if (s == null || '\u0000' in s) { lossy(issues, "$base.$key", "Expected an array of text; left unmapped."); return emptyList() }
                list.add(s)
            } else { lossy(issues, "$base.$key", "Expected an array of text; left unmapped."); return emptyList() }
            values.put(column, JSONArray(list))
            return list
        }

        val director = text("director", "director")
        val genres = strings("genres", "genres")
        val posterUrl = text("posterUrl", "poster_url")
        val backdropUrl = text("backdropUrl", "backdrop_url")
        val synopsis = text("synopsis", "synopsis")
        val runtime = integer("runtime", "runtime", 0..Int.MAX_VALUE)
        val network = text("network", "network")
        val rating = decimal1("rating", "rating", 0.0, 5.0)
        val notes = text("notes", "notes")
        val tags = strings("tags", "tags")
        val imdbRating = decimal1("imdbRating", "imdb_rating", 0.0, 10.0)
        val rtScore = integer("rtScore", "rt_score", 0..100)
        val metacriticScore = integer("metacriticScore", "metacritic_score", 0..100)
        val studios = strings("studios", "studios")
        val releaseDate = row.opt("releaseDate").let { v ->
            if (isNull(v)) null
            else if (v is String && validDate(v)) { values.put("release_date", v); v }
            else { lossy(issues, "$base.releaseDate", "Expected a YYYY-MM-DD date; left unmapped."); null }
        }
        val originalLanguage = text("originalLanguage", "original_language")
        val contentRating = text("contentRating", "content_rating")
        val imdbId = text("imdbId", "imdb_id")
        val rtUrl = text("rtUrl", "rt_url")
        val awardsCount = integer("awardsCount", "awards_count", 0..Int.MAX_VALUE)
        val bechdelOutcome = row.opt("bechdelOutcome").let { v ->
            if (isNull(v)) null
            else if (v == "pass" || v == "fail") { values.put("bechdel_outcome", v); v as String }
            else { lossy(issues, "$base.bechdelOutcome", "Expected pass or fail; left unmapped."); null }
        }
        val bechdelScore = text("bechdelScore", "bechdel_score")
        val customWatchUrl = text("customWatchUrl", "custom_watch_url")
        val collectionId = integer("collectionId", "collection_id", Int.MIN_VALUE..Int.MAX_VALUE)
        val collectionName = text("collectionName", "collection_name")

        // Server column is NOT NULL default false, so absent/null and false are the same stored fact.
        val inHome = row.opt("inHomeCollection").let { v ->
            when {
                isNull(v) -> false
                v is Boolean -> { values.put("in_home_collection", v); v }
                else -> { lossy(issues, "$base.inHomeCollection", "Expected a boolean; left unmapped."); false }
            }
        }

        // Complete array kept verbatim (including element fields this client does not know).
        var physicalMediaJson: String? = null
        val media = row.opt("physicalMedia")
        if (!isNull(media)) {
            if (media is JSONArray) {
                val invalidText = invalidJsonText(media, "$base.physicalMedia")
                if (invalidText != null) {
                    lossy(issues, invalidText, "NUL or an unpaired Unicode surrogate cannot be stored in server JSON; physicalMedia left unmapped and retained in the archive.")
                } else {
                    val copy = detached(media) as JSONArray
                    for (i in 0 until copy.length()) {
                        if (copy.opt(i) !is JSONObject) issues.add(Issue("$base.physicalMedia[$i]", Kind.UNMAPPED, "Not an object; kept verbatim, not interpreted."))
                    }
                    values.put("physical_media", copy)
                    physicalMediaJson = LibraryBackupCodec.encodeJsonValue(copy)
                }
            } else lossy(issues, "$base.physicalMedia", "Expected an array; left unmapped.")
        }

        val addedAtRaw = row.opt("addedAt")
        val addedAt = if (addedAtRaw is String && parseInstant(addedAtRaw)) addedAtRaw else {
            val why = if (isNull(addedAtRaw)) "Missing" else "Not an ISO-8601 instant with offset (date-only is not reinterpreted in a device or server timezone)"
            lossy(issues, "$base.addedAt", "$why; the frozen admission time is used instead.")
            admittedAt
        }
        values.put("added_at", addedAt)

        for (key in keysOf(row)) {
            when {
                key in MAPPED_KEYS -> Unit
                key in DEFERRED_KEYS -> {
                    val v = row.opt(key)
                    val n = (v as? JSONArray)?.length()
                    if (!isNull(v) && n != 0) {
                        issues.add(Issue("$base.$key", Kind.DEFERRED, if (n != null) "$n item(s) are not restored by this mapper." else "Not restored by this mapper."))
                    }
                }
                key == "ext" -> issues.add(Issue("$base.ext", Kind.UNMAPPED, "Opaque archive data stays in the archive; it has no authority and no storage."))
                else -> issues.add(Issue("$base.$key", Kind.UNMAPPED, "Unknown field is not written to the library."))
            }
        }

        val key = JSONObject().put("id", id)
        val operation = JSONObject().put("table", "titles").put("action", "insert").put("key", key).put("values", values)
        val entity = TitleEntity(
            id = id, tmdbId = tmdbId, type = type.uppercase(), title = title, year = year, director = director,
            genres = genres, posterUrl = posterUrl, backdropUrl = backdropUrl, synopsis = synopsis, runtime = runtime,
            network = network, status = status.uppercase(), rating = rating, notes = notes, addedAt = addedAt,
            updatedAt = admittedAt, imdbRating = imdbRating, originalLanguage = originalLanguage,
            releaseDate = releaseDate, tags = tags, studios = studios, collectionId = collectionId,
            collectionName = collectionName, contentRating = contentRating, imdbId = imdbId, rtUrl = rtUrl,
            rtScore = rtScore, metacriticScore = metacriticScore, customWatchUrl = customWatchUrl,
            inHomeCollection = inHome, physicalMediaJson = physicalMediaJson, awardsCount = awardsCount,
            bechdelOutcome = bechdelOutcome, bechdelScore = bechdelScore,
        )
        return TitleMapping(entity, operation, issues)
    }

    private val STATUSES = setOf("watched", "watchlist", "watching", "dropped")

    /** PostgreSQL jsonb validates text in both keys and values, including opaque nested data. */
    private fun invalidJsonText(value: Any?, path: String): String? = when (value) {
        is String -> path.takeIf { !validJsonText(value) }
        is JSONObject -> keysOf(value).firstNotNullOfOrNull { key ->
            if (!validJsonText(key)) "$path[object key]" else invalidJsonText(value.get(key), "$path.$key")
        }
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { invalidJsonText(value.get(it), "$path[$it]") }
        else -> null
    }

    private fun validJsonText(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char == '\u0000' || char.isLowSurrogate()) return false
            if (char.isHighSurrogate() && (index == value.length || !value[index++].isLowSurrogate())) return false
        }
        return true
    }

    /** Clone containers, retaining immutable scalar objects (including exact BigInteger/BigDecimal). */
    private fun detached(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { copy -> keysOf(value).forEach { copy.put(it, detached(value.get(it))) } }
        is JSONArray -> JSONArray().also { copy -> for (i in 0 until value.length()) copy.put(detached(value.get(i))) }
        else -> value
    }

    private fun lossy(issues: MutableList<Issue>, path: String, message: String) { issues.add(Issue(path, Kind.LOSSY, message)) }

    private fun isNull(v: Any?) = v == null || v == JSONObject.NULL

    private fun keysOf(o: JSONObject): List<String> = o.keys().asSequence().toList().sorted()

    private fun isUuid(s: String) = runCatching { UUID.fromString(s).toString() == s.lowercase() }.getOrDefault(false)

    private fun parseInstant(s: String) = runCatching { OffsetDateTime.parse(s) }.isSuccess

    private fun validDate(s: String) = s.length == 10 && runCatching { LocalDate.parse(s) }.isSuccess

    /** Integer-valued JSON number (no fraction or exponent token) within Int, else null. */
    private fun intOf(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        is BigInteger -> v.takeIf { it.bitLength() < 32 }?.toInt()
        else -> null
    }

    /** Exact value with at most one decimal digit; null for anything else (never rounded). */
    private fun oneDecimal(v: Any?): BigDecimal? {
        val d = when (v) {
            is Int, is Long -> BigDecimal(v.toString())
            is BigInteger -> BigDecimal(v)
            is BigDecimal -> v
            is Double -> if (v.isFinite()) BigDecimal(v.toString()) else return null
            else -> return null
        }
        return d.stripTrailingZeros().takeIf { it.scale() <= 1 }?.let { if (it.scale() < 0) it.setScale(0) else it }
    }
}
