package work.kumarfamilynet.cinemarchive.core.model

import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Device-local controls. Categories combine with AND; choices within a category use OR. */
data class LibraryFilters(
    val search: String = "",
    val type: MediaType? = null,
    val statuses: Set<LibraryStatus> = emptySet(),
    val genres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val networks: Set<String> = emptySet(),
    val decades: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val studio: String? = null,
    val minRating: Double = 0.0,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.LAST_INTERACTION,
    val sortDirection: LibrarySortDirection = LibrarySortDirection.DESCENDING,
    val grouping: LibraryGrouping = LibraryGrouping.NONE,
) {
    val activeFilterCount: Int get() = listOf(
        type != null, statuses.isNotEmpty(), genres.isNotEmpty(), tags.isNotEmpty(),
        networks.isNotEmpty(), decades.isNotEmpty(), languages.isNotEmpty(),
        studio != null, minRating > 0, grouping != LibraryGrouping.NONE,
    ).count { it }
}

data class LibraryFilterChoices(
    val genres: List<String>, val tags: List<String>, val networks: List<String>,
    val decades: List<String>, val languages: List<String>, val studios: List<String>,
)

fun libraryFilterChoices(titles: List<LibraryTitle>) = LibraryFilterChoices(
    genres = titles.flatMap { it.genres }.distinct().sorted(),
    tags = titles.flatMap { it.tags }.distinct().sorted(),
    networks = titles.mapNotNull { it.network?.takeIf(String::isNotEmpty) }.distinct().sorted(),
    decades = titles.map { decade(it.year) }.distinct().sorted(),
    languages = titles.mapNotNull { it.originalLanguage?.takeIf(String::isNotEmpty) }.distinct().sorted(),
    studios = titles.flatMap { it.studios }.distinct().sorted(),
)

fun filterLibrary(titles: List<LibraryTitle>, filters: LibraryFilters): List<LibraryTitle> {
    val query = filters.search.lowercase()
    val filtered = titles.filter { title ->
        (filters.search.isBlank() || listOfNotNull(title.name, title.director)
            .plus(title.genres).plus(title.tags).plus(title.castNames).any { it.lowercase().contains(query) }) &&
            (filters.type == null || title.type == filters.type) &&
            (filters.statuses.isEmpty() || title.status in filters.statuses) &&
            (filters.genres.isEmpty() || title.genres.any { it in filters.genres }) &&
            (filters.tags.isEmpty() || title.tags.any { it in filters.tags }) &&
            (filters.networks.isEmpty() || title.network in filters.networks) &&
            (filters.decades.isEmpty() || decade(title.year) in filters.decades) &&
            (filters.languages.isEmpty() || title.originalLanguage in filters.languages) &&
            (filters.studio == null || filters.studio in title.studios) &&
            (title.rating ?: 0.0) >= filters.minRating
    }
    val collator = Collator.getInstance()
    val times = when (filters.sortOrder) {
        LibrarySortOrder.ADDED_AT -> filtered.associate { it.id to timestamp(it.addedAt) }
        LibrarySortOrder.LAST_INTERACTION -> filtered.associate { it.id to timestamp(it.lastInteractionAt) }
        else -> emptyMap()
    }
    val comparator = Comparator<LibraryTitle> { a, b ->
        when (filters.sortOrder) {
            LibrarySortOrder.TITLE -> collator.compare(a.name, b.name)
            LibrarySortOrder.YEAR_NEWEST -> (a.year ?: 0).compareTo(b.year ?: 0)
            LibrarySortOrder.RATING_HIGHEST -> (a.rating ?: 0.0).compareTo(b.rating ?: 0.0)
            LibrarySortOrder.DIRECTOR -> collator.compare(a.director.orEmpty(), b.director.orEmpty())
            LibrarySortOrder.ADDED_AT, LibrarySortOrder.LAST_INTERACTION -> times.getValue(a.id).compareTo(times.getValue(b.id))
        }
    }
    // Kotlin's stable sort retains incoming order for ties, matching the web comparator.
    return filtered.sortedWith(if (filters.sortDirection == LibrarySortDirection.ASCENDING) comparator else comparator.reversed())
}

data class LibraryTitleGroup(val key: String, val label: String?, val titles: List<LibraryTitle>)

fun groupLibrary(titles: List<LibraryTitle>, grouping: LibraryGrouping): List<LibraryTitleGroup> = when (grouping) {
    LibraryGrouping.NONE -> listOf(LibraryTitleGroup("all", null, titles))
    LibraryGrouping.STATUS -> listOf(LibraryStatus.WATCHED, LibraryStatus.WATCHING, LibraryStatus.WATCHLIST, LibraryStatus.DROPPED)
        .mapNotNull { status -> titles.filter { it.status == status }.takeIf { it.isNotEmpty() }?.let {
            LibraryTitleGroup(status.name, status.name.lowercase().replaceFirstChar(Char::uppercase), it)
        } }
    LibraryGrouping.FRANCHISE -> {
        val franchises = linkedMapOf<String, MutableList<LibraryTitle>>()
        val standalone = mutableListOf<LibraryTitle>()
        titles.forEach { title ->
            if (title.collectionName.isNullOrEmpty()) standalone += title
            else franchises.getOrPut(title.collectionId?.toString() ?: title.collectionName) { mutableListOf() } += title
        }
        val groups = franchises.map { (key, members) ->
            LibraryTitleGroup("franchise-$key", members.first().collectionName, members.sortedBy(::releaseTime))
        }
        if (standalone.isEmpty()) groups else groups + LibraryTitleGroup("standalone", if (groups.isEmpty()) null else "Other titles", standalone)
    }
}

private fun decade(year: Int?) = "${Math.floorDiv(year ?: 0, 10) * 10}s"

private fun timestamp(value: String?): Long = value?.let {
    runCatching { Instant.parse(it).toEpochMilli() }.getOrElse { _ ->
        runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
    }
} ?: Long.MIN_VALUE

private fun releaseTime(title: LibraryTitle): Long = title.releaseDate?.let(::timestamp) ?: run {
    // The web's new Date(year, 0, 1) uses local midnight and treats years 0..99 as 1900..1999.
    val year = (title.year ?: 0).let { if (it in 0..99) it + 1900 else it }
    LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
