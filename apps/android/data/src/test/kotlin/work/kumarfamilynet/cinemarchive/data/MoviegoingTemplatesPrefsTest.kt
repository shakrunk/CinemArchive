package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.OutingTemplate
import work.kumarfamilynet.cinemarchive.core.model.OutingTemplateRules
import java.time.DayOfWeek
import java.time.LocalTime

/** Outing template cases from docs/android-contracts/moviegoing-prefs.md. */
@RunWith(RobolectricTestRunner::class)
class MoviegoingTemplatesPrefsTest {
    private val stamp = "\"updatedAt\":\"2026-10-29T10:00:00Z\""

    private fun parse(templates: String) = parseMoviegoingPrefs(JSONObject("""{"templates":$templates,$stamp}"""))

    private val full = """{"id":"a","name":"Tuesday matinee","venue":"AMC","companions":["Sam"],"format":"IMAX","weekday":2,"time":"14:00"}"""

    @Test fun parsesAFullTemplate() {
        val template = parse("[$full]")!!.templates.single()
        assertEquals(
            OutingTemplate("a", "Tuesday matinee", "AMC", listOf("Sam"), CinemaFormat.IMAX, DayOfWeek.TUESDAY, LocalTime.of(14, 0)),
            template,
        )
    }

    @Test fun onlyIdAndNameAreRequired() {
        val template = parse("""[{"id":"a","name":"Bare"}]""")!!.templates.single()
        assertEquals(OutingTemplate("a", "Bare", null, emptyList(), null, null, null), template)
    }

    @Test fun anAbsentOrNullListMeansNoTemplates() {
        assertTrue(parseMoviegoingPrefs(JSONObject("{$stamp}"))!!.templates.isEmpty())
        assertTrue(parse("null")!!.templates.isEmpty())
    }

    @Test fun aListThatIsNotAnArrayRejectsThePayload() {
        assertNull(parse("""{"id":"a"}"""))
        assertNull(parse("\"nope\""))
    }

    @Test fun invalidEntriesAreSkippedWithoutLosingTheRest() {
        val bad = listOf(
            """{"id":"b"}""",
            """{"id":"b","name":"  "}""",
            """{"id":"b","name":"x","weekday":0}""",
            """{"id":"b","name":"x","weekday":8}""",
            """{"id":"b","name":"x","weekday":2.5}""",
            """{"id":"b","name":"x","time":"25:00"}""",
            """{"id":"b","name":"x","time":"7pm"}""",
            """{"id":"b","name":"x","format":"Betamax"}""",
            """{"id":"b","name":"x","companions":"Sam"}""",
            """{"id":"b","name":"x","companions":[1]}""",
            """{"id":"b","name":"x","venue":4}""",
            "7",
        )
        bad.forEach { entry ->
            assertEquals(entry, listOf("a"), parse("[$full,$entry]")!!.templates.map { it.id })
        }
    }

    @Test fun aBadTemplateNeverRejectsTheBudgetAndPlan() {
        val parsed = parseMoviegoingPrefs(JSONObject(
            """{"budget":{"amount":40,"period":"month"},"templates":[{"id":"b"}],$stamp}""",
        ))
        assertNotNull(parsed)
        assertNotNull(parsed!!.budget)
    }

    @Test fun duplicateIdsKeepTheFirstAndTheListIsCapped() {
        val twice = parse("""[$full,{"id":"a","name":"Other"}]""")!!.templates
        assertEquals(listOf("Tuesday matinee"), twice.map { it.name })
        val many = (1..OutingTemplateRules.MAX_TEMPLATES + 3).joinToString(",", "[", "]") { """{"id":"t$it","name":"T$it"}""" }
        assertEquals(OutingTemplateRules.MAX_TEMPLATES, parse(many)!!.templates.size)
    }

    @Test fun writingTemplatesKeepsEveryOtherKeyAndRoundTrips() {
        val base = JSONObject("""{"budget":{"amount":40,"period":"month"},"future":{"x":1},$stamp}""")
        val template = OutingTemplate("a", "Matinee", "AMC", listOf("Sam"), CinemaFormat.DOLBY, DayOfWeek.SUNDAY, LocalTime.of(11, 30))
        val written = moviegoingPrefsWithTemplates(base, listOf(template), "2026-10-30T00:00:00Z")
        assertEquals("2026-10-30T00:00:00Z", written.getString("updatedAt"))
        assertEquals(1, written.getJSONObject("future").getInt("x"))
        val reread = parseMoviegoingPrefs(written)!!
        assertEquals(listOf(template), reread.templates)
        assertEquals(40.0, reread.budget!!.amount, 0.0)
    }

    @Test fun clearingWritesAnEmptyListWithAFreshStamp() {
        val written = moviegoingPrefsWithTemplates(JSONObject("""{"templates":[$full],$stamp}"""), emptyList(), "2026-10-30T00:00:00Z")
        assertEquals(0, written.getJSONArray("templates").length())
        assertEquals("2026-10-30T00:00:00Z", written.getString("updatedAt"))
    }
}
