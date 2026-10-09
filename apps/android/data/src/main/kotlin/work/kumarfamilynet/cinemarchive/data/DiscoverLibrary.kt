package work.kumarfamilynet.cinemarchive.data

import java.text.Collator
import work.kumarfamilynet.cinemarchive.core.database.TitleCastEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.model.LibraryPerson
import work.kumarfamilynet.cinemarchive.core.model.MediaType

data class DiscoverLibraryTitle(val id: String, val tmdbId: Int, val name: String, val type: MediaType)
data class DiscoverLibrary(val titles: List<DiscoverLibraryTitle> = emptyList(), val cast: List<LibraryPerson> = emptyList()) {
    val ownedKeys: Set<Pair<Int, MediaType>> get() = titles.map { it.tmdbId to it.type }.toSet()
}

/** Like web, the first library title seeds recommendations and only title cast seeds filmographies. */
internal fun discoverLibrary(titles: List<TitleEntity>, cast: List<TitleCastEntity>): DiscoverLibrary {
    val castByTitle = cast.groupBy { it.titleId }
    val people = titles.flatMap { title -> castByTitle[title.id].orEmpty().sortedBy { it.castOrder } }
        .filter { it.tmdbPersonId > 0 }.distinctBy { it.tmdbPersonId }
        .map { LibraryPerson(it.tmdbPersonId, it.name) }
    val collator = Collator.getInstance()
    return DiscoverLibrary(titles.map { DiscoverLibraryTitle(it.id, it.tmdbId, it.title, MediaType.valueOf(it.type)) },
        people.sortedWith { a, b -> collator.compare(a.name, b.name) })
}
