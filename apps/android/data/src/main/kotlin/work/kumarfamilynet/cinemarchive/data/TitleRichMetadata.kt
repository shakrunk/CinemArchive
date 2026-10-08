package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.model.PhysicalMediaItem

/** Both wire forms preserve absence separately from explicit null/false/zero/empty arrays. */
internal fun TitleEntity.withRichMetadata(payload: JSONObject, snakeCase: Boolean = false, previous: TitleEntity? = null): TitleEntity {
    val prior = previous ?: this
    fun key(camel: String, snake: String) = if (snakeCase) snake else camel
    fun string(camel: String, snake: String, previous: String?): String? {
        val name = key(camel, snake)
        return if (!payload.has(name)) previous else if (payload.isNull(name)) null else payload.getString(name)
    }
    fun number(camel: String, snake: String, previous: Int?): Int? {
        val name = key(camel, snake)
        return if (!payload.has(name)) previous else if (payload.isNull(name)) null else payload.getInt(name)
    }
    val home = key("inHomeCollection", "in_home_collection")
    val shelf = key("physicalMedia", "physical_media")
    return copy(
        contentRating = string("contentRating", "content_rating", prior.contentRating),
        imdbId = string("imdbId", "imdb_id", prior.imdbId), rtUrl = string("rtUrl", "rt_url", prior.rtUrl),
        rtScore = number("rtScore", "rt_score", prior.rtScore), metacriticScore = number("metacriticScore", "metacritic_score", prior.metacriticScore),
        customWatchUrl = string("customWatchUrl", "custom_watch_url", prior.customWatchUrl),
        inHomeCollection = if (!payload.has(home)) prior.inHomeCollection else if (payload.isNull(home)) null else payload.getBoolean(home),
        physicalMediaJson = if (!payload.has(shelf)) prior.physicalMediaJson else if (payload.isNull(shelf)) null else payload.getJSONArray(shelf).toString(),
        awardsCount = number("awardsCount", "awards_count", prior.awardsCount),
        bechdelOutcome = string("bechdelOutcome", "bechdel_outcome", prior.bechdelOutcome),
        bechdelScore = string("bechdelScore", "bechdel_score", prior.bechdelScore),
    )
}

/** Malformed legacy copies stay in raw storage for recovery; valid siblings remain visible. */
internal fun physicalMediaItems(raw: String?): List<PhysicalMediaItem> {
    if (raw == null) return emptyList()
    val items = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val id = item.opt("id") as? String ?: return@mapNotNull null
        val format = item.opt("format") as? String ?: return@mapNotNull null
        PhysicalMediaItem(id, format, item.opt("edition") as? String, item.opt("notes") as? String, item.toString())
    }
}
