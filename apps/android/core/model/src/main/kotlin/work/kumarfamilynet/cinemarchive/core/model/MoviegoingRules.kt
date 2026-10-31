package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Parsing and formatting rules that the moviegoing budget, plan and recap share with the web
 * client (docs/android-contracts/moviegoing-prefs.md, moviegoing-recap.md). Each rule is pinned
 * by the cross-client fixture docs/android-contracts/fixtures/moviegoing.json; the web twins
 * live in apps/web/src/store/moviegoingRules.ts.
 */
object MoviegoingRules {
    /** An optional sign, then digits with an optional point: ASCII only, no exponent, no suffix. */
    private val AMOUNT = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)")
    private val WHOLE_NUMBER = Regex("[+-]?\\d+")

    /** A UTC instant `YYYY-MM-DDTHH:mm:ss[.f]Z` with up to nine fractional digits, nothing looser. */
    private val INSTANT = Regex("\\d{4}-\\d{2}-\\d{2}T([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(\\.\\d{1,9})?Z")

    /** The calendar date a viewing's date starts with, or null when it has none or it is not a real date. */
    fun parseTripDate(value: String?): LocalDate? =
        value?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    /** A viewing is a cinema trip only with a non-empty venue; an empty string is the same as none. */
    fun isTripVenue(venue: String?): Boolean = !venue.isNullOrEmpty()

    /** The instant a synced `updatedAt` names, or null when it is not exactly the shape above. */
    fun parseInstant(value: String?): Instant? {
        if (value == null || !INSTANT.matches(value)) return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** What a person types into the budget field, as plain decimal text. Null when it is not one. */
    fun parseAmountText(cleaned: String): Double? =
        cleaned.takeIf { AMOUNT.matches(it) }?.toDoubleOrNull()

    /** What a person types into the cap field, as whole-number text. Null when it is not one. */
    fun parseWholeNumberText(trimmed: String): Int? =
        trimmed.takeIf { WHOLE_NUMBER.matches(it) }?.toIntOrNull()

    /** "$1,234.50": a dollar sign, thousands commas and two decimals, built from whole cents so
     *  the device locale never changes the figure. */
    fun formatMoney(amount: Double): String {
        val total = Math.abs(MoviegoingBudgetRules.cents(amount))
        val dollars = (total / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val sign = if (amount < 0 && total > 0) "-" else ""
        return "$sign\$$dollars.${(total % 100).toString().padStart(2, '0')}"
    }
}
