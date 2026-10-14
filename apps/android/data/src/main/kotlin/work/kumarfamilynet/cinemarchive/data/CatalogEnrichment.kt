package work.kumarfamilynet.cinemarchive.data

import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails

/** Independent optional lookups: an unavailable ratings service must not hide accolades. */
internal suspend fun enrichCatalogDetails(
    base: MediaDetails,
    invoke: suspend (String) -> String,
): MediaDetails = coroutineScope {
    val imdb = base.imdbId?.takeIf { it.isNotBlank() } ?: return@coroutineScope base
    val encoded = URLEncoder.encode(imdb, "UTF-8")
    suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    val scores = async { optional { parseCriticScores(invoke("action=ratings&imdb=$encoded")) } }
    val link = async { optional { JSONObject(invoke("action=rt_link&imdb=$encoded")) } }
    val accolades = async { optional { JSONObject(invoke("action=accolades&imdb=$encoded")) } }
    val critics = scores.await()
    val awards = accolades.await()
    val bechdel = awards?.optJSONObject("bechdel")
    fun JSONObject.text(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    base.copy(
        imdbRating = critics?.imdbRating,
        rtScore = critics?.rtScore,
        metacriticScore = critics?.metacriticScore,
        rtUrl = link.await()?.text("rtUrl"),
        awardsCount = awards?.opt("awardsCount")?.let { (it as? Number)?.toInt() }?.takeIf { it >= 0 },
        bechdelOutcome = bechdel?.text("outcome")?.takeIf { it == "pass" || it == "fail" },
        bechdelScore = bechdel?.text("score"),
    )
}
