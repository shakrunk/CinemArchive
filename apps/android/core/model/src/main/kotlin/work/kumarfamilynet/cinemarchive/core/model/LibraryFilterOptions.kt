package work.kumarfamilynet.cinemarchive.core.model

/** Library screen sort order (#120/KP-050) — purely a client-side list ordering, not persisted
 *  server-side. [LAST_INTERACTION] is the default on both clients, labelled "Smart" — every
 *  other option names a column, so this one is named for being derived rather than for the
 *  timestamps it derives from. */
enum class LibrarySortOrder {
    LAST_INTERACTION,
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
