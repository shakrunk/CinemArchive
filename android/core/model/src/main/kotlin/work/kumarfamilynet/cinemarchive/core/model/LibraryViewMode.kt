package work.kumarfamilynet.cinemarchive.core.model

/** Poster grid vs. compact row list for the Library screen. Persisted via
 *  `PreferencesRepository` so it survives navigating away and back, and mirrored onto the
 *  Library tab's bottom-nav icon so it reflects the *current* layout. */
enum class LibraryViewMode {
    GRID,
    LIST,
}
