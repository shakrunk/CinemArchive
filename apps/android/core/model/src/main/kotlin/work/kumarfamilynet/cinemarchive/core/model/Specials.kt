package work.kumarfamilynet.cinemarchive.core.model

/**
 * TMDB files a series' specials, OVAs and feature-length one-offs under season 0. They are
 * shown and loggable like any season, but every series-level rollup — progress, Up Next, the
 * next air date and the season count — is computed over the main seasons only, so a show with
 * dozens of extras can still be finished. Mirrors the Specials helpers in the web app's
 * `src/store/episodeUtils.ts`.
 */
const val SPECIALS_SEASON_NUMBER = 0

fun isSpecialsSeason(seasonNumber: Int): Boolean = seasonNumber == SPECIALS_SEASON_NUMBER

val SeasonDetail.isSpecials: Boolean get() = isSpecialsSeason(seasonNumber)

val MediaSeason.isSpecials: Boolean get() = isSpecialsSeason(seasonNumber)

/** Every season except Specials. */
fun List<SeasonDetail>.mainSeasons(): List<SeasonDetail> = filterNot { it.isSpecials }

/** Display order: main seasons ascending, then Specials last. */
fun List<SeasonDetail>.orderedForDisplay(): List<SeasonDetail> =
    sortedWith(compareBy({ it.isSpecials }, { it.seasonNumber }))

/** "Season 3" / "Specials". */
fun seasonLabel(seasonNumber: Int): String =
    if (isSpecialsSeason(seasonNumber)) "Specials" else "Season $seasonNumber"

/** "S3" / "SP" — compact prefix for episode codes and season chips. */
fun seasonShortLabel(seasonNumber: Int): String =
    if (isSpecialsSeason(seasonNumber)) "SP" else "S$seasonNumber"
