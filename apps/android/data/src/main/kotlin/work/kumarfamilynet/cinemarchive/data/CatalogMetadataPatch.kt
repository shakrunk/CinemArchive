package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails

internal val catalogTextFields = setOf("title", "director", "poster_url", "backdrop_url", "synopsis", "network",
    "release_date", "original_language", "content_rating", "imdb_id", "rt_url", "collection_name", "bechdel_outcome", "bechdel_score")
internal val catalogIntFields = setOf("year", "runtime", "rt_score", "metacritic_score", "awards_count", "collection_id")
internal val catalogFields = catalogTextFields + catalogIntFields + setOf("genres", "studios", "imdb_rating")

internal fun validateCatalogPatch(patch: JSONObject) {
    patch.keys().forEach { key ->
        if (key !in catalogFields) return@forEach
        val value = patch.get(key)
        when {
            key in catalogTextFields -> require(value == JSONObject.NULL || value is String)
            key in setOf("genres", "studios") -> require(value is JSONArray && (0 until value.length()).all { value.get(it) is String })
            key in catalogIntFields -> require(value == JSONObject.NULL || value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble())
            key == "imdb_rating" -> require(value == JSONObject.NULL || value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..10.0)
        }
        if (key == "title") require(value is String && value.isNotBlank())
        if (key == "year") require(value is Number && value.toInt() >= 0)
        if (key == "runtime" && value != JSONObject.NULL) require((value as Number).toInt() > 0)
        if (key in setOf("rt_score", "metacritic_score") && value != JSONObject.NULL) require((value as Number).toInt() in 0..100)
        if (key == "awards_count" && value != JSONObject.NULL) require((value as Number).toInt() >= 0)
        if (key == "collection_id" && value != JSONObject.NULL) require((value as Number).toInt() > 0)
        if (key == "release_date" && value != JSONObject.NULL) java.time.LocalDate.parse(value as String)
    }
}

internal fun TitleEntity.catalogValues() = JSONObject().apply {
    listOf("title" to title, "year" to (year ?: 0), "director" to director, "genres" to JSONArray(genres),
        "poster_url" to posterUrl, "backdrop_url" to backdropUrl, "synopsis" to synopsis, "runtime" to runtime,
        "network" to network, "release_date" to releaseDate, "original_language" to originalLanguage,
        "content_rating" to contentRating, "imdb_id" to imdbId, "rt_url" to rtUrl, "imdb_rating" to imdbRating,
        "rt_score" to rtScore, "metacritic_score" to metacriticScore, "awards_count" to awardsCount,
        "bechdel_outcome" to bechdelOutcome, "bechdel_score" to bechdelScore, "studios" to JSONArray(studios),
        "collection_id" to collectionId, "collection_name" to collectionName).forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}

/** Missing provider fields are not evidence to erase previously known metadata. */
internal fun catalogMetadataPatch(local: TitleEntity, fresh: MediaDetails): JSONObject {
    val old = local.catalogValues()
    val next = old.let { exactMetadataObject(metadataJson(it)) }
    listOf("title" to fresh.title, "year" to fresh.year, "director" to fresh.director,
        "poster_url" to fresh.posterUrl, "backdrop_url" to fresh.backdropUrl, "synopsis" to fresh.synopsis,
        "runtime" to fresh.runtime, "network" to fresh.network, "release_date" to fresh.releaseDate,
        "original_language" to fresh.originalLanguage, "content_rating" to fresh.contentRating, "imdb_id" to fresh.imdbId,
        "rt_url" to fresh.rtUrl, "imdb_rating" to fresh.imdbRating, "rt_score" to fresh.rtScore,
        "metacritic_score" to fresh.metacriticScore, "awards_count" to fresh.awardsCount,
        "bechdel_outcome" to fresh.bechdelOutcome, "bechdel_score" to fresh.bechdelScore,
        "collection_id" to fresh.collectionId, "collection_name" to fresh.collectionName).forEach { (key, value) ->
        if (value != null && (value !is String || value.isNotBlank())) next.put(key, value)
    }
    if (fresh.genres.isNotEmpty()) next.put("genres", JSONArray(fresh.genres))
    if (fresh.studios.isNotEmpty()) next.put("studios", JSONArray(fresh.studios))
    return JSONObject().apply { next.keys().forEach { key -> if (!sameCommandJson(next.get(key), old.get(key))) put(key, next.get(key)) } }
}

internal fun TitleEntity.withCatalogMetadata(patch: JSONObject): TitleEntity {
    fun text(key: String, old: String?) = if (!patch.has(key)) old else if (patch.isNull(key)) null else patch.getString(key)
    fun int(key: String, old: Int?) = if (!patch.has(key)) old else if (patch.isNull(key)) null else patch.getInt(key)
    fun strings(key: String, old: List<String>) = if (!patch.has(key)) old else patch.getJSONArray(key).let { a -> (0 until a.length()).map(a::getString) }
    return copy(title = text("title", title)!!, year = int("year", year), director = text("director", director),
        genres = strings("genres", genres), posterUrl = text("poster_url", posterUrl), backdropUrl = text("backdrop_url", backdropUrl),
        synopsis = text("synopsis", synopsis), runtime = int("runtime", runtime), network = text("network", network),
        releaseDate = text("release_date", releaseDate), originalLanguage = text("original_language", originalLanguage),
        contentRating = text("content_rating", contentRating), imdbId = text("imdb_id", imdbId), rtUrl = text("rt_url", rtUrl),
        imdbRating = if (!patch.has("imdb_rating")) imdbRating else if (patch.isNull("imdb_rating")) null else patch.getDouble("imdb_rating"),
        rtScore = int("rt_score", rtScore), metacriticScore = int("metacritic_score", metacriticScore), awardsCount = int("awards_count", awardsCount),
        bechdelOutcome = text("bechdel_outcome", bechdelOutcome), bechdelScore = text("bechdel_score", bechdelScore),
        studios = strings("studios", studios), collectionId = int("collection_id", collectionId), collectionName = text("collection_name", collectionName))
}
