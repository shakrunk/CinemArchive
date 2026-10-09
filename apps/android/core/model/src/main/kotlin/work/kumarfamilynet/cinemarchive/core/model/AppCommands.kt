package work.kumarfamilynet.cinemarchive.core.model

import java.util.Locale

/** Commands are data only; the host rechecks its current owner/scope before dispatch. */
data class AppCommand(val id: String, val label: String, val hint: String = "", val keywords: String = "")

fun titleCommand(id: String, name: String, year: Int?, director: String?, series: Boolean, genres: List<String>) =
    AppCommand("title:$id", name, listOfNotNull(
        director?.takeIf(String::isNotBlank)?.let { "dir. $it" } ?: if (series) "series" else "film",
        year?.toString()).joinToString(" · "), genres.joinToString(" "))

fun ownerCommands(titles: List<LibraryTitle>): List<AppCommand> = listOf(
    AppCommand("add", "Add a title", "new", "create new movie series"),
    AppCommand("upnext", "Go to Up Next", keywords = "continue watching"),
    AppCommand("tickets", "I've got tickets…", "schedule", "cinema outing movie showtime theater venue"),
    AppCommand("marquee", "On the Marquee", keywords = "cinema outings tickets up next showtime"),
    AppCommand("library", "Go to the Library", keywords = "collection posters"),
    AppCommand("ledger", "Go to the Ledger", keywords = "stats dashboard"),
    AppCommand("discover", "Go to Discover", keywords = "explore browse trending genres movies tv"),
    AppCommand("profile", "Go to Profile & Settings", keywords = "account settings preferences theme shared links sign in out email export import"),
    AppCommand("friends", "Go to Friends", keywords = "friends social recommendations activity inbox"),
    AppCommand("lists", "Go to Lists", keywords = "collections lists"),
    AppCommand("grid", "Library: poster wall", keywords = "grid posters"),
    AppCommand("list", "Library: ledger list", keywords = "list table"),
) + titles.map { titleCommand(it.id, it.name, it.year, it.director, it.type == MediaType.TV, it.genres) }

/** Same score, tie breaks and eight-result bound as the web command palette. */
fun rankCommands(commands: List<AppCommand>, query: String): List<AppCommand> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return commands.take(8)
    fun boundary(text: String) = Regex("(^|[^A-Za-z0-9_])" + Regex.escape(q)).containsMatchIn(text)
    fun score(command: AppCommand): Double {
        val label = command.label.lowercase(Locale.ROOT)
        val keywords = command.keywords.lowercase(Locale.ROOT)
        val penalty = minOf(label.length, 40) * .1
        return when {
            label == q -> 100 - penalty
            label.startsWith(q) -> 80 - penalty
            boundary(label) -> 60 - penalty
            q in label -> 40 - penalty
            boundary(keywords) -> 20.0
            q in keywords -> 10.0
            else -> -1.0
        }
    }
    return commands.map { it to score(it) }.filter { it.second >= 0 }
        .sortedWith(compareByDescending<Pair<AppCommand, Double>> { it.second }
            .thenBy { it.first.label.length }.thenBy { it.first.id })
        .take(8).map { it.first }
}

