package work.kumarfamilynet.cinemarchive.feature.friends

import java.net.URI
import kotlinx.coroutines.CancellationException
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.data.Recommendation

sealed interface RecommendationNavigation {
    data class Owned(val titleId: String) : RecommendationNavigation
    data class Add(val title: MediaSearchResult) : RecommendationNavigation
}

/** Match by catalog identity, never the sender's private title ID or display name. */
suspend fun recommendationNavigation(
    recommendation: Recommendation,
    isActive: () -> Boolean,
    markRead: suspend (String) -> Unit,
    findOwned: suspend (Int, MediaType) -> String?,
): RecommendationNavigation? {
    val type = recommendation.type ?: return null
    if (!isActive()) return null
    if (recommendation.isUnread) {
        try {
            markRead(recommendation.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A read-receipt outage must not prevent opening the recommendation.
        }
    }
    if (!isActive()) return null
    val owned = findOwned(recommendation.tmdbId, type)
    if (!isActive()) return null
    return owned?.let { RecommendationNavigation.Owned(it) }
        ?: RecommendationNavigation.Add(MediaSearchResult(
            recommendation.tmdbId, recommendation.title, recommendation.year, type,
            recommendation.posterUrl, synopsis = null,
        ))
}

internal fun recommendationWatchUrl(value: String?): String? = value?.takeIf {
    runCatching {
        val uri = URI(it)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
