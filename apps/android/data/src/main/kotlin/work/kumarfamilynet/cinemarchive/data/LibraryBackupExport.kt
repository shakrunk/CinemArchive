package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat

/** One transaction's owner-local graph, including its optimistic pending edits. */
internal data class LibraryExportGraph(
    val titles: List<TitleEntity>,
    val seasons: List<SeasonEntity> = emptyList(),
    val episodes: List<EpisodeEntity> = emptyList(),
    val watches: List<EpisodeWatchEventEntity> = emptyList(),
    val ratings: List<EpisodeRatingEntity> = emptyList(),
    val reviews: List<EpisodeReviewEntity> = emptyList(),
    val viewings: List<ViewingEntity> = emptyList(),
    val cast: List<TitleCastEntity> = emptyList(),
    val crew: List<TitleCrewEntity> = emptyList(),
    val seasonCast: List<SeasonCastEntity> = emptyList(),
    val episodeCrew: List<EpisodeCrewEntity> = emptyList(),
    val outings: List<CinemaOutingEntity> = emptyList(),
    /** Presence with null represents a managed clear, never a legacy barcode fallback. */
    val ticketBarcodes: Map<String, JSONObject?> = emptyMap(),
)

class CompanionRecoveryRequired : IllegalStateException(
    "Some older viewing or outing companions have not been recovered yet. Sync to recover their friend links, then export again. If a saved change needs review, resolve it first. Your library has not been changed.",
)

/** Use the shipped web v1 envelope, not the codec's optional v2 archive format. */
internal fun LibraryExportGraph.exportDocument(day: String): JSONObject {
    val titleIds = titles.mapTo(mutableSetOf()) { it.id }
    val seasonOwners = seasons.associate { it.id to it.titleId }
    val episodeOwners = episodes.associate { it.id to it.titleId }
    check(seasons.all { it.titleId in titleIds } && episodes.all { it.titleId in titleIds && seasonOwners[it.seasonId] == it.titleId } &&
        viewings.all { it.titleId in titleIds } && outings.all { it.titleId in titleIds } &&
        cast.all { it.titleId in titleIds } && crew.all { it.titleId in titleIds } &&
        seasonCast.all { seasonOwners[it.seasonId] == it.titleId } && episodeCrew.all { episodeOwners[it.episodeId] == it.titleId } &&
        watches.all { it.episodeId in episodeOwners } && ratings.all { it.episodeId in episodeOwners } && reviews.all { it.episodeId in episodeOwners }) {
        "Some saved library records no longer match their title. Sync or resolve pending changes before exporting."
    }
    if (viewings.any { it.companions.isNotEmpty() && it.companionsJson == null } ||
        outings.any { it.companions.isNotEmpty() && it.companionsJson == null }) throw CompanionRecoveryRequired()
    val seasonsByTitle = seasons.groupBy { it.titleId }
    val episodesBySeason = episodes.groupBy { it.seasonId }
    val watchesByEpisode = watches.groupBy { it.episodeId }
    val ratingsByEpisode = ratings.groupBy { it.episodeId }
    val reviewsByEpisode = reviews.groupBy { it.episodeId }
    val viewingsByTitle = viewings.groupBy { it.titleId }
    val castByTitle = cast.groupBy { it.titleId }
    val crewByTitle = crew.groupBy { it.titleId }
    val castBySeason = seasonCast.groupBy { it.seasonId }
    val crewByEpisode = episodeCrew.groupBy { it.episodeId }
    val titleRows = titles.map { title -> title.exportTitle().apply {
        put("cast", JSONArray(castByTitle[title.id].orEmpty().sortedBy { it.castOrder }.map {
            exportCast(it.tmdbPersonId, it.name, it.characterName, it.castOrder, it.profileUrl, it.episodeCount)
        }))
        put("crew", JSONArray(crewByTitle[title.id].orEmpty().map { json(
            "tmdbPersonId" to it.tmdbPersonId, "name" to it.name, "job" to it.job,
            "department" to it.department, "profileUrl" to it.profileUrl,
        ) }))
        put("viewings", JSONArray(viewingsByTitle[title.id].orEmpty().map { json(
            "id" to it.id, "titleId" to it.titleId, "date" to it.date, "rating" to it.rating,
            "notes" to it.notes, "venue" to it.venue, "outingId" to it.outingId,
            "companions" to companionObjects(it.companionsJson, it.companions),
        ) }))
        if (title.type.equals("TV", ignoreCase = true) || seasonsByTitle.containsKey(title.id)) {
            put("seasons", JSONArray(seasonsByTitle[title.id].orEmpty().sortedBy { it.seasonNumber }.map { season ->
                json("id" to season.id, "seasonNumber" to season.seasonNumber, "episodeCount" to season.episodeCount,
                    "episodesWatched" to season.episodesWatched, "airYear" to season.airYear,
                    "cast" to JSONArray(castBySeason[season.id].orEmpty().sortedBy { it.castOrder }.map {
                        exportCast(it.tmdbPersonId, it.name, it.characterName, it.castOrder, it.profileUrl, it.episodeCount)
                    }),
                    "episodes" to JSONArray(episodesBySeason[season.id].orEmpty().sortedBy { it.episodeNumber }.map { episode ->
                        val credits = crewByEpisode[episode.id].orEmpty()
                        json("id" to episode.id, "episodeNumber" to episode.episodeNumber, "episodeName" to episode.episodeName,
                            "airDate" to episode.airDate, "runtime" to episode.runtime, "synopsis" to episode.synopsis,
                            "stillUrl" to episode.stillUrl, "director" to credits.firstOrNull { it.job == "Director" }?.name,
                            "writers" to JSONArray(credits.filter { it.job in setOf("Writer", "Screenplay", "Story", "Teleplay") }.map { it.name }.distinct()),
                            "crew" to JSONArray(credits.map { json("tmdbPersonId" to it.tmdbPersonId, "name" to it.name, "job" to it.job) }),
                            "watchEvents" to JSONArray(watchesByEpisode[episode.id].orEmpty().map {
                                json("id" to it.id, "watchedAt" to it.watchedAt, "notes" to it.notes, "colorMode" to it.colorMode)
                            }),
                            "ratings" to JSONArray(ratingsByEpisode[episode.id].orEmpty().map {
                                json("id" to it.id, "rating" to it.rating, "ratedAt" to it.ratedAt)
                            }),
                            "reviews" to JSONArray(reviewsByEpisode[episode.id].orEmpty().map {
                                json("id" to it.id, "reviewText" to it.reviewText, "reviewedAt" to it.reviewedAt, "colorMode" to it.colorMode)
                            }),
                        )
                    }),
                )
            }))
        }
    } }
    return json("version" to 1, "exportedAt" to day, "titles" to JSONArray(titleRows),
        "outings" to JSONArray(outings.map { outing -> outing.exportOuting().apply {
            if (ticketBarcodes.containsKey(outing.id)) {
                remove("ticketBarcodePayload"); remove("ticketBarcodeFormat")
                ticketBarcodes[outing.id]?.let {
                    put("ticketBarcodePayload", it.getString("payload")); put("ticketBarcodeFormat", it.getString("format"))
                }
            }
        } }),
    )
}

