package work.kumarfamilynet.cinemarchive.core.model

/**
 * A season's cast with the people missing from the series' main cast first, so the season's own
 * additions lead instead of a repeat of the series regulars. Billing order is kept within each
 * group. Mirrors `seasonCastNewcomersFirst` in the web app's `src/store/episodeUtils.ts`.
 */
fun List<PersonCredit>.newcomersFirst(seriesCast: List<PersonCredit>): List<PersonCredit> {
    val seriesIds = seriesCast.mapTo(HashSet()) { it.tmdbPersonId }
    val (returning, newcomers) = partition { it.tmdbPersonId in seriesIds }
    return newcomers + returning
}
