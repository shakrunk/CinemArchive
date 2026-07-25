package work.kumarfamilynet.cinemarchive.core.model

/** Library screen sort order (#120/KP-050) — purely a client-side list ordering, not persisted
 *  server-side. */
enum class LibrarySortOrder {
    TITLE,
    YEAR_NEWEST,
    RATING_HIGHEST,
}

/** Library screen grouping (#120/KP-050) — inserts section headers into the poster grid/list;
 *  [NONE] renders the flat, sorted list untouched. */
enum class LibraryGrouping {
    NONE,
    STATUS,
}