private fun TitleEntity.exportTitle() = json(
    "id" to id, "tmdbId" to tmdbId, "type" to type.lowercase(), "title" to title, "year" to year,
    "director" to director, "genres" to JSONArray(genres), "posterUrl" to posterUrl, "backdropUrl" to backdropUrl,
    "synopsis" to synopsis, "runtime" to runtime, "network" to network, "status" to status.lowercase(),
    "rating" to rating, "notes" to notes, "tags" to JSONArray(tags), "addedAt" to addedAt,
    "releaseDate" to releaseDate, "originalLanguage" to originalLanguage, "contentRating" to contentRating,
    "imdbId" to imdbId, "rtUrl" to rtUrl, "customWatchUrl" to customWatchUrl, "inHomeCollection" to inHomeCollection,
    "physicalMedia" to physicalMediaJson?.let(::exactExportArray), "collectionId" to collectionId,
    "collectionName" to collectionName, "imdbRating" to imdbRating, "rtScore" to rtScore,
    "metacriticScore" to metacriticScore, "awardsCount" to awardsCount, "bechdelOutcome" to bechdelOutcome,
    "bechdelScore" to bechdelScore, "studios" to JSONArray(studios),
)

private fun CinemaOutingEntity.exportOuting() = json(
    "id" to id, "titleId" to titleId, "showtime" to showtime, "previewsMinutes" to previewsMinutes,
    "runtimeMinutes" to runtimeMinutes, "endsAt" to endsAt, "venue" to venue,
    "companions" to companionObjects(companionsJson, companions),
    "format" to format?.let { CinemaFormat.fromWire(it)?.wireValue ?: it }, "ticketPrice" to ticketPrice,
    "seat" to seat, "auditorium" to auditorium, "seatRow" to seatRow, "seats" to JSONArray(seats),
    "bookingRef" to bookingRef, "ticketBarcodePayload" to ticketBarcodePayload, "ticketBarcodeFormat" to ticketBarcodeFormat,
    "notes" to notes, "status" to status.lowercase(), "previousStatus" to previousStatus?.lowercase(),
    "completedViewingId" to completedViewingId, "followUpDismissedAt" to followUpDismissedAt, "createdAt" to createdAt,
)

private fun exportCast(id: Int, name: String, character: String?, order: Int, profile: String?, count: Int?) =
    json("tmdbPersonId" to id, "name" to name, "character" to character, "order" to order, "profileUrl" to profile, "episodeCount" to count)

private fun json(vararg fields: Pair<String, Any?>) = JSONObject().apply {
    fields.forEach { (key, value) -> if (value != null) put(key, value) }
}

/** Reuse the codec's exact-number parser; org.json would round unknown numeric metadata. */
private fun exactExportArray(raw: String): JSONArray {
    val parsed = LibraryBackupCodec.parse("{\"titles\":[],\"value\":$raw}")
    check(parsed is LibraryBackupCodec.ParseResult.Success) { "Saved physical media could not be exported safely." }
    return parsed.document.extra.getJSONArray("value")
}
