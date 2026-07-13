package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import work.kumarfamilynet.cinemarchive.core.database.LibraryTitleDao
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle

/**
 * The app reads the Library through this Room-backed repository. Network sync is added here,
 * rather than being called from UI, once the protected Android contract is available.
 */
class LibraryRepository(private val titleDao: LibraryTitleDao) {
    fun observeLibrary(): Flow<List<LibraryTitle>> = titleDao.observeAll().map { entities ->
        entities.map { entity ->
            LibraryTitle(
                id = entity.id,
                name = entity.name,
                year = entity.year,
                posterUrl = entity.posterUrl,
                status = LibraryStatus.valueOf(entity.status),
            )
        }
    }
}
