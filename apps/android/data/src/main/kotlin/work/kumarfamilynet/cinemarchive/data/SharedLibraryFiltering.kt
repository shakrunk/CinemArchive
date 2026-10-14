package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import work.kumarfamilynet.cinemarchive.core.model.LibraryPerson
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/** Project only the graph already authorized for this viewer; never consult the owner's Room. */
fun SharedLibraryTitle.toLibraryFilterTitle(): LibraryTitle {
    val row = graph()
    val cast = row.creditRows("title_cast")
    val episodes = row.creditRows("episodes")
    val people = (cast + row.creditRows("title_crew") +
        row.creditRows("seasons").flatMap { it.creditRows("season_cast") } +
        episodes.flatMap { it.creditRows("episode_crew") }).mapNotNull { credit ->
        credit.optInt("tmdb_person_id").takeIf { it > 0 }?.let { LibraryPerson(it, credit.optString("name")) }
    }.distinctBy { it.tmdbPersonId }
    // Matches web titleLastInteractionAt: catalog updated_at and insertion created_at are excluded.
    val interactionDates = listOfNotNull(addedAt) + row.creditRows("viewings").mapNotNull { it.text("viewed_at") } +
        episodes.flatMap { episode ->
            episode.creditRows("episode_watch_events").mapNotNull { it.text("watched_at") } +
                episode.creditRows("episode_ratings").mapNotNull { it.text("rated_at") } +
                episode.creditRows("episode_reviews").mapNotNull { it.text("reviewed_at") }
        }
    return LibraryTitle(
        id = id, name = title, year = year, posterUrl = posterUrl,
        status = LibraryStatus.valueOf(status.uppercase()), type = if (mediaType == "movie") MediaType.MOVIE else MediaType.TV,
        director = row.text("director"), network = row.text("network"), rating = rating,
        releaseDate = row.text("release_date"), genres = genres,
        lastInteractionAt = interactionDates.maxByOrNull(::interactionTime), addedAt = addedAt,
        originalLanguage = row.text("original_language"), tags = row.texts("tags"), studios = row.texts("studios"),
        collectionId = if (row.isNull("collection_id")) null else row.optInt("collection_id"),
        collectionName = row.text("collection_name"), castNames = cast.mapNotNull { it.text("name") }, people = people,
    )
}

private fun JSONObject.creditRows(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}.orEmpty()
private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
private fun JSONObject.texts(key: String): List<String> = optJSONArray(key)?.let { array ->
    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
}.orEmpty()
private fun interactionTime(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
    runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}
