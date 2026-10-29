package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.model.BudgetPeriod
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingBudget
import java.time.Instant

/** Payload and merge cases from docs/android-contracts/moviegoing-prefs.md. */
@RunWith(RobolectricTestRunner::class)
class MoviegoingPrefsTest {
    private fun json(text: String) = JSONObject(text)

    private fun prefs(updatedAt: String, budget: String = """{"amount":40,"period":"month"}""") =
        parseMoviegoingPrefs(json("""{"budget":$budget,"updatedAt":"$updatedAt"}"""))!!

    @Test fun parsesAValidBudget() {
        val parsed = prefs("2026-10-29T10:00:00Z")
        assertEquals(MoviegoingBudget(40.0, BudgetPeriod.MONTH), parsed.budget)
        assertEquals(Instant.parse("2026-10-29T10:00:00Z"), parsed.updatedAt)
    }

    @Test fun anAbsentOrNullBudgetMeansNoBudget() {
        assertNull(parseMoviegoingPrefs(json("""{"updatedAt":"2026-10-29T10:00:00Z"}"""))!!.budget)
        assertNull(parseMoviegoingPrefs(json("""{"budget":null,"updatedAt":"2026-10-29T10:00:00Z"}"""))!!.budget)
    }

    @Test fun rejectsTheWholePayloadOnAnyInvalidField() {
        val stamp = "\"updatedAt\":\"2026-10-29T10:00:00Z\""
        listOf(
            """{"budget":{"amount":0,"period":"month"},$stamp}""",
            """{"budget":{"amount":-3,"period":"month"},$stamp}""",
            """{"budget":{"amount":100001,"period":"month"},$stamp}""",
            """{"budget":{"amount":"40","period":"month"},$stamp}""",
            """{"budget":{"amount":40,"period":"week"},$stamp}""",
            """{"budget":{"amount":40},$stamp}""",
            """{"budget":"forty",$stamp}""",
            """{"budget":{"amount":40,"period":"month"}}""",
            """{"budget":{"amount":40,"period":"month"},"updatedAt":"yesterday"}""",
            """{"budget":{"amount":40,"period":"month"},"updatedAt":12}""",
        ).forEach { assertNull(it, parseMoviegoingPrefs(json(it))) }
    }

    @Test fun settingABudgetKeepsKeysThisClientDoesNotKnow() {
        val base = json("""{"budget":null,"region":"CA","updatedAt":"2026-10-29T10:00:00Z"}""")
        val next = moviegoingPrefsWithBudget(base, MoviegoingBudget(25.5, BudgetPeriod.YEAR), "2026-10-30T09:00:00Z")
        assertEquals("CA", next.getString("region"))
        assertEquals("2026-10-30T09:00:00Z", next.getString("updatedAt"))
        val parsed = parseMoviegoingPrefs(next)!!
        assertEquals(MoviegoingBudget(25.5, BudgetPeriod.YEAR), parsed.budget)
        // The input is not mutated.
        assertTrue(base.isNull("budget"))
    }

    @Test fun clearingWritesAnExplicitNullWithAFreshStamp() {
        val base = prefs("2026-10-29T10:00:00Z").json
        val next = moviegoingPrefsWithBudget(base, null, "2026-10-30T09:00:00Z")
        assertTrue(next.has("budget") && next.isNull("budget"))
        assertNull(parseMoviegoingPrefs(next)!!.budget)
        assertEquals("2026-10-30T09:00:00Z", next.getString("updatedAt"))
    }

    @Test fun startingFromNothingProducesAValidPayload() {
        val next = moviegoingPrefsWithBudget(null, MoviegoingBudget(40.0, BudgetPeriod.MONTH), "2026-10-29T10:00:00Z")
        assertNotNull(parseMoviegoingPrefs(next))
    }

    @Test fun aNewerRemoteIsApplied() {
        val decision = resolveMoviegoingPrefsReconciliation(prefs("2026-10-30T00:00:00Z").json, prefs("2026-10-29T00:00:00Z"))
        assertTrue(decision is MoviegoingPrefsReconciliation.ApplyRemote)
    }

    @Test fun aNewerLocalIsPushed() {
        val decision = resolveMoviegoingPrefsReconciliation(prefs("2026-10-29T00:00:00Z").json, prefs("2026-10-30T00:00:00Z"))
        assertEquals(MoviegoingPrefsReconciliation.PushLocal, decision)
    }

    @Test fun equalStampsChangeNothing() {
        val decision = resolveMoviegoingPrefsReconciliation(prefs("2026-10-29T00:00:00Z").json, prefs("2026-10-29T00:00:00Z"))
        assertEquals(MoviegoingPrefsReconciliation.Keep, decision)
    }

    @Test fun noRemoteRowPushesALocalCopyAndOtherwiseDoesNothing() {
        assertEquals(MoviegoingPrefsReconciliation.PushLocal, resolveMoviegoingPrefsReconciliation(null, prefs("2026-10-29T00:00:00Z")))
        assertEquals(MoviegoingPrefsReconciliation.Keep, resolveMoviegoingPrefsReconciliation(null, null))
    }

    @Test fun noLocalCopyAppliesTheRemoteOne() {
        val decision = resolveMoviegoingPrefsReconciliation(prefs("2026-10-29T00:00:00Z").json, null)
        assertTrue(decision is MoviegoingPrefsReconciliation.ApplyRemote)
    }

    @Test fun anInvalidRemoteIsNeitherAppliedNorOverwritten() {
        val invalid = json("""{"budget":{"amount":-1,"period":"month"},"updatedAt":"2026-12-01T00:00:00Z"}""")
        assertEquals(MoviegoingPrefsReconciliation.Keep, resolveMoviegoingPrefsReconciliation(invalid, prefs("2026-10-29T00:00:00Z")))
        assertEquals(MoviegoingPrefsReconciliation.Keep, resolveMoviegoingPrefsReconciliation(invalid, null))
    }
}
