package work.kumarfamilynet.cinemarchive.core.model

/**
 * An automatic list whose membership is derived from [LibraryTitle.status] rather than stored
 * as a [TitleList]. Never persisted or synced — it exists only on the Lists screen, so it never
 * appears in the add-to-list sheet, backups or membership sync. Ids use the reserved `smart:`
 * prefix (shared with the web app's `smartLists.ts`), which can't collide with a real list's uuid.
 */
enum class SmartList(
    val id: String,
    val displayName: String,
    val description: String,
    val status: LibraryStatus,
    val emptyMessage: String,
) {
    WATCHING(
        id = "smart:watching",
        displayName = "Currently Watching",
        description = "Everything you have in progress.",
        status = LibraryStatus.WATCHING,
        emptyMessage = "Nothing in progress — mark a title as Watching to see it here.",
    ),
    WATCHLIST(
        id = "smart:watchlist",
        displayName = "Watchlist",
        description = "Everything you plan to watch.",
        status = LibraryStatus.WATCHLIST,
        emptyMessage = "Your watchlist is empty — mark a title as Watchlist to see it here.",
    ),
    ;

    fun members(titles: List<LibraryTitle>): List<LibraryTitle> = titles.filter { it.status == status }

    companion object {
        fun fromId(id: String?): SmartList? = entries.firstOrNull { it.id == id }
    }
}
