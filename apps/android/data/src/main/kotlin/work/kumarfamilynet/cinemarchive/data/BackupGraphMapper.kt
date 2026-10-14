package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat

internal data class BackupGraphMapping(val graph: LibraryExportGraph, val operations: JSONArray)

/** Maps one reviewed, freshly identified graph. Any unrepresentable value rejects this title,
 * before either its local rows or its atomic server command can be admitted. */
internal fun mapBackupGraph(title: JSONObject, outings: List<JSONObject>, admittedAt: String): BackupGraphMapping {
    val mapping = BackupRestoreMapper.mapTitle(title, 0, admittedAt)
    val unsupported = mapping.issues.filter { it.kind != BackupRestoreMapper.Kind.DEFERRED &&
        !(it.kind == BackupRestoreMapper.Kind.UNMAPPED && it.path.startsWith("titles[0].physicalMedia[")) }
    require(unsupported.isEmpty()) { unsupported.joinToString("; ") { "${it.path}: ${it.message}" } }
    val entity = requireNotNull(mapping.entity).copy(updatedAt = "") // No invented server CAS revision.
    require((entity.genres + entity.tags + entity.studios).all { it.isNotBlank() && '|' !in it }) {
        "A genre, tag or studio cannot be represented by this device's list storage. The original file is unchanged."
    }
    val titleId = entity.id
    val ops = JSONArray().put(requireNotNull(mapping.operation))
    val seasons = mutableListOf<SeasonEntity>(); val episodes = mutableListOf<EpisodeEntity>()
    val watches = mutableListOf<EpisodeWatchEventEntity>(); val ratings = mutableListOf<EpisodeRatingEntity>()
    val reviews = mutableListOf<EpisodeReviewEntity>(); val viewings = mutableListOf<ViewingEntity>()
    val cast = mutableListOf<TitleCastEntity>(); val crew = mutableListOf<TitleCrewEntity>()
    val seasonCast = mutableListOf<SeasonCastEntity>(); val episodeCrew = mutableListOf<EpisodeCrewEntity>()
    val outingRows = mutableListOf<CinemaOutingEntity>()

    fun insert(table: String, id: String, values: JSONObject) { ops.put(importOperation(table, JSONObject().put("id", id), values)) }
    fun castRows(source: JSONArray, seasonId: String? = null) {
        val seen = mutableSetOf<Int>()
        source.importObjects().forEach { person ->
            person.importFields("tmdbPersonId", "name", "character", "order", "profileUrl", "episodeCount")
            val personId = person.importInt("tmdbPersonId", 1)
            require(seen.add(personId)) { "Duplicate cast identity cannot be restored without losing a credit." }
            val name = person.importText("name")!!; val character = person.importText("character")
            val order = person.importInt("order", 0, default = 0); val profile = person.importText("profileUrl")
            val count = person.importNullableInt("episodeCount")
            val table = if (seasonId == null) "title_cast" else "season_cast"
            val key = JSONObject().put(if (seasonId == null) "title_id" else "season_id", seasonId ?: titleId).put("tmdb_person_id", personId)
            val values = importValues("name" to name, "character_name" to character, "cast_order" to order,
                "profile_url" to profile, "episode_count" to count)
            if (seasonId != null) values.put("title_id", titleId)
            ops.put(importOperation(table, key, values))
            val id = importCreditId(table, key)
            if (seasonId == null) cast += TitleCastEntity(id, titleId, personId, name, character, order, profile, count)
            else seasonCast += SeasonCastEntity(id, titleId, seasonId, personId, name, character, order, profile, count)
        }
    }
    fun crewRows(source: JSONArray, episodeId: String? = null) {
        val seen = mutableSetOf<Pair<Int, String>>()
        source.importObjects().forEach { person ->
            person.importFields(*(if (episodeId == null) arrayOf("tmdbPersonId", "name", "job", "department", "profileUrl") else arrayOf("tmdbPersonId", "name", "job")))
            val personId = person.importInt("tmdbPersonId", 1); val name = person.importText("name")!!; val job = person.importText("job")!!
            require(seen.add(personId to job)) { "Duplicate crew identity cannot be restored without losing a credit." }
            val table = if (episodeId == null) "title_crew" else "episode_crew"
            val key = JSONObject().put(if (episodeId == null) "title_id" else "episode_id", episodeId ?: titleId).put("tmdb_person_id", personId).put("job", job)
            val values = JSONObject().put("name", name)
            if (episodeId != null) values.put("title_id", titleId)
            else { values.put("department", person.importText("department") ?: JSONObject.NULL); values.put("profile_url", person.importText("profileUrl") ?: JSONObject.NULL) }
            ops.put(importOperation(table, key, values))
            val id = importCreditId(table, key)
            if (episodeId == null) crew += TitleCrewEntity(id, titleId, personId, name, job, person.importText("department"), person.importText("profileUrl"))
            else episodeCrew += EpisodeCrewEntity(id, titleId, episodeId, personId, name, job)
        }
    }
    castRows(title.importArray("cast")); crewRows(title.importArray("crew"))
    val seasonNumbers = mutableSetOf<Int>()
    title.importArray("seasons").importObjects().forEach { season ->
        season.importFields("id", "seasonNumber", "episodeCount", "episodesWatched", "airYear", "cast", "episodes")
        val seasonId = season.importId(); val number = season.importInt("seasonNumber", 0)
        require(seasonNumbers.add(number)) { "Duplicate season number cannot be restored without losing history." }
        val count = season.importInt("episodeCount", 0); val watched = season.importInt("episodesWatched", 0, default = 0)
        require(watched <= count) { "Watched episode count exceeds the season size." }
        val airYear = season.importNullableInt("airYear")
        seasons += SeasonEntity(seasonId, titleId, number, count, watched, airYear)
        insert("seasons", seasonId, importValues("title_id" to titleId, "season_number" to number, "episode_count" to count, "episodes_watched" to watched, "air_year" to airYear))
        castRows(season.importArray("cast"), seasonId)
        val episodeNumbers = mutableSetOf<Int>()
        season.importArray("episodes").importObjects().forEach { episode ->
            episode.importFields("id", "episodeNumber", "episodeName", "airDate", "runtime", "synopsis", "stillUrl", "director", "writers", "crew", "watchEvents", "ratings", "reviews")
            val id = episode.importId(); val epNumber = episode.importInt("episodeNumber", 1)
            require(episodeNumbers.add(epNumber)) { "Duplicate episode number cannot be restored without losing history." }
            val epName = episode.importText("episodeName"); val air = episode.importDate("airDate")
            val runtime = episode.importNullableInt("runtime", 0); val synopsis = episode.importText("synopsis"); val still = episode.importText("stillUrl")
            episodes += EpisodeEntity(id, titleId, seasonId, epNumber, epName, air, runtime, synopsis, still)
            insert("episodes", id, importValues("title_id" to titleId, "season_number" to number, "episode_number" to epNumber,
                "episode_name" to epName, "air_date" to air, "runtime" to runtime, "synopsis" to synopsis, "still_url" to still))
            crewRows(episode.importArray("crew"), id)
            val credits = episodeCrew.filter { it.episodeId == id }
            episode.importText("director")?.let { require(it == credits.firstOrNull { person -> person.job == "Director" }?.name) { "Episode director has no matching person credit; it cannot be preserved." } }
            if (episode.has("writers")) require(episode.importStrings("writers") == credits.filter { it.job in setOf("Writer", "Screenplay", "Story", "Teleplay") }.map { it.name }.distinct()) { "Episode writers have no matching person credits; they cannot be preserved." }
            episode.importArray("watchEvents").importObjects().forEach { event ->
                event.importFields("id", "watchedAt", "notes", "colorMode")
                val watch = EpisodeWatchEventEntity(event.importId(), id, event.importDate("watchedAt"), event.importText("notes"), event.importColor())
                watches += watch
                insert("episode_watch_events", watch.id, importValues("episode_id" to id, "watched_at" to watch.watchedAt, "notes" to watch.notes, "color_mode" to watch.colorMode, "created_at" to admittedAt))
            }
            episode.importArray("ratings").importObjects().forEach { rating ->
                rating.importFields("id", "rating", "ratedAt")
                val row = EpisodeRatingEntity(rating.importId(), id, requireNotNull(rating.importDecimal("rating", 1, 0.0, 5.0)), requireNotNull(rating.importInstant("ratedAt")))
                ratings += row
                insert("episode_ratings", row.id, importValues("episode_id" to id, "rating" to row.rating, "rated_at" to row.ratedAt))
            }
            episode.importArray("reviews").importObjects().forEach { review ->
                review.importFields("id", "reviewText", "reviewedAt", "colorMode")
                val row = EpisodeReviewEntity(review.importId(), id, requireNotNull(review.importText("reviewText")), requireNotNull(review.importInstant("reviewedAt")), review.importColor())
                reviews += row
                insert("episode_reviews", row.id, importValues("episode_id" to id, "review_text" to row.reviewText, "reviewed_at" to row.reviewedAt, "color_mode" to row.colorMode))
            }
        }
    }
    outings.forEach { outing ->
        outing.importFields("id", "titleId", "showtime", "previewsMinutes", "runtimeMinutes", "endsAt", "venue", "companions", "format", "ticketPrice", "seat", "auditorium", "seatRow", "seats", "bookingRef", "notes", "status", "previousStatus", "completedViewingId", "followUpDismissedAt", "createdAt", "ticketBarcodePayload", "ticketBarcodeFormat")
        require(outing.importText("titleId") == titleId)
        val id = outing.importId(); val names = outing.importCompanions()
        val status = requireNotNull(outing.importText("status")).also { require(it in setOf("scheduled", "completed", "missed", "cancelled")) }
        val previous = outing.importText("previousStatus")?.also { require(it in setOf("watchlist", "watching", "watched", "dropped")) }
        val completed = outing.importText("completedViewingId")?.also(UUID::fromString)
        require(outing.importStrings("seats").all { it.isNotBlank() && '|' !in it }) { "A seat cannot be represented by this device's list storage." }
        val row = CinemaOutingEntity(id, titleId, requireNotNull(outing.importInstant("showtime")), outing.importInt("previewsMinutes", 0, 120, 20),
            outing.importInt("runtimeMinutes", 1), requireNotNull(outing.importInstant("endsAt")), outing.importText("venue"), companionNames(names),
            outing.importText("format")?.let { CinemaFormat.fromWire(it)?.wireValue ?: it }, outing.importDecimal("ticketPrice", 2, 0.0, 9999.99),
            outing.importText("seat"), outing.importText("auditorium"), outing.importText("seatRow"), outing.importStrings("seats"), outing.importText("bookingRef"),
            ticketBarcodePayload = outing.importText("ticketBarcodePayload"), ticketBarcodeFormat = outing.importText("ticketBarcodeFormat"),
            notes = outing.importText("notes"), status = status.uppercase(), previousStatus = previous?.uppercase(), completedViewingId = completed,
            followUpDismissedAt = outing.importInstant("followUpDismissedAt"), createdAt = requireNotNull(outing.importInstant("createdAt")), updatedAt = "",
            companionsJson = names.toString())
        require(Instant.parse(row.endsAt).isAfter(Instant.parse(row.showtime))) { "An outing must end after its showtime." }
        outingRows += row
        val values = outingWireBody(row.mutationPayload(), "unused", true).apply {
            remove("id"); remove("user_id"); remove("updated_at"); remove("completed_viewing_id"); remove("ticket_image_path")
        }
        insert("cinema_outings", id, values)
    }
    title.importArray("viewings").importObjects().forEach { viewing ->
        viewing.importFields("id", "titleId", "date", "rating", "notes", "venue", "companions", "outingId")
        require(viewing.importText("titleId") == null || viewing.importText("titleId") == titleId)
        val companions = viewing.importCompanions(); val outingId = viewing.importText("outingId")
        require(outingId == null || outingRows.any { it.id == outingId }) { "Viewing refers to an outing outside this imported graph." }
        val row = ViewingEntity(viewing.importId(), titleId, viewing.importDate("date"), viewing.importDecimal("rating", 1, 0.0, 5.0),
            viewing.importText("notes"), viewing.importText("venue"), companionNames(companions), outingId, companionsJson = companions.toString())
        viewings += row
        insert("viewings", row.id, importValues("title_id" to titleId, "viewed_at" to row.date, "rating" to row.rating, "notes" to row.notes,
            "venue" to row.venue, "companions" to companions, "outing_id" to outingId, "created_at" to admittedAt))
    }
    outingRows.forEach { row ->
        row.completedViewingId?.let { viewing ->
            require(viewings.any { it.id == viewing }) { "Completed outing refers to another title's history." }
            ops.put(importOperation("cinema_outings", JSONObject().put("id", row.id), JSONObject().put("completed_viewing_id", viewing), "update"))
        }
    }
    val graph = LibraryExportGraph(listOf(entity), seasons, episodes, watches, ratings, reviews, viewings, cast, crew, seasonCast, episodeCrew, outingRows)
    assertImportOperations(ops)
    return BackupGraphMapping(graph, ops)
}

