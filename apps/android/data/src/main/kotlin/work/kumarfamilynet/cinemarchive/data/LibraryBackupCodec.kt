package work.kumarfamilynet.cinemarchive.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Library backup/restore document codec (#323; contract: docs/android-contracts/library-backup.md).
 *
 * A pure, I/O-free, Room-free layer so the exact same behavior can be pinned by shared fixtures
 * (docs/fixtures/library-backup/) on both clients:
 *  - [parse] runs its OWN strict RFC 8259 parser (no org.json tokener, so the JVM and Android
 *    behave identically): no trailing tokens, comments, single quotes, NaN/Infinity, leading zeros,
 *    duplicate keys, raw control characters; nesting depth and node count are bounded. It reads the
 *    web `v1` envelope, a bare titles array, and the `v2` envelope. Entities stay as [JSONObject]
 *    trees, so **unknown fields survive verbatim** in the archive document (lossless round trip).
 *  - [encode] writes `v2` with sorted keys and its own string/number formatting. Canonical form is
 *    deterministic; byte identity across runtimes is NOT promised (compare canonical forms).
 *  - [validate] is the trust boundary: every archive is untrusted input. Structural problems are
 *    fatal for the owning title/outing/list; ambiguous identities reject every row sharing the id.
 *  - [planCopy] is the "restore as copy" planner (no writes): admitted rows are rebuilt from
 *    per-entity allowlists, everything else is moved into one inert `ext` object, untrusted keys are
 *    removed at any depth, every identity is regenerated, every reference is rewritten or dropped
 *    (and counted + listed), and a full [Report] is returned.
 *  - [sha256Hex] is the idempotency-hash helper; callers must hash the ORIGINAL input bytes.
 */
object LibraryBackupCodec {
    const val FORMAT = "cinemarchive-library"
    const val CURRENT_VERSION = 2

    /** Hard bounds — an archive above these is rejected before any planning. */
    object Limits {
        const val MAX_TITLES = 50_000
        const val MAX_OUTINGS = 100_000
        const val MAX_LISTS = 1_000
        const val MAX_LIST_ITEMS = 200_000
        const val MAX_SEASONS_PER_TITLE = 200
        const val MAX_EPISODES_PER_SEASON = 2_000
        const val MAX_VIEWINGS_PER_TITLE = 5_000
        const val MAX_ROWS_PER_EPISODE = 1_000
        const val MAX_TAGS = 100
        const val MAX_STRING = 20_000
        const val MAX_ID = 128
        const val MAX_SHORT = 512
        /** Maximum container nesting accepted by the parser (root counts as 1). */
        const val MAX_DEPTH = 64
        /** Maximum number of JSON values (scalars + containers) in one document. */
        const val MAX_NODES = 4_000_000
    }

    private val TITLE_TYPES = setOf("movie", "tv")
    private val WATCH_STATUSES = setOf("watched", "watchlist", "watching", "dropped")
    private val OUTING_STATUSES = setOf("scheduled", "completed", "missed", "cancelled")
    private val KNOWN_FORMATS = setOf("Standard", "IMAX", "3D", "Dolby", "70mm", "Drive-in", "Other")

    /**
     * Keys that are never trusted from an archive, at any nesting depth of an admitted row (matched
     * case-insensitively). `ticketImagePath` is a device-local file path: never portable, never kept.
     */
    private val UNTRUSTED_ROW_KEYS = setOf(
        "ticketAttachment", "ticketManaged", "ticketImagePath",
        "user_id", "userId", "ownerUserId", "owner_user_id",
        "outbox", "receipts", "aliases", "operationId", "operationIds", "tombstones", "syncCursor",
        "shareToken", "shareTokens", "token",
    )
    private val UNTRUSTED_LOWER = UNTRUSTED_ROW_KEYS.map { it.lowercase() }.toSet()
    private fun isUntrusted(key: String) = key.lowercase() in UNTRUSTED_LOWER

    /** Top-level keys the format defines; anything else is kept in [BackupDocument.extra] but is
     *  never written into the library by a restore. */
    private val KNOWN_TOP_LEVEL = setOf("version", "format", "exportedAt", "client", "titles", "outings", "lists", "localOnly")

    // Per-entity allowlists (derived from apps/web/src/store/mockData.ts). Unknown keys go to `ext`.
    private val TITLE_FIELDS = setOf(
        "id", "tmdbId", "type", "title", "year", "director", "genres", "posterUrl", "backdropUrl", "synopsis",
        "runtime", "network", "seasons", "status", "rating", "notes", "tags", "addedAt", "releaseDate",
        "originalLanguage", "contentRating", "imdbId", "rtUrl", "customWatchUrl", "inHomeCollection",
        "physicalMedia", "collectionId", "collectionName", "viewings", "imdbRating", "rtScore",
        "metacriticScore", "awardsCount", "bechdelOutcome", "bechdelScore", "cast", "crew", "studios",
    )
    private val TITLE_STRUCTURAL = setOf("seasons", "viewings", "physicalMedia")
    private val SEASON_FIELDS = setOf("id", "seasonNumber", "episodeCount", "episodesWatched", "airYear", "cast", "episodes")
    private val SEASON_STRUCTURAL = setOf("episodes")
    private val EPISODE_FIELDS = setOf(
        "id", "episodeNumber", "episodeName", "airDate", "runtime", "synopsis", "stillUrl", "director",
        "writers", "crew", "watchEvents", "ratings", "reviews",
    )
    private val EPISODE_ROW_KEYS = listOf("watchEvents", "ratings", "reviews")
    private val EPISODE_STRUCTURAL = EPISODE_ROW_KEYS.toSet()
    private val EPISODE_ROW_TABLES = listOf("watchEvents" to "episode watch", "ratings" to "episode rating", "reviews" to "episode review")
    private val WATCH_FIELDS = setOf("id", "watchedAt", "notes", "colorMode")
    private val RATING_FIELDS = setOf("id", "rating", "ratedAt")
    private val REVIEW_FIELDS = setOf("id", "reviewText", "reviewedAt", "colorMode")
    private val VIEWING_FIELDS = setOf("id", "titleId", "date", "rating", "notes", "venue", "companions", "outingId")
    private val OUTING_FIELDS = setOf(
        "ticketBarcodePayload", "ticketBarcodeFormat", "id", "titleId", "showtime", "previewsMinutes",
        "runtimeMinutes", "endsAt", "venue", "companions", "format", "ticketPrice", "seat", "auditorium",
        "seatRow", "seats", "bookingRef", "notes", "status", "previousStatus", "completedViewingId",
        "followUpDismissedAt", "createdAt",
    )
    private val COMPANIONS_STRUCTURAL = setOf("companions")
    private val LIST_FIELDS = setOf("id", "name", "description", "createdAt", "updatedAt", "items")
    private val LIST_STRUCTURAL = setOf("items")
    private val LIST_ITEM_FIELDS = setOf("titleId", "position", "addedAt")

    // ── Model ───────────────────────────────────────────────────────────────────────────────

    class BackupDocument(
        val version: Int,
        val format: String?,
        val exportedAt: String?,
        val client: JSONObject?,
        val titles: List<JSONObject>,
        val outings: List<JSONObject>,
        val lists: List<JSONObject>,
        val localOnly: JSONObject?,
        /** Unknown top-level keys, preserved verbatim for lossless re-encoding. */
        val extra: JSONObject,
    )

    sealed interface ParseResult {
        data class Success(val document: BackupDocument, val sourceVersion: Int, val bareArray: Boolean) : ParseResult
        data class Failure(val message: String) : ParseResult
    }

    data class Issue(val path: String, val message: String, val fatal: Boolean)

    private class BackupFormatException(message: String) : Exception(message)

    private fun isNull(v: Any?): Boolean = v == null || v === JSONObject.NULL

    private fun integral(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Short -> v.toLong()
        is Byte -> v.toLong()
        else -> null
    }

    private fun keysOf(o: JSONObject): List<String> = o.keys().asSequence().toList()

    /** Lowercase hex SHA-256. Hash the ORIGINAL input bytes for idempotency keys, never re-encoded JSON. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    // ── Parse ───────────────────────────────────────────────────────────────────────────────

    /** Largest archive a reader accepts (checked BEFORE the text is allocated). */
    const val MAX_BYTES = 64 * 1024 * 1024

    /** Longest numeric token the parser converts (exact BigInteger/BigDecimal are bounded). */
    private const val MAX_NUMBER_TOKEN = 64

    private fun tooLarge(maxBytes: Int) =
        ParseResult.Failure("This backup is too large to import (limit ${maxBytes / (1024 * 1024)} MiB).")

    /**
     * Bounded entry point for files/streams: reads at most [maxBytes]+1 bytes and rejects anything
     * larger without ever allocating the whole input, then parses. NOTE: parsing still builds the
     * full in-memory tree — this is *bounded*, not streaming.
     */
    fun parseStream(input: java.io.InputStream, maxBytes: Int = MAX_BYTES): ParseResult {
        try {
            val buffer = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > maxBytes) return tooLarge(maxBytes)
                buffer.write(chunk, 0, n)
            }
            return parseBytes(buffer.toByteArray(), maxBytes)
        } catch (e: java.io.IOException) {
            return ParseResult.Failure("Could not read the backup file.")
        } catch (e: OutOfMemoryError) {
            return tooLarge(maxBytes)
        }
    }

    fun parseBytes(bytes: ByteArray, maxBytes: Int = MAX_BYTES): ParseResult {
        if (bytes.size > maxBytes) return tooLarge(maxBytes)
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            return ParseResult.Failure("Invalid file — not valid UTF-8.")
        } catch (e: OutOfMemoryError) {
            return tooLarge(maxBytes)
        }
        return parse(text)
    }

    fun parse(text: String): ParseResult = parseWithLimits(text, Limits.MAX_NODES, Limits.MAX_DEPTH)

    /** Test seam for the node/depth bounds. */
    internal fun parseWithLimits(text: String, maxNodes: Int, maxDepth: Int): ParseResult {
        // Exactly one leading BOM is tolerated; any other U+FEFF is an ordinary (invalid) character.
        val body = if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text
        return try {
            val root = StrictJsonParser(body, maxNodes, maxDepth).parseDocument()
            when (root) {
                is JSONArray -> {
                    val titles = objects(root) ?: throw BackupFormatException("Invalid export file: every title must be an object.")
                    ParseResult.Success(BackupDocument(1, null, null, null, titles, emptyList(), emptyList(), null, JSONObject()), 1, true)
                }
                is JSONObject -> parseEnvelope(root)
                else -> ParseResult.Failure("Invalid export file: expected a \"titles\" array.")
            }
        } catch (e: BackupFormatException) {
            ParseResult.Failure(e.message ?: "Invalid file.")
        } catch (e: OutOfMemoryError) {
            ParseResult.Failure("This backup is too large to import.")
        } catch (e: StackOverflowError) {
            ParseResult.Failure("Invalid file — nested too deeply.")
        } catch (e: RuntimeException) {
            ParseResult.Failure("Invalid file.")
        }
    }

    private fun parseEnvelope(root: JSONObject): ParseResult {
        val rawFormat = root.opt("format")
        var format: String? = null
        if (!isNull(rawFormat)) {
            if (rawFormat !is String) throw BackupFormatException("Invalid export file: \"format\" must be a string.")
            if (rawFormat.isNotEmpty()) {
                if (rawFormat != FORMAT) throw BackupFormatException("This file is not a CinemArchive library backup (format \"${rawFormat.take(64)}\").")
                format = rawFormat
            }
        }
        val rawVersion = root.opt("version")
        val version: Int = if (rawVersion == null) 1 else {
            val v = integral(rawVersion) ?: throw BackupFormatException("Invalid export file: \"version\" must be an integer.")
            if (v < 1) throw BackupFormatException("Invalid export file: unsupported version $v.")
            if (v > CURRENT_VERSION) throw BackupFormatException("This backup was made by a newer version of CinemArchive (format v$v).")
            v.toInt()
        }
        val titlesRaw = root.opt("titles") as? JSONArray ?: throw BackupFormatException("Invalid export file: expected a \"titles\" array.")
        val titles = objects(titlesRaw) ?: throw BackupFormatException("Invalid export file: every title must be an object.")
        // Older exports (pre-cinema-outings) simply have no "outings" key — tolerate its absence, not a wrong type.
        val outings = optionalObjects(root, "outings", "outing")
        val lists = optionalObjects(root, "lists", "list")
        val client = root.opt("client")
        if (!isNull(client) && client !is JSONObject) throw BackupFormatException("Invalid export file: \"client\" must be an object.")
        val localOnly = root.opt("localOnly")
        if (!isNull(localOnly) && localOnly !is JSONObject) throw BackupFormatException("Invalid export file: \"localOnly\" must be an object.")
        val exportedAt = root.opt("exportedAt")
        if (!isNull(exportedAt) && exportedAt !is String) throw BackupFormatException("Invalid export file: \"exportedAt\" must be a string.")
        val extra = JSONObject()
        for (key in keysOf(root)) if (key !in KNOWN_TOP_LEVEL) extra.put(key, root.get(key))
        return ParseResult.Success(
            BackupDocument(
                version = version,
                format = format,
                exportedAt = (exportedAt as? String)?.takeIf { it.isNotEmpty() },
                client = client as? JSONObject,
                titles = titles,
                outings = outings,
                lists = lists,
                localOnly = localOnly as? JSONObject,
                extra = extra,
            ),
            version,
            false,
        )
    }

    private fun optionalObjects(root: JSONObject, key: String, label: String): List<JSONObject> {
        val raw = root.opt(key)
        if (isNull(raw)) return emptyList()
        val array = raw as? JSONArray ?: throw BackupFormatException("Invalid export file: \"$key\" must be an array.")
        return objects(array) ?: throw BackupFormatException("Invalid export file: every $label must be an object.")
    }

    private fun objects(array: JSONArray): List<JSONObject>? {
        val out = ArrayList<JSONObject>(array.length())
        for (i in 0 until array.length()) out += (array.opt(i) as? JSONObject ?: return null)
        return out
    }

    /** Strict RFC 8259 recursive-descent parser producing JSONObject/JSONArray/String/Long/Double/Boolean/NULL. */
    private class StrictJsonParser(private val s: String, private val maxNodes: Int, private val maxDepth: Int) {
        private var pos = 0
        private var nodes = 0

        fun parseDocument(): Any {
            skipWs()
            val v = parseValue(0)
            skipWs()
            if (pos != s.length) fail("unexpected content after the JSON value")
            return v
        }

        private fun fail(msg: String): Nothing =
            throw BackupFormatException("Invalid file — not valid JSON ($msg at offset $pos).")

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        private fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        private fun parseValue(depth: Int): Any {
            nodes++
            if (nodes > maxNodes) fail("document has too many values")
            if (pos >= s.length) fail("unexpected end of input")
            val c = s[pos]
            return when {
                c == '{' -> parseObject(depth + 1)
                c == '[' -> parseArray(depth + 1)
                c == '"' -> parseString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", JSONObject.NULL)
                c == '-' || (c in '0'..'9') -> parseNumber()
                else -> fail("unexpected character")
            }
        }

        private fun literal(word: String, value: Any): Any {
            if (!s.startsWith(word, pos)) fail("unexpected token")
            pos += word.length
            return value
        }

        private fun parseObject(depth: Int): JSONObject {
            if (depth > maxDepth) fail("nesting too deep")
            pos++ // {
            val obj = JSONObject()
            skipWs()
            if (peek() == '}') { pos++; return obj }
            while (true) {
                skipWs()
                if (peek() != '"') fail("expected a quoted key")
                val key = parseString()
                if (obj.has(key)) fail("duplicate key \"${key.take(40)}\"")
                skipWs()
                if (peek() != ':') fail("expected ':'")
                pos++
                skipWs()
                val v = parseValue(depth)
                obj.put(key, v)
                skipWs()
                val c = peek()
                if (c == ',') { pos++; continue }
                if (c == '}') { pos++; return obj }
                fail("expected ',' or '}'")
            }
        }

        private fun parseArray(depth: Int): JSONArray {
            if (depth > maxDepth) fail("nesting too deep")
            pos++ // [
            val arr = JSONArray()
            skipWs()
            if (peek() == ']') { pos++; return arr }
            while (true) {
                skipWs()
                arr.put(parseValue(depth))
                skipWs()
                val c = peek()
                if (c == ',') { pos++; continue }
                if (c == ']') { pos++; return arr }
                fail("expected ',' or ']'")
            }
        }

        private fun hex(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

        private fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                if (c == '"') { checkSurrogates(sb); return sb.toString() }
                if (c < ' ') { pos--; fail("control character in string") }
                if (c != '\\') { sb.append(c); continue }
                if (pos >= s.length) fail("unterminated escape")
                when (s[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > s.length) fail("invalid unicode escape")
                        var code = 0
                        for (k in 0 until 4) {
                            val d = hex(s[pos + k])
                            if (d < 0) fail("invalid unicode escape")
                            code = code * 16 + d
                        }
                        pos += 4
                        sb.append(code.toChar())
                    }
                    else -> { pos--; fail("invalid escape") }
                }
            }
        }

        private fun digits(): Int {
            val start = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            return pos - start
        }

        private fun parseNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length) fail("invalid number")
            if (s[pos] == '0') {
                pos++
                if (pos < s.length && s[pos] in '0'..'9') fail("leading zeros are not allowed")
            } else if (s[pos] in '1'..'9') {
                digits()
            } else {
                fail("invalid number")
            }
            var isInteger = true
            if (pos < s.length && s[pos] == '.') {
                isInteger = false
                pos++
                if (digits() == 0) fail("invalid number")
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isInteger = false
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (digits() == 0) fail("invalid number")
            }
            val text = s.substring(start, pos)
            if (text.length > MAX_NUMBER_TOKEN) fail("number too long")
            if (isInteger) {
                val l = text.toLongOrNull()
                if (l != null) return l
                // Exact, never routed through Double: an opaque future value must round-trip unchanged.
                return java.math.BigInteger(text).also { requireFinite(it.toDouble()) }
            }
            // Exact decimal (value-identical on re-encode; exponent forms may be re-spelled, e.g. 1e3 -> 1E+3).
            // The stored value is never rounded; only values whose Double view is infinite are refused,
            // because the framework org.json validates Number.doubleValue() (same range as before: |x| < ~1.8e308).
            return try {
                java.math.BigDecimal(text).also { requireFinite(it.toDouble()) }
            } catch (_: NumberFormatException) {
                fail("invalid number")
            } catch (_: ArithmeticException) {
                fail("number out of range")
            }
        }

        private fun requireFinite(d: Double) {
            if (d.isNaN() || d.isInfinite()) fail("number out of range")
        }

        /** JSON text allows any pair of surrogates only when well-formed; an unpaired one cannot be written as UTF-8. */
        private fun checkSurrogates(t: CharSequence) {
            var i = 0
            while (i < t.length) {
                val c = t[i]
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 < t.length && Character.isLowSurrogate(t[i + 1])) i += 2 else fail("unpaired surrogate")
                } else if (Character.isLowSurrogate(c)) {
                    fail("unpaired surrogate")
                } else {
                    i++
                }
            }
        }
    }

    // ── Encode ──────────────────────────────────────────────────────────────────────────────

    /**
     * Always writes v2, keys sorted at every level, 2-space indent, trailing newline. Throws
     * [IllegalArgumentException] only for a programmatically built tree nested deeper than the
     * parser would ever accept (parsed documents can never trigger it).
     */
    fun encode(document: BackupDocument, exportedAt: String, client: JSONObject? = null): String {
        val root = JSONObject()
        for (key in keysOf(document.extra)) root.put(key, document.extra.get(key))
        root.put("format", FORMAT)
        root.put("version", CURRENT_VERSION)
        root.put("exportedAt", exportedAt)
        (client ?: document.client)?.let { root.put("client", it) }
        root.put("titles", JSONArray(document.titles))
        root.put("outings", JSONArray(document.outings))
        root.put("lists", JSONArray(document.lists))
        document.localOnly?.let { root.put("localOnly", it) }
        val sb = StringBuilder()
        writeValue(sb, root, 0)
        return sb.append('\n').toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?, depth: Int) {
        if (depth > Limits.MAX_DEPTH + 4) throw IllegalArgumentException("Backup tree is nested too deeply to encode.")
        if (value == null || value === JSONObject.NULL) { sb.append("null"); return }
        when (value) {
            is JSONObject -> {
                val keys = keysOf(value).sorted()
                if (keys.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                keys.forEachIndexed { i, k ->
                    indent(sb, depth + 1); sb.append(quote(k)).append(": ")
                    writeValue(sb, value.get(k), depth + 1)
                    if (i < keys.size - 1) sb.append(',')
                    sb.append('\n')
                }
                indent(sb, depth); sb.append('}')
            }
            is JSONArray -> {
                if (value.length() == 0) { sb.append("[]"); return }
                sb.append("[\n")
                for (i in 0 until value.length()) {
                    indent(sb, depth + 1)
                    writeValue(sb, value.get(i), depth + 1)
                    if (i < value.length() - 1) sb.append(',')
                    sb.append('\n')
                }
                indent(sb, depth); sb.append(']')
            }
            is String -> sb.append(quote(value))
            is Boolean -> sb.append(value.toString())
            is Number -> sb.append(formatNumber(value))
            else -> sb.append(quote(value.toString()))
        }
    }

    private fun indent(sb: StringBuilder, depth: Int) { repeat(depth) { sb.append("  ") } }

    /** Own quoting so output never depends on the runtime's JSONObject.quote (slash escaping differs). */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun formatNumber(n: Number): String = when (n) {
        is Long, is Int, is Short, is Byte -> n.toString()
        is Double, is Float -> {
            val d = n.toDouble()
            when {
                d.isNaN() || d.isInfinite() -> "null"
                d == Math.rint(d) && Math.abs(d) < 1e15 -> d.toLong().toString()
                else -> d.toString()
            }
        }
        else -> n.toString()
    }

    // ── Validate ────────────────────────────────────────────────────────────────────────────

    private class Analysis(
        val issues: List<Issue>,
        val documentFatal: Boolean,
        val titleFatal: BooleanArray,
        val outingFatal: BooleanArray,
        val listFatal: BooleanArray,
        /** Outing rejected because its completed history cannot be resolved. */
        val completedRejected: BooleanArray,
        /** Archive index of the title an (accepted) outing belongs to, else -1. */
        val outingTitleIdx: IntArray,
        val ambiguousIdRows: Int,
    )

    private fun isUnder(path: String, prefix: String) =
        path == prefix || path.startsWith("$prefix.") || path.startsWith("$prefix[")

    /**
     * Returns every problem found. A `fatal` issue means that row cannot be restored (the planner
     * skips it and reports why); non-fatal issues are warnings (the row is restored, normalized).
     * Document-level limit violations are fatal with path `$`. Never throws.
     */
    fun validate(document: BackupDocument): List<Issue> = try {
        analyze(document).issues
    } catch (e: Exception) {
        listOf(Issue("$", "Unexpected error while validating the backup (${e.javaClass.simpleName}).", true))
    } catch (e: StackOverflowError) {
        listOf(Issue("$", "Backup is nested too deeply to validate.", true))
    }

    private fun forEachObject(raw: Any?, block: (JSONObject) -> Unit) {
        if (raw !is JSONArray) return
        for (i in 0 until raw.length()) {
            val o = raw.opt(i)
            if (o is JSONObject) block(o)
        }
    }

    private fun analyze(document: BackupDocument): Analysis {
        val issues = ArrayList<Issue>()
        fun fatal(path: String, msg: String) { issues += Issue(path, msg, true) }
        fun warn(path: String, msg: String) { issues += Issue(path, msg, false) }

        val nT = document.titles.size
        val nO = document.outings.size
        val nL = document.lists.size
        val titleFatal = BooleanArray(nT)
        val outingFatal = BooleanArray(nO)
        val listFatal = BooleanArray(nL)
        val completedRejected = BooleanArray(nO)
        val outingTitleIdx = IntArray(nO) { -1 }

        if (nT > Limits.MAX_TITLES) fatal("$", "Too many titles ($nT > ${Limits.MAX_TITLES}).")
        if (nO > Limits.MAX_OUTINGS) fatal("$", "Too many outings.")
        if (nL > Limits.MAX_LISTS) fatal("$", "Too many lists.")
        if (issues.isNotEmpty()) return Analysis(issues, true, titleFatal, outingFatal, listFatal, completedRejected, outingTitleIdx, 0)

        // ── Pre-scan: count every id per table so ambiguity is known before any row is judged.
        val counts = HashMap<String, HashMap<String, Int>>()
        val titleIdx = HashMap<String, Int>()
        val viewingOwner = HashMap<String, Int>()
        val outingIdx = HashMap<String, Int>()
        fun see(table: String, raw: Any?): String? {
            val id = raw as? String ?: return null
            if (id.isEmpty() || id.length > Limits.MAX_ID) return null
            val m = counts.getOrPut(table) { HashMap() }
            m[id] = (m[id] ?: 0) + 1
            return id
        }
        fun ambiguous(table: String, id: String) = (counts[table]?.get(id) ?: 0) > 1

        document.titles.forEachIndexed { i, t ->
            see("title", t.opt("id"))?.let { titleIdx[it] = i }
            forEachObject(t.opt("seasons")) { s ->
                see("season", s.opt("id"))
                forEachObject(s.opt("episodes")) { e ->
                    see("episode", e.opt("id"))
                    for ((key, table) in EPISODE_ROW_TABLES) forEachObject(e.opt(key)) { r -> see(table, r.opt("id")) }
                }
            }
            forEachObject(t.opt("viewings")) { v -> see("viewing", v.opt("id"))?.let { viewingOwner[it] = i } }
        }
        document.outings.forEachIndexed { i, o -> see("outing", o.opt("id"))?.let { outingIdx[it] = i } }
        document.lists.forEach { l -> see("list", l.opt("id")) }
        var ambiguousRows = 0
        for (table in counts.values) for (n in table.values) if (n > 1) ambiguousRows += n

        fun idOk(table: String, path: String, raw: Any?, required: Boolean): Boolean {
            if (isNull(raw)) {
                if (required) { fatal(path, "Missing $table identity."); return false }
                return true
            }
            val id = raw as? String
            if (id == null || id.isEmpty() || id.length > Limits.MAX_ID) { fatal(path, "Invalid $table identity."); return false }
            if (ambiguous(table, id)) {
                fatal(path, "Ambiguous $table identity: used by ${counts[table]?.get(id)} rows (every such row is rejected).")
                return false
            }
            return true
        }

        fun arr(owner: JSONObject, key: String, path: String): JSONArray? {
            val v = owner.opt(key)
            if (isNull(v)) return null
            if (v is JSONArray) return v
            fatal(path, "Must be an array.")
            return null
        }

        fun obj(a: JSONArray, i: Int, path: String): JSONObject? {
            val o = a.opt(i)
            if (o is JSONObject) return o
            fatal(path, "Must be an object.")
            return null
        }

        fun strings(owner: JSONObject, key: String, path: String) {
            val a = arr(owner, key, path) ?: return
            if (a.length() > Limits.MAX_TAGS) { fatal(path, "Too many $key."); return }
            for (k in 0 until a.length()) if (a.opt(k) !is String) fatal("$path[$k]", "Must be a string.")
        }

        fun checkTitle(i: Int, t: JSONObject) {
            val p = "titles[$i]"
            if (!idOk("title", "$p.id", t.opt("id"), false)) return
            val tmdb = integral(t.opt("tmdbId"))
            if (tmdb == null || tmdb <= 0 || tmdb > Int.MAX_VALUE) { fatal(p, "Missing or invalid tmdbId (must be a positive integer)."); return }
            val type = t.opt("type") as? String
            if (type == null || type !in TITLE_TYPES) { fatal(p, "Unknown title type."); return }
            val name = t.opt("title") as? String
            if (name.isNullOrBlank()) { fatal(p, "Missing title."); return }
            if (name.length > Limits.MAX_SHORT * 2) { fatal(p, "Title too long."); return }
            val status = t.opt("status")
            if (!isNull(status) && !(status is String && status in WATCH_STATUSES)) { fatal(p, "Unknown status \"$status\"."); return }
            checkRating(t.opt("rating"), "$p.rating", ::fatal)
            checkStrings(t, p, ::fatal)
            strings(t, "tags", "$p.tags")
            strings(t, "genres", "$p.genres")

            val seasons = arr(t, "seasons", "$p.seasons")
            if (seasons != null) {
                if (seasons.length() > Limits.MAX_SEASONS_PER_TITLE) { fatal("$p.seasons", "Too many seasons."); return }
                val seenSeason = HashSet<Long>()
                for (si in 0 until seasons.length()) {
                    val sp = "$p.seasons[$si]"
                    val s = obj(seasons, si, sp) ?: continue
                    idOk("season", "$sp.id", s.opt("id"), false)
                    checkStrings(s, sp, ::fatal)
                    val sn = integral(s.opt("seasonNumber"))
                    if (sn == null || sn < 0) fatal("$sp.seasonNumber", "Season needs an integer seasonNumber >= 0.")
                    else if (!seenSeason.add(sn)) fatal("$sp.seasonNumber", "Duplicate seasonNumber $sn within the title.")
                    val eps = arr(s, "episodes", "$sp.episodes") ?: continue
                    if (eps.length() > Limits.MAX_EPISODES_PER_SEASON) { fatal("$sp.episodes", "Too many episodes."); continue }
                    val seenEp = HashSet<Long>()
                    for (ei in 0 until eps.length()) {
                        val ep = "$sp.episodes[$ei]"
                        val e = obj(eps, ei, ep) ?: continue
                        idOk("episode", "$ep.id", e.opt("id"), false)
                        checkStrings(e, ep, ::fatal)
                        val en = integral(e.opt("episodeNumber"))
                        if (en == null || en < 0) fatal("$ep.episodeNumber", "Episode needs an integer episodeNumber >= 0.")
                        else if (!seenEp.add(en)) fatal("$ep.episodeNumber", "Duplicate episodeNumber $en within the season.")
                        for ((key, table) in EPISODE_ROW_TABLES) {
                            val rows = arr(e, key, "$ep.$key") ?: continue
                            if (rows.length() > Limits.MAX_ROWS_PER_EPISODE) { fatal("$ep.$key", "Too many rows."); continue }
                            for (ri in 0 until rows.length()) {
                                val rp = "$ep.$key[$ri]"
                                val row = obj(rows, ri, rp) ?: continue
                                idOk(table, "$rp.id", row.opt("id"), false)
                                checkStrings(row, rp, ::fatal)
                                when (key) {
                                    "watchEvents" -> checkDate(row.opt("watchedAt"), "$rp.watchedAt", ::warn)
                                    "ratings" -> {
                                        if (row.opt("rating") !is Number) fatal("$rp.rating", "Rating row needs a number between 0 and 5.")
                                        else checkRating(row.opt("rating"), "$rp.rating", ::fatal)
                                        if (row.opt("ratedAt") !is String) fatal("$rp.ratedAt", "Rating row needs a string ratedAt.")
                                        else checkDate(row.opt("ratedAt"), "$rp.ratedAt", ::warn)
                                    }
                                    else -> {
                                        if (row.opt("reviewText") !is String) fatal("$rp.reviewText", "Review row needs a string reviewText.")
                                        if (row.opt("reviewedAt") !is String) fatal("$rp.reviewedAt", "Review row needs a string reviewedAt.")
                                        else checkDate(row.opt("reviewedAt"), "$rp.reviewedAt", ::warn)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val viewings = arr(t, "viewings", "$p.viewings")
            if (viewings != null) {
                if (viewings.length() > Limits.MAX_VIEWINGS_PER_TITLE) { fatal("$p.viewings", "Too many viewings."); return }
                val parentId = t.opt("id") as? String
                for (vi in 0 until viewings.length()) {
                    val vp = "$p.viewings[$vi]"
                    val v = obj(viewings, vi, vp) ?: continue
                    idOk("viewing", "$vp.id", v.opt("id"), false)
                    checkRating(v.opt("rating"), "$vp.rating", ::fatal)
                    checkDate(v.opt("date"), "$vp.date", ::warn)
                    checkStrings(v, vp, ::fatal)
                    val vt = v.opt("titleId")
                    if (!isNull(vt) && (vt !is String || vt != parentId)) {
                        fatal("$vp.titleId", "Viewing titleId does not match its parent title (ambiguous graph).")
                    }
                    arr(v, "companions", "$vp.companions")
                }
            }
        }

        document.titles.forEachIndexed { i, t ->
            val before = issues.size
            checkTitle(i, t)
            titleFatal[i] = issues.subList(before, issues.size).any { it.fatal }
        }

        fun checkOuting(i: Int, o: JSONObject) {
            val p = "outings[$i]"
            val startIssues = issues.size
            if (!idOk("outing", "$p.id", o.opt("id"), false)) return
            val tid = o.opt("titleId") as? String
            if (tid == null) { fatal(p, "Outing has no titleId."); return }
            val showtime = o.opt("showtime") as? String
            if (showtime == null || !isInstant(showtime)) { fatal(p, "Invalid showtime."); return }
            val ends = o.opt("endsAt") as? String
            if (ends == null || !isInstant(ends)) { fatal(p, "Invalid endsAt."); return }
            val status = o.opt("status") as? String
            if (status == null || status !in OUTING_STATUSES) { fatal(p, "Unknown outing status."); return }
            for (k in listOf("previewsMinutes", "runtimeMinutes")) {
                val raw = o.opt(k)
                if (isNull(raw)) continue
                val n = raw as? Number
                if (n == null || n.toDouble().isNaN() || n.toDouble() < 0 || n.toDouble() > 24 * 60) { fatal("$p.$k", "Out of range."); return }
            }
            (o.opt("format") as? String)?.let { if (it !in KNOWN_FORMATS) warn("$p.format", "Unknown format \"$it\" kept as-is.") }
            checkStrings(o, p, ::fatal)
            arr(o, "companions", "$p.companions")
            strings(o, "seats", "$p.seats")
            val cv = o.opt("completedViewingId")
            if (!isNull(cv) && cv !is String) { fatal("$p.completedViewingId", "Must be a string."); return }
            if (issues.subList(startIssues, issues.size).any { it.fatal }) return

            // Cross-references.
            if (ambiguous("title", tid)) { fatal("$p.titleId", "Outing refers to an ambiguous title identity."); return }
            val ti = titleIdx[tid]
            if (ti == null) { fatal(p, "Outing refers to a title that is not in the archive."); return }
            if (titleFatal[ti]) { fatal(p, "Outing refers to a title that was rejected."); return }
            outingTitleIdx[i] = ti
            val completed = status == "completed"
            val cp = "$p.completedViewingId"
            if (cv is String) {
                if (ambiguous("viewing", cv)) {
                    fatal(cp, "Outing refers to an ambiguous viewing identity.")
                    if (completed) completedRejected[i] = true
                    return
                }
                val owner = viewingOwner[cv]
                if (owner == null) {
                    if (completed) { fatal(cp, "Completed outing has no resolvable viewing in the archive."); completedRejected[i] = true; return }
                    warn(cp, "completedViewingId does not reference a viewing in the archive; the reference is dropped.")
                } else if (titleFatal[owner]) {
                    fatal(cp, "Outing refers to a viewing of a rejected title.")
                    if (completed) completedRejected[i] = true
                    return
                } else if (owner != ti) {
                    if (completed) { fatal(cp, "Completed outing's viewing belongs to a different title."); completedRejected[i] = true; return }
                    warn(cp, "completedViewingId references a viewing of a different title; the reference is dropped.")
                }
            } else if (completed) {
                fatal(cp, "Completed outing has no completedViewingId (its history is required).")
                completedRejected[i] = true
            }
        }

        document.outings.forEachIndexed { i, o ->
            val before = issues.size
            checkOuting(i, o)
            outingFatal[i] = issues.subList(before, issues.size).any { it.fatal }
            if (outingFatal[i]) outingTitleIdx[i] = -1
        }

        // viewing.outingId must reference an accepted outing of the same title; otherwise it is dropped.
        document.titles.forEachIndexed { i, t ->
            if (titleFatal[i]) return@forEachIndexed
            val vs = t.opt("viewings") as? JSONArray ?: return@forEachIndexed
            for (vi in 0 until vs.length()) {
                val v = vs.opt(vi) as? JSONObject ?: continue
                val ref = v.opt("outingId")
                if (isNull(ref)) continue
                val oi = (ref as? String)?.let { if (ambiguous("outing", it)) null else outingIdx[it] }
                if (oi == null || outingFatal[oi] || outingTitleIdx[oi] != i) {
                    warn("titles[$i].viewings[$vi].outingId", "outingId does not reference an accepted outing of the same title; the reference is dropped.")
                }
            }
        }

        fun checkList(i: Int, l: JSONObject) {
            val p = "lists[$i]"
            if (!idOk("list", "$p.id", l.opt("id"), true)) return
            val name = l.opt("name") as? String
            if (name.isNullOrBlank()) { fatal(p, "List has no name."); return }
            checkStrings(l, p, ::fatal)
            val items = arr(l, "items", "$p.items") ?: return
            if (items.length() > Limits.MAX_LIST_ITEMS) { fatal("$p.items", "Too many list items."); return }
            for (ii in 0 until items.length()) {
                val item = items.opt(ii) as? JSONObject
                if (item == null || item.opt("titleId") !is String) fatal("$p.items[$ii]", "List item has no titleId.")
            }
        }
        document.lists.forEachIndexed { i, l ->
            val before = issues.size
            checkList(i, l)
            listFatal[i] = issues.subList(before, issues.size).any { it.fatal }
        }
        return Analysis(issues, false, titleFatal, outingFatal, listFatal, completedRejected, outingTitleIdx, ambiguousRows)
    }

    private fun checkRating(raw: Any?, path: String, fatal: (String, String) -> Unit) {
        if (isNull(raw)) return
        val n = (raw as? Number)?.toDouble()
        if (n == null || n.isNaN() || n < 0.0 || n > 5.0) fatal(path, "Rating must be between 0 and 5.")
    }

    private fun checkDate(raw: Any?, path: String, warn: (String, String) -> Unit) {
        if (isNull(raw)) return // undated stays undated, never "today"
        val s = raw as? String
        if (s == null || !(isInstant(s) || isLocalDate(s))) warn(path, "Unrecognized date form kept as-is.")
    }

    private fun checkStrings(row: JSONObject, path: String, fatal: (String, String) -> Unit) {
        for (key in keysOf(row)) {
            val v = row.opt(key)
            if (v is String && v.length > Limits.MAX_STRING) fatal("$path.$key", "Text too long.")
        }
    }

    private fun isInstant(s: String) = try { Instant.parse(s); true } catch (_: DateTimeParseException) {
        try { java.time.OffsetDateTime.parse(s); true } catch (_: DateTimeParseException) { false }
    }

    private fun isLocalDate(s: String) = try { LocalDate.parse(s); true } catch (_: DateTimeParseException) { false }

    // ── Companions (mirror of apps/web/src/store/companions.ts normalizeCompanions) ──────────

    fun normalizeCompanions(raw: Any?): JSONArray {
        val out = JSONArray()
        val array = raw as? JSONArray ?: return out
        for (i in 0 until array.length()) {
            val entry = array.opt(i)
            val name = when (entry) {
                is String -> entry
                is JSONObject -> entry.opt("name") as? String
                else -> null
            }?.trim()
            if (name.isNullOrEmpty()) continue
            val c = JSONObject().put("name", name.take(Limits.MAX_SHORT))
            if (entry is JSONObject) (entry.opt("friendUserId") as? String)?.takeIf { it.isNotEmpty() }?.let { c.put("friendUserId", it) }
            out.put(c)
        }
        return out
    }

    // ── Copy planning ───────────────────────────────────────────────────────────────────────

    /** The library the archive is being restored into, for dedupe: `(tmdbId, type)` → existing title id. */
    class ExistingLibrary(val titleIdByKey: Map<Pair<Int, String>, String> = emptyMap())

    data class Report(
        val titlesNew: Int,
        val titlesSkippedExisting: Int,
        val titlesRejected: Int,
        val outingsNew: Int,
        val outingsRejected: Int,
        val listsNew: Int,
        val listItemsNew: Int,
        val listItemsDropped: Int,
        val untrustedFieldsDropped: Int,
        val unknownTopLevelKeysIgnored: Int,
        val warnings: List<Issue>,
        val rejections: List<Issue>,
        val listsRejected: Int = 0,
        /** Viewings + episode watch/rating/review rows of titles skipped as existing/duplicate (NOT restored). */
        val historyRowsOmittedForSkippedTitles: Int = 0,
        /** viewing.outingId references removed (target missing, rejected, ambiguous or of another title). */
        val viewingOutingRefsDropped: Int = 0,
        /** Non-history outings re-pointed at an existing / first-copy title. */
        val outingsRetargeted: Int = 0,
        /** Completed outings rejected because their history cannot be restored. */
        val completedOutingsRejected: Int = 0,
        /** Rows whose id appeared more than once in their table (all of them rejected). */
        val ambiguousIdRows: Int = 0,
        /** outing.completedViewingId references removed on non-completed outings. */
        val outingViewingRefsDropped: Int = 0,
        /** List items re-pointed at an existing / first-copy title. */
        val listItemsRetargeted: Int = 0,
    )

    class CopyPlan(
        /** Fully remapped rows to write (new titles only; existing duplicates are not repeated). */
        val titles: List<JSONObject>,
        val outings: List<JSONObject>,
        /** `{id,name,description,createdAt,updatedAt,items:[{titleId,position,addedAt}]}` with new ids. */
        val lists: List<JSONObject>,
        val report: Report,
    )

    private class TitleTarget(val id: String, val restored: Boolean)
    private class ViewingTarget(val freshId: String, val titleIdx: Int)
    private class OutingTarget(val freshId: String, val titleIdx: Int)
    private class ViewingRef(val copy: JSONObject, val oldOutingId: Any, val path: String, val titleIdx: Int)

    private class PlanState(val newId: () -> String, initialWarnings: List<Issue>) {
        var untrusted = 0
        val warnings = ArrayList<Issue>(initialWarnings)
        private val warned = HashSet<String>().also { set -> initialWarnings.forEach { set.add(it.path) } }
        fun warn(path: String, message: String) { warnings.add(Issue(path, message, false)) }
        fun warnOnce(path: String, message: String) { if (warned.add(path)) warnings.add(Issue(path, message, false)) }
    }

    /**
     * Pure "restore as copy" plan. Every identity (title, season, episode, watch/rating/review,
     * viewing, outing, list, physical-media copy) is replaced with a fresh id from [newId], and
     * every reference is rewritten through the same maps, so the restored graph is internally
     * consistent and shares no identity with the archive. A title whose `(tmdbId,type)` already
     * exists (or repeats earlier in the archive under a DIFFERENT id) is NOT restored again: its
     * histories are omitted and counted; only non-history references (list items, non-completed
     * outings) are re-pointed at the existing/first title, each counted and listed.
     * Never throws: an unexpected failure becomes a fatal `$` rejection.
     */
    fun planCopy(document: BackupDocument, existing: ExistingLibrary, newId: () -> String = { UUID.randomUUID().toString() }): CopyPlan = try {
        planCopyInternal(document, existing, newId)
    } catch (e: Exception) {
        failedPlan(document, e.javaClass.simpleName)
    } catch (e: StackOverflowError) {
        failedPlan(document, "StackOverflowError")
    }

    private fun failedPlan(document: BackupDocument, what: String) = CopyPlan(
        emptyList(), emptyList(), emptyList(),
        Report(
            titlesNew = 0, titlesSkippedExisting = 0, titlesRejected = document.titles.size,
            outingsNew = 0, outingsRejected = document.outings.size, listsNew = 0, listItemsNew = 0,
            listItemsDropped = 0, untrustedFieldsDropped = 0, unknownTopLevelKeysIgnored = document.extra.length(),
            warnings = emptyList(),
            rejections = listOf(Issue("$", "Unexpected error while planning the restore ($what).", true)),
            listsRejected = document.lists.size,
        ),
    )

    private fun emptyReport(a: Analysis, document: BackupDocument) = Report(
        titlesNew = 0, titlesSkippedExisting = 0, titlesRejected = document.titles.size,
        outingsNew = 0, outingsRejected = document.outings.size, listsNew = 0, listItemsNew = 0,
        listItemsDropped = 0, untrustedFieldsDropped = 0, unknownTopLevelKeysIgnored = document.extra.length(),
        warnings = a.issues.filter { !it.fatal }, rejections = a.issues.filter { it.fatal },
        listsRejected = document.lists.size,
    )

    private fun planCopyInternal(document: BackupDocument, existing: ExistingLibrary, newId: () -> String): CopyPlan {
        val a = analyze(document)
        if (a.documentFatal) return CopyPlan(emptyList(), emptyList(), emptyList(), emptyReport(a, document))
        val issues = a.issues
        val st = PlanState(newId, issues.filter { !it.fatal })
        val rejections = ArrayList<Issue>()
        // Fatal issues grouped by owning row ("titles[3]", "outings[1]", ...) so rejection lookup is O(1).
        val fatalByRow: Map<String, List<Issue>> = issues.filter { it.fatal }.groupBy { it.path.substringBefore('.') }
        fun under(path: String): List<Issue> = fatalByRow[path] ?: emptyList()

        val titleMap = HashMap<String, TitleTarget>()
        val viewingMap = HashMap<String, ViewingTarget>()
        val viewingRefs = ArrayList<ViewingRef>()
        val firstNewIdByKey = HashMap<Pair<Int, String>, String>()
        val titles = ArrayList<JSONObject>()
        var skipped = 0
        var titlesRejected = 0
        var historyOmitted = 0

        document.titles.forEachIndexed { i, t ->
            val path = "titles[$i]"
            if (a.titleFatal[i]) {
                titlesRejected++
                rejections.addAll(under(path))
                return@forEachIndexed
            }
            val key = (integral(t.opt("tmdbId")) ?: 0L).toInt() to (t.opt("type") as? String ?: "")
            val oldId = t.opt("id") as? String
            val existingId = existing.titleIdByKey[key]
            val redirect = existingId ?: firstNewIdByKey[key]
            if (redirect != null) {
                if (oldId != null) titleMap[oldId] = TitleTarget(redirect, false)
                skipped++
                val omitted = historyRows(t)
                historyOmitted += omitted
                val why = if (existingId != null) "already exists in the library" else "repeats an earlier title (same tmdbId and type) in this archive"
                st.warn(path, "Title $why and was skipped; $omitted history row(s) were not restored. Scheduled/missed/cancelled outings and list items that referenced it are re-pointed at the kept title.")
                return@forEachIndexed
            }
            val fresh = newId()
            if (oldId != null) titleMap[oldId] = TitleTarget(fresh, true)
            firstNewIdByKey[key] = fresh
            titles += admitTitle(t, i, path, fresh, st, viewingMap, viewingRefs)
        }

        val outingMap = HashMap<String, OutingTarget>()
        val outings = ArrayList<JSONObject>()
        var outingsRejected = 0
        var completedRejected = 0
        var retargeted = 0
        var cvDropped = 0
        document.outings.forEachIndexed { i, o ->
            val path = "outings[$i]"
            if (a.outingFatal[i]) {
                outingsRejected++
                if (a.completedRejected[i]) completedRejected++
                rejections.addAll(under(path))
                return@forEachIndexed
            }
            val target = titleMap[o.opt("titleId") as? String ?: ""]
            if (target == null) {
                outingsRejected++
                rejections += Issue(path, "Outing refers to a title that is not in the archive.", true)
                return@forEachIndexed
            }
            val titleIdx = a.outingTitleIdx[i]
            val completed = o.opt("status") == "completed"
            val cvRaw = o.opt("completedViewingId") as? String
            val vt = cvRaw?.let { viewingMap[it] }
            val vtOk = vt != null && vt.titleIdx == titleIdx
            if (completed && (!target.restored || !vtOk)) {
                outingsRejected++
                completedRejected++
                rejections += Issue(
                    path,
                    if (!target.restored) "Completed outing not restored: its title was skipped (already in the library or duplicated), so its viewing history is not restored."
                    else "Completed outing not restored: its viewing could not be resolved.",
                    true,
                )
                return@forEachIndexed
            }
            val copy = admitRow(o, OUTING_FIELDS, COMPANIONS_STRUCTURAL, st, path)
            val fresh = newId()
            copy.put("id", fresh)
            copy.put("titleId", target.id)
            copy.put("companions", normalizeCompanions(o.opt("companions")))
            copy.remove("completedViewingId")
            if (vtOk && vt != null) {
                copy.put("completedViewingId", vt.freshId)
            } else if (!isNull(o.opt("completedViewingId"))) {
                cvDropped++
                st.warnOnce("$path.completedViewingId", "completedViewingId references a viewing that is not restored; the reference was dropped.")
            }
            if (!target.restored) {
                retargeted++
                st.warn("$path.titleId", "Outing re-pointed at the existing/first copy of its title.")
            }
            (o.opt("id") as? String)?.let { outingMap[it] = OutingTarget(fresh, titleIdx) }
            outings += copy
        }

        var viewingRefsDropped = 0
        for (ref in viewingRefs) {
            ref.copy.remove("outingId")
            val ot = (ref.oldOutingId as? String)?.let { outingMap[it] }
            if (ot != null && ot.titleIdx == ref.titleIdx) {
                ref.copy.put("outingId", ot.freshId)
            } else {
                viewingRefsDropped++
                st.warnOnce(ref.path, "outingId does not reference a restored outing of the same title; the reference was dropped.")
            }
        }

        val lists = ArrayList<JSONObject>()
        var itemsNew = 0
        var itemsDropped = 0
        var itemsRetargeted = 0
        var listsRejected = 0
        document.lists.forEachIndexed { i, l ->
            val path = "lists[$i]"
            if (a.listFatal[i]) {
                listsRejected++
                rejections.addAll(under(path))
                return@forEachIndexed
            }
            val copy = admitRow(l, LIST_FIELDS, LIST_STRUCTURAL, st, path)
            copy.put("id", newId())
            val items = JSONArray()
            val seenTitle = HashSet<String>()
            val src = l.opt("items") as? JSONArray
            if (src != null) for (ii in 0 until src.length()) {
                val ip = "$path.items[$ii]"
                val item = src.opt(ii) as? JSONObject ?: continue
                val target = titleMap[item.opt("titleId") as? String ?: ""]
                if (target == null) {
                    itemsDropped++
                    st.warn(ip, "List item dropped: its title is not part of the restore.")
                    continue
                }
                if (!seenTitle.add(target.id)) {
                    itemsDropped++
                    st.warn(ip, "List item dropped: the list already contains that title.")
                    continue
                }
                val ci = admitRow(item, LIST_ITEM_FIELDS, emptySet(), st, ip, silent = setOf("id"))
                ci.put("titleId", target.id)
                if (!target.restored) {
                    itemsRetargeted++
                    st.warn(ip, "List item re-pointed at the existing/first copy of its title.")
                }
                items.put(ci)
                itemsNew++
            }
            copy.put("items", items)
            lists += copy
        }

        val report = Report(
            titlesNew = titles.size,
            titlesSkippedExisting = skipped,
            titlesRejected = titlesRejected,
            outingsNew = outings.size,
            outingsRejected = outingsRejected,
            listsNew = lists.size,
            listItemsNew = itemsNew,
            listItemsDropped = itemsDropped,
            untrustedFieldsDropped = st.untrusted,
            unknownTopLevelKeysIgnored = document.extra.length(),
            warnings = st.warnings,
            rejections = rejections,
            listsRejected = listsRejected,
            historyRowsOmittedForSkippedTitles = historyOmitted,
            viewingOutingRefsDropped = viewingRefsDropped,
            outingsRetargeted = retargeted,
            completedOutingsRejected = completedRejected,
            ambiguousIdRows = a.ambiguousIdRows,
            outingViewingRefsDropped = cvDropped,
            listItemsRetargeted = itemsRetargeted,
        )
        return CopyPlan(titles, outings, lists, report)
    }

    private fun historyRows(t: JSONObject): Int {
        var n = (t.opt("viewings") as? JSONArray)?.length() ?: 0
        val seasons = t.opt("seasons") as? JSONArray ?: return n
        for (si in 0 until seasons.length()) {
            val eps = (seasons.opt(si) as? JSONObject)?.opt("episodes") as? JSONArray ?: continue
            for (ei in 0 until eps.length()) {
                val e = eps.opt(ei) as? JSONObject ?: continue
                for (k in EPISODE_ROW_KEYS) n += (e.opt(k) as? JSONArray)?.length() ?: 0
            }
        }
        return n
    }

    private fun admitTitle(
        t: JSONObject,
        titleIdx: Int,
        path: String,
        fresh: String,
        st: PlanState,
        viewingMap: MutableMap<String, ViewingTarget>,
        viewingRefs: MutableList<ViewingRef>,
    ): JSONObject {
        val copy = admitRow(t, TITLE_FIELDS, TITLE_STRUCTURAL, st, path)
        copy.put("id", fresh)
        // URLs that later become links/images must be plain http(s): no javascript:, file:, content:, data: or credentials.
        for (k in listOf("posterUrl", "backdropUrl", "rtUrl", "customWatchUrl")) sanitizeUrl(copy, k, "$path.$k", st)
        for (k in listOf("cast", "crew")) sanitizeCreditUrls(copy, k, "$path.$k", st)
        // physicalMedia[].id is an owner-authored logical copy identity: regenerated, rest retained.
        val media = t.opt("physicalMedia")
        if (media is JSONArray) {
            val out = JSONArray()
            for (mi in 0 until media.length()) {
                val m = media.opt(mi)
                if (m is JSONObject) {
                    val mc = JSONObject()
                    for (k in keysOf(m)) {
                        if (k == "id") continue
                        if (isUntrusted(k)) { st.untrusted++; continue }
                        mc.put(k, deepStrip(m.opt(k), st, 0))
                    }
                    mc.put("id", st.newId())
                    out.put(mc)
                } else {
                    out.put(deepStrip(m, st, 0))
                }
            }
            copy.put("physicalMedia", out)
        } else if (!isNull(media)) {
            copy.put("physicalMedia", deepStrip(media, st, 0))
        }
        val seasonsSrc = t.opt("seasons") as? JSONArray
        if (seasonsSrc != null) {
            val seasons = JSONArray()
            for (si in 0 until seasonsSrc.length()) {
                val s = seasonsSrc.opt(si) as? JSONObject ?: continue
                seasons.put(admitSeason(s, "$path.seasons[$si]", st))
            }
            copy.put("seasons", seasons)
        }
        val viewings = JSONArray()
        val vsSrc = t.opt("viewings") as? JSONArray
        if (vsSrc != null) for (vi in 0 until vsSrc.length()) {
            val v = vsSrc.opt(vi) as? JSONObject ?: continue
            val vp = "$path.viewings[$vi]"
            val vc = admitRow(v, VIEWING_FIELDS, COMPANIONS_STRUCTURAL, st, vp)
            val freshViewing = st.newId()
            (v.opt("id") as? String)?.let { viewingMap[it] = ViewingTarget(freshViewing, titleIdx) }
            vc.put("id", freshViewing)
            vc.put("titleId", fresh)
            if (v.has("companions")) vc.put("companions", normalizeCompanions(v.opt("companions")))
            val oo = v.opt("outingId")
            if (!isNull(oo)) viewingRefs.add(ViewingRef(vc, oo!!, "$vp.outingId", titleIdx))
            viewings.put(vc)
        }
        copy.put("viewings", viewings)
        if (isNull(copy.opt("tags"))) copy.put("tags", JSONArray())
        return copy
    }

    private fun sanitizeCreditUrls(row: JSONObject, key: String, path: String, st: PlanState) {
        val arr = row.opt(key) as? JSONArray ?: return
        for (i in 0 until arr.length()) (arr.opt(i) as? JSONObject)?.let { sanitizeUrl(it, "profileUrl", "$path[$i].profileUrl", st) }
    }

    private fun sanitizeUrl(row: JSONObject, key: String, path: String, st: PlanState) {
        val v = row.opt(key) as? String ?: return
        val ok = v.length <= 2048 && try {
            val u = java.net.URI(v)
            (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty() && u.userInfo == null
        } catch (_: Exception) { false }
        if (!ok) {
            row.remove(key)
            st.untrusted++
            st.warn(path, "Unsafe or malformed URL removed.")
        }
    }

    private fun admitSeason(s: JSONObject, path: String, st: PlanState): JSONObject {
        val copy = admitRow(s, SEASON_FIELDS, SEASON_STRUCTURAL, st, path)
        sanitizeCreditUrls(copy, "cast", "$path.cast", st)
        copy.put("id", st.newId())
        val eps = s.opt("episodes") as? JSONArray ?: return copy
        val out = JSONArray()
        for (ei in 0 until eps.length()) {
            val e = eps.opt(ei) as? JSONObject ?: continue
            val ep = "$path.episodes[$ei]"
            val ec = admitRow(e, EPISODE_FIELDS, EPISODE_STRUCTURAL, st, ep)
            sanitizeUrl(ec, "stillUrl", "$ep.stillUrl", st)
            ec.put("id", st.newId())
            for (key in EPISODE_ROW_KEYS) {
                val rows = e.opt(key) as? JSONArray ?: continue
                val allowed = when (key) { "watchEvents" -> WATCH_FIELDS; "ratings" -> RATING_FIELDS; else -> REVIEW_FIELDS }
                val rowsOut = JSONArray()
                for (ri in 0 until rows.length()) {
                    val r = rows.opt(ri) as? JSONObject ?: continue
                    val rc = admitRow(r, allowed, emptySet(), st, "$ep.$key[$ri]")
                    rc.put("id", st.newId())
                    rowsOut.put(rc)
                }
                ec.put(key, rowsOut)
            }
            out.put(ec)
        }
        copy.put("episodes", out)
        return copy
    }

    /**
     * Builds an admitted row: allowlisted keys are copied (untrusted keys removed at any depth),
     * [structural] keys are skipped (the caller rebuilds them), every other key moves verbatim into
     * a single inert `ext` object. An archive `ext` object is merged in (and sanitized the same way).
     */
    private fun admitRow(src: JSONObject, allowed: Set<String>, structural: Set<String>, st: PlanState, path: String, silent: Set<String> = emptySet()): JSONObject {
        val out = JSONObject()
        val unknown = LinkedHashMap<String, Any>()
        val archiveExt = src.opt("ext") as? JSONObject
        for (key in keysOf(src)) {
            if (key == "ext" && archiveExt != null) continue
            if (isUntrusted(key)) { st.untrusted++; continue }
            if (key in silent || key in structural) continue
            val value = deepStrip(src.opt(key), st, 0)
            if (key in allowed) out.put(key, value) else unknown[key] = value
        }
        var ext: JSONObject? = null
        if (archiveExt != null) ext = deepStrip(archiveExt, st, 0) as JSONObject
        if (unknown.isNotEmpty()) {
            val target = ext ?: JSONObject()
            for ((k, v) in unknown) {
                if (target.has(k)) st.warn("$path.ext.$k", "ext key collided with an unknown top-level field; the top-level value was kept.")
                target.put(k, v)
            }
            ext = target
        }
        if (ext != null && ext.length() > 0) out.put("ext", ext)
        return out
    }

    /** Deep copy without [UNTRUSTED_ROW_KEYS] (counted). Depth-bounded. */
    private fun deepStrip(v: Any?, st: PlanState, depth: Int): Any {
        if (depth > Limits.MAX_DEPTH + 8) throw IllegalStateException("nesting too deep")
        return when (v) {
            null -> JSONObject.NULL
            is JSONObject -> {
                val o = JSONObject()
                for (k in keysOf(v)) {
                    if (isUntrusted(k)) { st.untrusted++; continue }
                    o.put(k, deepStrip(v.opt(k), st, depth + 1))
                }
                o
            }
            is JSONArray -> {
                val a = JSONArray()
                for (i in 0 until v.length()) a.put(deepStrip(v.opt(i), st, depth + 1))
                a
            }
            else -> v ?: JSONObject.NULL
        }
    }
}
