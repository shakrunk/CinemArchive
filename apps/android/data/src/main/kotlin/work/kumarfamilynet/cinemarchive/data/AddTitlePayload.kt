package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeCrewEntity
import work.kumarfamilynet.cinemarchive.core.database.SeasonCastEntity
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCastEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails

/**
 * The single outbox payload for an added title — the whole object graph
 * (`LibraryRepository.addTitle` writes it as one entry; see that method's kdoc for why it
 * isn't split per table). Keys are camelCase like every other outbox payload in this module;
 * `SupabaseRemoteMutationWriter.insertTitle` maps them to their snake_case columns.
 *
 * Catalog details accompany the local title projection so both clients receive the same
 * certification, external IDs, studios, collection and critic scores when a title is added.
 */
internal fun buildAddTitlePayload(
    title: TitleEntity,
    details: MediaDetails,
    seasons: List<SeasonEntity>,
    episodes: List<EpisodeEntity>,
    cast: List<TitleCastEntity>,
    crew: List<TitleCrewEntity>,
    viewing: ViewingEntity?,
    seasonCast: List<SeasonCastEntity> = emptyList(),
    episodeCrew: List<EpisodeCrewEntity> = emptyList(),
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
        putOrNull("rtUrl", details.rtUrl)
        putOrNull("awardsCount", details.awardsCount)
        putOrNull("bechdelOutcome", details.bechdelOutcome)
        putOrNull("bechdelScore", details.bechdelScore)
        put("studios", JSONArray(details.studios))
        putOrNull("collectionId", details.collectionId)
        putOrNull("collectionName", details.collectionName)
        put("seasonCast", JSONArray().apply {
            seasonCast.forEach { credit -> put(JSONObject().apply {
                put("id", credit.id); put("seasonId", credit.seasonId); put("tmdbPersonId", credit.tmdbPersonId)
                put("name", credit.name); putOrNull("characterName", credit.characterName); put("castOrder", credit.castOrder)
                putOrNull("profileUrl", credit.profileUrl); putOrNull("episodeCount", credit.episodeCount)
            }) }
        })
        put("episodeCrew", JSONArray().apply {
            episodeCrew.forEach { credit -> put(JSONObject().apply {
                put("id", credit.id); put("episodeId", credit.episodeId); put("tmdbPersonId", credit.tmdbPersonId)
                put("name", credit.name); put("job", credit.job)
            }) }
        })

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
                            putOrNull("synopsis", episode.synopsis)
                            putOrNull("stillUrl", episode.stillUrl)
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
                            putOrNull("profileUrl", member.profileUrl)
                            putOrNull("episodeCount", member.episodeCount)
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
                            putOrNull("profileUrl", member.profileUrl)
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
