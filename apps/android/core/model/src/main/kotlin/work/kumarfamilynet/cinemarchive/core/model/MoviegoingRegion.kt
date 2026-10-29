package work.kumarfamilynet.cinemarchive.core.model

import java.util.Locale

/**
 * The country whose theatrical "now playing" list the schedule flow shows (#202), as an
 * ISO 3166-1 alpha-2 code. A region, not a location: no permission and no GPS, and TMDB has no
 * per-theater data to narrow it further. Mirrors `apps/web/src/store/moviegoingRegion.ts`.
 */
object MoviegoingRegion {
    const val FALLBACK = "US"
    private val CODE = Regex("[A-Z]{2}")

    /** The synced wire value: exactly two uppercase ASCII letters. */
    fun isValid(code: String?): Boolean = code != null && CODE.matches(code)

    /** What a user typed or a locale reported, tidied to the wire form, or null when it cannot be one. */
    fun normalize(input: String?): String? = input?.trim()?.uppercase(Locale.ROOT)?.takeIf(::isValid)

    /** The saved region, else the device locale's country, else [FALLBACK]. */
    fun effective(saved: String?, locale: Locale = Locale.getDefault()): String =
        saved?.takeIf(::isValid) ?: normalize(locale.country) ?: FALLBACK
}
