package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate
import kotlin.math.roundToLong

/** A budget's calendar window (docs/android-contracts/moviegoing-prefs.md). */
enum class BudgetPeriod(val wire: String) {
    MONTH("month"),
    YEAR("year");

    companion object {
        fun fromWire(value: String?): BudgetPeriod? = entries.firstOrNull { it.wire == value }
    }
}

/** The user's moviegoing spend target: [amount] per calendar [period]. */
data class MoviegoingBudget(val amount: Double, val period: BudgetPeriod) {
    companion object {
        const val MAX_AMOUNT = 100_000.0

        fun isValidAmount(amount: Double): Boolean = amount.isFinite() && amount > 0.0 && amount <= MAX_AMOUNT

        /** Parses what a person types into the budget field: an optional `$` and thousands
         *  commas are ignored. Null for anything that is not a valid amount. */
        fun parseAmount(text: String): Double? =
            text.trim().removePrefix("$").replace(",", "").trim().toDoubleOrNull()?.takeIf(::isValidAmount)
    }
}

/** One outing with a recorded ticket price, dated by the viewing it produced. */
data class MoviegoingPricedTrip(val date: LocalDate, val amount: Double)

enum class BudgetState { UNDER, NEAR, OVER }

/** Spend against a [MoviegoingBudget] for the period containing the day it was computed for.
 *  [periodStart]/[periodEnd] are both inclusive. */
data class BudgetStatus(
    val budget: MoviegoingBudget,
    val spent: Double,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val state: BudgetState,
) {
    val remaining: Double get() = budget.amount - spent

    /** Share of the budget spent, uncapped (so 1.25 means 25% over). */
    val fraction: Double get() = spent / budget.amount
}

object MoviegoingBudgetRules {
    /** Spend at or past this share of the budget reads as "near" the cap. */
    const val NEAR_THRESHOLD_PERCENT = 80

    fun periodRange(period: BudgetPeriod, today: LocalDate): ClosedRange<LocalDate> = when (period) {
        BudgetPeriod.MONTH -> today.withDayOfMonth(1)..today.withDayOfMonth(today.lengthOfMonth())
        BudgetPeriod.YEAR -> today.withDayOfYear(1)..today.withDayOfYear(today.lengthOfYear())
    }

    /** Sums priced trips dated inside the period containing [today]. Compared in whole cents so
     *  a budget met exactly is "near", never "over", whatever the float sum does. */
    fun status(budget: MoviegoingBudget, trips: List<MoviegoingPricedTrip>, today: LocalDate): BudgetStatus {
        val range = periodRange(budget.period, today)
        val spentCents = trips.filter { it.date in range }.sumOf { cents(it.amount) }
        val budgetCents = cents(budget.amount)
        val state = when {
            spentCents > budgetCents -> BudgetState.OVER
            spentCents * 100 >= budgetCents * NEAR_THRESHOLD_PERCENT -> BudgetState.NEAR
            else -> BudgetState.UNDER
        }
        return BudgetStatus(budget, spentCents / 100.0, range.start, range.endInclusive, state)
    }

    private fun cents(amount: Double): Long = (amount * 100).roundToLong()
}
