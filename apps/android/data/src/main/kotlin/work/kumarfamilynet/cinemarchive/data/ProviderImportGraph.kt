package work.kumarfamilynet.cinemarchive.data

import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.*

internal data class ProviderTitleLink(val provider: SyncProvider, val externalId: String) {
    init {
        require(externalId.isNotBlank() && '\u0000' !in externalId && Charsets.UTF_8.newEncoder().canEncode(externalId)) {
            "The provider item has no valid stable identity."
        }
    }
    fun json() = JSONObject().put("provider", provider.wire).put("externalId", externalId)
}

internal fun checkedProviderLinks(values: JSONArray): List<ProviderTitleLink> {
    val links = values.importObjects().map {
        require(it.keys().asSequence().toSet() == setOf("provider", "externalId"))
        require(it.opt("provider") is String && it.opt("externalId") is String)
        ProviderTitleLink(requireNotNull(SyncProvider.fromWire(it.getString("provider"))), it.getString("externalId"))
    }
    require(links.distinct().size == links.size) { "Duplicate provider identities in one import." }
    return links
}

internal fun providerLinkOperation(link: ProviderTitleLink, titleId: String) = importOperation("external_title_links",
    JSONObject().put("provider", link.provider.wire).put("external_id", link.externalId),
    JSONObject().put("title_id", titleId))

/** Provider imports retain fetched catalog children but invent no episode watch history.
 * A watched movie gets only the dates actually supplied; TV has no movie-style viewing. */
internal fun providerImportTitle(details: MediaDetails, item: SyncItem, now: String): JSONObject {
    require(details.type == item.type && details.tmdbId > 0)
    item.rating?.let { require(it.isFinite() && it in 0.0..5.0 && it.toBigDecimal().stripTrailingZeros().scale() <= 1) }
    val title = catalogImportTitle(details, item.status, item.rating, now)
    val titleId = title.getString("id")
    val dates = item.watchedDates.distinct().sorted().onEach { require(it.length == 10); LocalDate.parse(it) }
    val viewings = if (item.type == MediaType.MOVIE && item.status == LibraryStatus.WATCHED) dates.map { date ->
        importValues("id" to UUID.randomUUID().toString(), "titleId" to titleId, "date" to date,
            "rating" to item.rating.takeIf { date == dates.lastOrNull() }, "companions" to JSONArray())
    } else emptyList()
    return title.put("viewings", JSONArray(viewings))
}

internal fun catalogImportTitle(details: MediaDetails, status: LibraryStatus, rating: Double?, now: String): JSONObject {
    val titleId = UUID.randomUUID().toString()
    fun cast(rows: List<MediaCredit>) = JSONArray(rows.distinctBy { it.tmdbPersonId }.map {
        importValues("tmdbPersonId" to it.tmdbPersonId, "name" to it.name, "character" to it.characterName, "order" to it.order,
            "profileUrl" to it.profileUrl, "episodeCount" to it.episodeCount)
    })
    fun crew(rows: List<MediaCrewCredit>, episode: Boolean = false) = JSONArray(rows.distinctBy { it.tmdbPersonId to it.job }.map {
        importValues("tmdbPersonId" to it.tmdbPersonId, "name" to it.name, "job" to it.job).apply {
            if (!episode) {
                put("department", it.department ?: JSONObject.NULL)
                put("profileUrl", it.profileUrl ?: JSONObject.NULL)
            }
        }
    })
    return importValues("id" to titleId, "tmdbId" to details.tmdbId, "type" to details.type.name.lowercase(),
        "title" to details.title, "year" to (details.year ?: 0), "status" to status.name.lowercase(),
        "rating" to rating, "addedAt" to now, "tags" to JSONArray(), "genres" to JSONArray(details.genres),
        "director" to details.director, "posterUrl" to details.posterUrl, "backdropUrl" to details.backdropUrl,
        "synopsis" to details.synopsis, "runtime" to details.runtime, "network" to details.network,
        "releaseDate" to details.releaseDate, "originalLanguage" to details.originalLanguage,
        "contentRating" to details.contentRating, "imdbId" to details.imdbId, "studios" to JSONArray(details.studios),
        "collectionId" to details.collectionId, "collectionName" to details.collectionName,
        "imdbRating" to details.imdbRating, "rtScore" to details.rtScore, "metacriticScore" to details.metacriticScore,
        "rtUrl" to details.rtUrl, "awardsCount" to details.awardsCount,
        "bechdelOutcome" to details.bechdelOutcome, "bechdelScore" to details.bechdelScore,
        "cast" to cast(details.cast), "crew" to crew(details.crew), "viewings" to JSONArray(),
        "seasons" to JSONArray(details.seasons.map { season ->
            importValues("id" to UUID.randomUUID().toString(), "seasonNumber" to season.seasonNumber,
                "episodeCount" to season.episodeCount, "episodesWatched" to 0, "airYear" to season.airYear,
                "cast" to cast(season.cast), "episodes" to JSONArray(season.episodes.map { episode ->
                    importValues("id" to UUID.randomUUID().toString(), "episodeNumber" to episode.episodeNumber,
                        "episodeName" to episode.name, "airDate" to episode.airDate, "runtime" to episode.runtime,
                        "synopsis" to episode.synopsis, "stillUrl" to episode.stillUrl,
                        "crew" to crew(episode.crew, true), "watchEvents" to JSONArray(), "ratings" to JSONArray(), "reviews" to JSONArray())
                }))
        }))
}
