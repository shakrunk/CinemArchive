package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollRulesTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")

    private fun option(id: String, showtime: String, votes: Int) =
        PollOption(id, 1, 1, "Film $id", 2026, null, showtime, null, votes, false, emptyList())

    private fun poll(status: PollStatus = PollStatus.OPEN, vararg options: PollOption) =
        OutingPoll("p", "Which?", status, true, "Olive", "2026-10-30T10:00:00Z", null, emptyList(), options.toList())

    @Test fun aPollTakesVotesWhileOpenAndAnOptionIsStillAhead() {
        val future = option("a", "2026-11-02T19:00:00Z", 0)
        val past = option("b", "2026-10-31T19:00:00Z", 0)
        assertTrue(PollRules.canVote(poll(PollStatus.OPEN, future, past), now))
        assertFalse(PollRules.canVote(poll(PollStatus.OPEN, past), now))
        assertFalse(PollRules.canVote(poll(PollStatus.CLOSED, future), now))
        assertFalse(PollRules.canVote(poll(PollStatus.CANCELLED, future), now))
    }

    @Test fun leadersAreTheMostTickedUpcomingOptionsAndNoneWithoutVotes() {
        val list = poll(
            PollStatus.OPEN,
            option("a", "2026-11-02T19:00:00Z", 2),
            option("b", "2026-11-03T19:00:00Z", 2),
            option("c", "2026-11-04T19:00:00Z", 1),
            option("old", "2026-10-31T19:00:00Z", 9),
        )
        assertEquals(listOf("a", "b"), PollRules.leaders(list, now).map { it.id })
        assertTrue(PollRules.leaders(poll(PollStatus.OPEN, option("a", "2026-11-02T19:00:00Z", 0)), now).isEmpty())
    }

    @Test fun aValidPollHasNoProblem() {
        val candidates = listOf(
            PollCandidate("t1", Instant.parse("2026-11-02T19:00:00Z"), "AMC"),
            PollCandidate("t2", Instant.parse("2026-11-03T19:00:00Z"), null),
        )
        assertNull(PollRules.problem("Friday?", candidates, 2, now))
    }

    @Test fun problemsAreNamedBeforeAnythingIsSent() {
        val a = PollCandidate("t1", Instant.parse("2026-11-02T19:00:00Z"), null)
        val b = PollCandidate("t2", Instant.parse("2026-11-03T19:00:00Z"), null)
        assertTrue(PollRules.problem("x".repeat(101), listOf(a, b), 1, now)!!.contains("question"))
        assertTrue(PollRules.problem("q", listOf(a), 1, now)!!.contains("options"))
        assertTrue(PollRules.problem("q", listOf(a, b, a, b, a), 1, now)!!.contains("options"))
        assertTrue(PollRules.problem("q", listOf(a, b.copy(showtime = Instant.parse("2026-10-31T19:00:00Z"))), 1, now)!!.contains("future"))
        assertTrue(PollRules.problem("q", listOf(a, a), 1, now)!!.contains("same"))
        assertTrue(PollRules.problem("q", listOf(a, b.copy(venue = "v".repeat(101))), 1, now)!!.contains("venue"))
        assertTrue(PollRules.problem("q", listOf(a, b), 0, now)!!.contains("friends"))
        assertTrue(PollRules.problem("q", listOf(a, b), 9, now)!!.contains("friends"))
    }
}