internal fun assertImportOperations(ops: JSONArray) {
    require(ops.length() in 1..50_000) { "One title exceeds the 50,000-operation atomic restore limit." }
    require(ops.toString(2).toByteArray(Charsets.UTF_8).size.toLong() + importNumericExpansion(ops) + 128L * ops.length() <= 16L * 1024 * 1024) { "One title exceeds the 16 MiB atomic restore limit." }
}

internal fun importOperation(table: String, key: JSONObject, values: JSONObject, action: String = "insert") =
    JSONObject().put("table", table).put("action", action).put("key", key).put("values", values)

/** PostgreSQL JSONB expands exponents and uses numeric's finite decimal bounds. */
internal fun importNumericExpansion(value: Any?): Long = when (value) {
    is JSONObject -> value.keys().asSequence().sumOf { importNumericExpansion(value.get(it)) }
    is JSONArray -> (0 until value.length()).sumOf { importNumericExpansion(value.get(it)) }
    is Number -> {
        val number = value.toString().toBigDecimal().stripTrailingZeros()
        val scale = number.scale().toLong()
        val precision = number.precision().toLong()
        require(scale <= 16_383 && precision - scale <= 131_072) { "A physical-copy number exceeds the server's exact decimal range." }
        val expanded = if (scale <= 0) precision - scale else maxOf(precision, scale) + 2
        maxOf(0L, expanded + (if (number.signum() < 0) 1 else 0) - value.toString().length)
    }
    else -> 0L
}
internal fun importValues(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
internal fun importCreditId(table: String, key: JSONObject): String = UUID.nameUUIDFromBytes((table + LibraryBackupCodec.encodeJsonValue(key)).toByteArray(Charsets.UTF_8)).toString()
internal fun JSONArray.importObjects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
internal fun JSONObject.importArray(key: String): JSONArray = if (!has(key) || isNull(key)) JSONArray() else getJSONArray(key)
private fun JSONObject.importFields(vararg allowed: String) { require(keys().asSequence().all { it in allowed }) { "Unsupported fields: ${keys().asSequence().filter { it !in allowed }.joinToString()}" } }
private fun JSONObject.importId(): String = requireNotNull(importText("id")).also { require(UUID.fromString(it).toString() == it) }
private fun JSONObject.importText(key: String): String? = if (!has(key) || isNull(key)) null else get(key).let {
    require(it is String && '\u0000' !in it && Charsets.UTF_8.newEncoder().canEncode(it)) { "$key must be valid text without NUL." }; it
}
private fun JSONObject.importInt(key: String, min: Int, max: Int = Int.MAX_VALUE, default: Int? = null): Int =
    if ((!has(key) || isNull(key)) && default != null) default else get(key).let {
        require(it is Number); it.toString().toBigDecimal().intValueExact().also { value -> require(value in min..max) { "$key is outside $min..$max." } }
    }
private fun JSONObject.importNullableInt(key: String, min: Int = Int.MIN_VALUE): Int? = if (!has(key) || isNull(key)) null else importInt(key, min)
private fun JSONObject.importStrings(key: String): List<String> = importArray(key).let { a -> (0 until a.length()).map { a.get(it).also { value -> require(value is String && '\u0000' !in value && Charsets.UTF_8.newEncoder().canEncode(value)) } as String } }
private fun JSONObject.importDate(key: String): String? = importText(key)?.also { require(it.length == 10); LocalDate.parse(it) }
private fun JSONObject.importInstant(key: String): String? = importText(key)?.also { Instant.parse(it) }
private fun JSONObject.importDecimal(key: String, scale: Int, min: Double, max: Double): Double? = if (!has(key) || isNull(key)) null else get(key).let {
    require(it is Number); val value = it.toString().toBigDecimal().stripTrailingZeros()
    require(value.scale() <= scale && value.toDouble() in min..max) { "$key cannot be stored without rounding." }; value.toDouble()
}
private fun JSONObject.importColor(): String? = importText("colorMode")?.also { require(it in setOf("bw", "color")) }
private fun JSONObject.importCompanions(): JSONArray = importArray("companions").also { values -> values.importObjects().forEach {
    it.importFields("name", "friendUserId"); require(!it.importText("name").isNullOrBlank()); it.importText("friendUserId")?.let(UUID::fromString)
} }
