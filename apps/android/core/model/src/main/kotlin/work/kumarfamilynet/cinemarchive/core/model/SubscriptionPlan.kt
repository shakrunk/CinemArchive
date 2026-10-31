package work.kumarfamilynet.cinemarchive.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** A subscription plan's reset window (docs/android-contracts/moviegoing-prefs.md). */
enum class PlanPeriod(val wire: String) {
    WEEK("week"),
    MONTH("month");

    companion object {
        fun fromWire(value: String?): PlanPeriod? = entries.firstOrNull { it.wire == value }
    }
}

/** A theater subscription (AMC A-List, Regal Unlimited, ...): [cap] outings per [period]. */
data class SubscriptionPlan(val cap: Int, val period: PlanPeriod) {
    companion object {
        const val MAX_CAP = 100

        fun isValidCap(cap: Int): Boolean = cap in 1..MAX_CAP

        /** Parses what a person types into the cap field. Null for anything that is not a whole
         *  number within range. */
        fun parseCap(text: String): Int? = MoviegoingRules.parseWholeNumberText(text.trim())?.takeIf(::isValidCap)
    }
}

enum class PlanState { UNDER, NEAR, AT_CAP, OVER }

/** Outings used against a [SubscriptionPlan] in the period containing the day it was computed
 *  for. [periodStart]/[periodEnd] are both inclusive. */
data class PlanStatus(
    val plan: SubscriptionPlan,
    val used: Int,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val state: PlanState,
) {
    val remaining: Int get() = plan.cap - used
}

object SubscriptionPlanRules {
    /** A week runs Monday to Sunday on every device, whatever the locale's first day, so both
     *  clients and every device agree on when a weekly cap resets. A month is the calendar month. */
    fun periodRange(period: PlanPeriod, today: LocalDate): ClosedRange<LocalDate> = when (period) {
        PlanPeriod.WEEK -> {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            monday..monday.plusDays(6)
        }
        PlanPeriod.MONTH -> MoviegoingBudgetRules.periodRange(BudgetPeriod.MONTH, today)
    }

    /** Counts [tripDates] inside the period containing [today]. Near means one outing left, or
     *  at least 80% used, once any has been used; a cap used exactly is [PlanState.AT_CAP] and
     *  only a further outing is [PlanState.OVER]. */
    fun status(plan: SubscriptionPlan, tripDates: List<LocalDate>, today: LocalDate): PlanStatus {
        val range = periodRange(plan.period, today)
        val used = tripDates.count { it in range }
        val state = when {
            used > plan.cap -> PlanState.OVER
            used == plan.cap -> PlanState.AT_CAP
            used > 0 && (plan.cap - used <= 1 || used * 100 >= plan.cap * NEAR_THRESHOLD_PERCENT) -> PlanState.NEAR
            else -> PlanState.UNDER
        }
        return PlanStatus(plan, used, range.start, range.endInclusive, state)
    }

    const val NEAR_THRESHOLD_PERCENT = 80
}
