package work.kumarfamilynet.cinemarchive.core.model

const val SPIDER_NOIR_TMDB_ID = 220102
const val NOIR_PIN_KEY = "spider_noir_color"
val NOIR_MODES = setOf("bw", "color")

data class NoirProgress(val unlocked: Set<String>, val earned: Set<String>, val lastMode: String?)

/** Mirrors web episodeUtils and detail history iteration; coarse progress never earns a mode. */
fun noirProgress(title: TitleDetail): NoirProgress {
    if (title.tmdbId != SPIDER_NOIR_TMDB_ID) return NoirProgress(emptySet(), emptySet(), null)
    val all = title.seasons.flatMap { it.episodes }
    val main = title.seasons.filter { it.seasonNumber != 0 }.flatMap { it.episodes }
    val unlocked = all.flatMap { ep -> ep.watchEvents.mapNotNull { it.colorMode?.takeIf(NOIR_MODES::contains) } }.toSet()
    val earned = NOIR_MODES.filterTo(mutableSetOf()) { mode -> main.isNotEmpty() && main.all { ep -> ep.watchEvents.any { it.colorMode == mode } } }
    var last: String? = null
    all.forEach { ep ->
        ep.watchEvents.forEach { it.colorMode?.takeIf(NOIR_MODES::contains)?.let { mode -> last = mode } }
        ep.reviews.forEach { it.colorMode?.takeIf(NOIR_MODES::contains)?.let { mode -> last = mode } }
    }
    return NoirProgress(unlocked, earned, last)
}

data class NoirPreview(val titleId: String, val mode: String?, val eligible: Boolean = true)
fun effectiveNoirMode(openTitleId: String?, preview: NoirPreview?, pins: Map<String, String>): String? =
    preview?.takeIf { it.eligible && (openTitleId == null || openTitleId == it.titleId) }?.let {
        if (openTitleId == null) pins[it.titleId] else it.mode
    }?.takeIf(NOIR_MODES::contains)
