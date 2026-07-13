package work.kumarfamilynet.cinemarchive

import android.app.Application
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.data.LibraryRepository

class CinemArchiveApplication : Application() {
    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(LibraryDatabase.create(this).libraryTitleDao())
    }
}
