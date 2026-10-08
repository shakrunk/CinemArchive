package work.kumarfamilynet.cinemarchive.feature.discover

import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle

internal enum class TypeFilter(val label: String) { ALL("All"), MOVIE("Movies"), TV("TV") }

/** TMDB movie and television IDs belong to different namespaces. */
internal val TrendingTitle.mediaIdentity: Pair<Int, MediaType> get() = tmdbId to type
internal val TrendingTitle.catalogKey: String get() = "${type.name}:$tmdbId"

internal fun filterDiscoverTitles(titles: List<TrendingTitle>, filter: TypeFilter): List<TrendingTitle> =
    titles.filter { filter == TypeFilter.ALL || (filter == TypeFilter.MOVIE) == (it.type == MediaType.MOVIE) }

data class DiscoverUiState(
    val query: String = "",
    val titles: List<TrendingTitle> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)
