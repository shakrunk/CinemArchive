package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CancellationException
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/** A local episode and the `YYYY-MM-DD` dates already logged on it. */
data class EpisodeWatchState(val episodeId: String, val watchedDates: Set<String>)

enum class EpisodePlan { LOG, DUPLICATE, MISSING }

/** Whether an episode watch on [date] can be logged against [episode] (null = not in the catalog). */
fun planEpisodeLog(episode: EpisodeWatchState?, date: String): EpisodePlan = when {
    episode == null -> EpisodePlan.MISSING
    date in episode.watchedDates -> EpisodePlan.DUPLICATE
    else -> EpisodePlan.LOG
}

sealed interface JellyfinConfirmResult {
    data object Added : JellyfinConfirmResult
    data object Duplicate : JellyfinConfirmResult
    data object Unmatched : JellyfinConfirmResult

    /** The series (or that episode) isn't in the library: hand off to the normal Add flow. */
    data class OpenAddFlow(val match: MediaSearchResult) : JellyfinConfirmResult
}

/**
 * Writes a user-confirmed Jellyfin play into the library — the Kotlin counterpart of
 * `jellyfinConfirm.ts`. Nothing here runs until the user answers "Add". Movies reuse the import
 * merge (dedupes by date, never overwrites). Episodes are logged on a series that is already in
 * the library; anything else hands off to the normal Add flow so the season/episode catalog is
 * built the same way as every other add.
 */
class JellyfinConfirm(
    private val library: LibraryRepository,
    private val sync: SyncRepository,
) {
    /** Throws on a lookup or storage failure so the caller keeps the play queued for a retry. */
    suspend fun confirm(play: JellyfinPlay): JellyfinConfirmResult = when (play.kind) {
        JellyfinPlay.Kind.MOVIE -> confirmMovie(play)
        JellyfinPlay.Kind.EPISODE -> confirmEpisode(play)
    }

    private suspend fun confirmMovie(play: JellyfinPlay): JellyfinConfirmResult {
        val item = SyncItem(
            provider = SyncProvider.JELLYFIN,
            externalId = play.itemId,
            type = MediaType.MOVIE,
            title = play.title,
            year = play.year,
            ids = play.ids,
            status = LibraryStatus.WATCHED,
            rating = null,
            watchedDates = listOf(play.date),
        )
        // A failed lookup throws (play stays queued); only a confident "no match" is Unmatched.
        if (sync.resolveMatch(item) == null) return JellyfinConfirmResult.Unmatched
        val result = sync.import(listOf(item))
        check(result.failed.isEmpty()) { "Couldn't add that watch." }
        if (result.unmatched.isNotEmpty()) return JellyfinConfirmResult.Unmatched
        return if (result.added + result.updated > 0) {
            pushQuietly()
            JellyfinConfirmResult.Added
        } else {
            JellyfinConfirmResult.Duplicate
        }
    }

    private suspend fun confirmEpisode(play: JellyfinPlay): JellyfinConfirmResult {
        val season = play.seasonNumber
        val episode = play.episodeNumber
        val series = SyncItem(
            provider = SyncProvider.JELLYFIN,
            externalId = play.itemId,
            type = MediaType.TV,
            title = play.title,
            year = null,
            ids = play.ids,
            status = LibraryStatus.WATCHING,
            rating = null,
            watchedDates = emptyList(),
        )
        val match = sync.resolveMatch(series) ?: return JellyfinConfirmResult.Unmatched
        val titleId = library.findLibraryTitleId(match.tmdbId, MediaType.TV)
        val state = if (titleId != null && season != null && episode != null) {
            library.findEpisodeWatchState(titleId, season, episode)
        } else {
            null
        }
        return when (planEpisodeLog(state, play.date)) {
            EpisodePlan.DUPLICATE -> JellyfinConfirmResult.Duplicate
            EpisodePlan.LOG -> {
                library.logEpisodeWatched(checkNotNull(state).episodeId, play.date)
                pushQuietly()
                JellyfinConfirmResult.Added
            }
            EpisodePlan.MISSING -> JellyfinConfirmResult.OpenAddFlow(match)
        }
    }

    // Local writes are durable already; delivery is best effort and retried by the normal outbox.
    private suspend fun pushQuietly() {
        try {
            sync.synchronize()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pending changes sync when connected.
        }
    }
}
