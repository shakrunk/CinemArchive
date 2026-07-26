package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCastEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails

/**
 * How many cast rows a newly added title carries. TMDB bills whole ensembles — a big
 * production can list 150 people — and the only thing reading `title_cast` locally is the
 * Ledger Ensemble widget's leading-cast tally (`castOrder < 5`, docs/android-contracts/ledger.md
 * §2). Twenty leaves generous headroom for that while keeping the outbox payload, which is one
 * JSON blob in a Room row, from ballooning by an order of magnitude per add.
 */
internal const val MAX_CAST_ROWS = 20

/**
 * The single outbox payload for an added title — the whole object graph
 * (`LibraryRepository.addTitle` writes it as one entry; see that method's kdoc for why it
 * isn't split per table). Keys are camelCase like every other outbox payload in this module;
 * `SupabaseRemoteMutationWriter.insertTitle` maps them to their snake_case columns.
 *
 * Carries a handful of fields the local Room mirror has no column for — `contentRating`,
 * `imdbId`, `studios`, `collectionId`/`collectionName`, `rtScore`, `metacriticScore` — read
 * straight off [MediaDetails] rather than off [title]. The server has columns for all of them
 * and the web app renders them, so dropping them here would make a title added on a phone look
 * permanently poorer on the web than the same title added there (see [MediaDetails]' kdoc).
 */
internal fun buildAddTitlePayload(
    title: TitleEntity,
    details: MediaDetails,
    seasons: List<SeasonEntity>,
    episodes: List<EpisodeEntity>,
    cast: List<TitleCastEntity>,
    crew: List<TitleCrewEntity>,
    viewing: ViewingEntity?,
): JSONObject {
    val seasonNumberById = seasons.associate { it.id to it.seasonNumber }
    return JSONObject().apply {
        put("id", title.id)
        put("tmdbId", title.tmdbId)
        put("type", title.type)
        put("title", title.title)
        // titles.year is `not null` server-side (schema.sql) while TMDB genuinely omits a date
        // for some entries, so an unknown year goes up as 0 — the same substitution the web
        // app's mapSearchItem makes — while the local mirror keeps the honest null.
        put("year", title.year ?: 0)
        putOrNull("releaseDate", title.releaseDate)
        putOrNull("director", title.director)
        put("genres", JSONArray(title.genres))
        putOrNull("posterUrl", title.posterUrl)
        putOrNull("backdropUrl", title.backdropUrl)
        putOrNull("synopsis", title.synopsis)
        putOrNull("runtime", title.runtime)
        putOrNull("network", title.network)
        put("status", title.status)
        putOrNull("rating", title.rating)
        putOrNull("notes", title.notes)
        put("addedAt", title.addedAt)
        put("updatedAt", title.updatedAt)
        putOrNull("originalLanguage", title.originalLanguage)
        putOrNull("imdbRating", title.imdbRating)
        putOrNull("contentRating", details.contentRating)
        putOrNull("imdbId", details.imdbId)
        putOrNull("rtScore", details.rtScore)
        putOrNull("metacriticScore", details.metacriticScore)
        put("studios", JSONArray(details.studios))
        putOrNull("collectionId", details.collectionId)
        putOrNull("collectionName", details.collectionName)

        put(
            "seasons",
            JSONArray().apply {
                seasons.forEach { season ->
                    put(
                        JSONObject().apply {
                            put("id", season.id)
                            put("seasonNumber", season.seasonNumber)
                            put("episodeCount", season.episodeCount)
                            put("episodesWatched", season.episodesWatched)
                            putOrNull("airYear", season.airYear)
                        },
                    )
                }
            },
        )
        // Remote `episodes` rows are keyed by (title_id, season_number, episode_number) and
        // have no season_id column at all (schema.sql) — the local seasonId FK is resolved
        // back from season_number on the way down, in LibrarySyncRepository.applyPage.
        put(
            "episodes",
            JSONArray().apply {
                episodes.forEach { episode ->
                    put(
                        JSONObject().apply {
                            put("id", episode.id)
                            put("seasonNumber", seasonNumberById[episode.seasonId] ?: 0)
                            put("episodeNumber", episode.episodeNumber)
                            putOrNull("episodeName", episode.episodeName)
                            putOrNull("airDate", episode.airDate)
                            putOrNull("runtime", episode.runtime)
                        },
                    )
                }
            },
        )
        put(
            "cast",
            JSONArray().apply {
                cast.forEach { member ->
                    put(
                        JSONObject().apply {
                            put("id", member.id)
                            put("tmdbPersonId", member.tmdbPersonId)
                            put("name", member.name)
                            putOrNull("characterName", member.characterName)
                            put("castOrder", member.castOrder)
                        },
                    )
                }
            },
        )
        put(
            "crew",
            JSONArray().apply {
                crew.forEach { member ->
                    put(
                        JSONObject().apply {
                            put("id", member.id)
                            put("tmdbPersonId", member.tmdbPersonId)
                            put("name", member.name)
                            put("job", member.job)
                            putOrNull("department", member.department)
                        },
                    )
                }
            },
        )
        viewing?.let {
            put(
                "viewing",
                JSONObject().apply {
                    put("id", it.id)
                    putOrNull("date", it.date)
                    putOrNull("rating", it.rating)
                    putOrNull("notes", it.notes)
                },
            )
        }
    }
}

/** `JSONObject.put(key, null)` *removes* the key rather than storing a JSON null, which would
 *  silently drop nullable columns from the push. [JSONObject.NULL] is the explicit null. */
private fun JSONObject.putOrNull(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}
