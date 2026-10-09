package work.kumarfamilynet.cinemarchive.feature.discover

import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle

enum class TypeFilter(val label: String) {
    ALL("All"), MOVIE("Movies"), TV("TV");
    val mediaType: MediaType? get() = when (this) { ALL -> null; MOVIE -> MediaType.MOVIE; TV -> MediaType.TV }
}

data class DiscoverGenre(val id: Int, val name: String)

/** Stable TMDB IDs and labels shared with web's MOVIE_GENRES / TV_GENRES. */
fun discoverGenres(type: TypeFilter): List<DiscoverGenre> = if (type == TypeFilter.TV) listOf(
    DiscoverGenre(10759, "Action & Adventure"), DiscoverGenre(16, "Animation"), DiscoverGenre(35, "Comedy"),
    DiscoverGenre(80, "Crime"), DiscoverGenre(18, "Drama"), DiscoverGenre(9648, "Mystery"),
    DiscoverGenre(10765, "Sci-Fi & Fantasy"), DiscoverGenre(10751, "Family"), DiscoverGenre(37, "Western"),
) else listOf(
    DiscoverGenre(28, "Action"), DiscoverGenre(12, "Adventure"), DiscoverGenre(16, "Animation"),
    DiscoverGenre(35, "Comedy"), DiscoverGenre(80, "Crime"), DiscoverGenre(18, "Drama"),
    DiscoverGenre(14, "Fantasy"), DiscoverGenre(27, "Horror"), DiscoverGenre(9648, "Mystery"),
    DiscoverGenre(10749, "Romance"), DiscoverGenre(878, "Sci-Fi"), DiscoverGenre(53, "Thriller"),
)

/** TMDB movie and television IDs belong to different namespaces. */
internal val TrendingTitle.mediaIdentity: Pair<Int, MediaType> get() = tmdbId to type
internal val TrendingTitle.catalogKey: String get() = "${type.name}:$tmdbId"

internal fun filterDiscoverTitles(titles: List<TrendingTitle>, filter: TypeFilter): List<TrendingTitle> =
    titles.filter { filter == TypeFilter.ALL || (filter == TypeFilter.MOVIE) == (it.type == MediaType.MOVIE) }

data class DiscoverUiState(
    val query: String = "",
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val genreId: Int? = null,
    val titles: List<TrendingTitle> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val moreError: String? = null,
)
