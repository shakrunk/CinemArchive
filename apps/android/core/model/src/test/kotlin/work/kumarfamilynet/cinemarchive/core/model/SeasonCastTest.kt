package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonCastTest {
    private fun credit(id: Int) = PersonCredit(id, "Actor $id", role = null)

    @Test
    fun newcomersLeadAndBillingOrderHoldsWithinEachGroup() {
        val season = listOf(credit(1), credit(5), credit(2), credit(6))
        val series = listOf(credit(1), credit(2), credit(3))
        assertEquals(listOf(5, 6, 1, 2), season.newcomersFirst(series).map { it.tmdbPersonId })
    }

    @Test
    fun keepsSeasonOrderWhenSeriesCastIsEmpty() {
        val season = listOf(credit(2), credit(1))
        assertEquals(listOf(2, 1), season.newcomersFirst(emptyList()).map { it.tmdbPersonId })
    }
}
