package work.kumarfamilynet.cinemarchive.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.model.*

@RunWith(RobolectricTestRunner::class)
class PersonFilterParityTest {
    private val fixture = JSONObject(File("../../../docs/android-contracts/fixtures/library-person-filters.json").readText())
    private val titles = fixture.getJSONArray("titles").objects().map { row ->
        val seasons = row.getJSONArray("seasons").objects()
        val credits = row.getJSONArray("cast").objects() + row.getJSONArray("crew").objects() +
            seasons.flatMap { it.getJSONArray("cast").objects() } +
            seasons.flatMap { it.getJSONArray("episodes").objects() }.flatMap { it.getJSONArray("crew").objects() }
        LibraryTitle(row.getString("id"), row.getString("title"), row.getInt("year"), null,
            LibraryStatus.valueOf(row.getString("status").uppercase()), MediaType.valueOf(row.getString("type").uppercase()), null, null, null,
            genres = row.getJSONArray("genres").strings(), castNames = row.getJSONArray("cast").objects().map { it.getString("name") },
            addedAt = row.getString("addedAt"), lastInteractionAt = row.getString("addedAt"),
            people = credits.map { LibraryPerson(it.getInt("tmdbPersonId"), it.getString("name")) }.distinctBy { it.tmdbPersonId })
    }

    @Test fun sharedIdBasedPersonFilterAcrossAllCreditKindsAndCombinedFacets() {
        fixture.getJSONArray("cases").objects().forEach { case ->
            val f = case.getJSONObject("filters")
            val person = f.getJSONObject("person")
            val filters = LibraryFilters(person = LibraryPerson(person.getInt("id"), person.getString("name")),
                search = f.optString("search"), type = if (f.has("type")) MediaType.valueOf(f.getString("type").uppercase()) else null,
                statuses = if (f.has("status")) setOf(LibraryStatus.valueOf(f.getString("status").uppercase())) else emptySet(),
                genres = f.optJSONArray("genres")?.strings()?.toSet().orEmpty())
            assertEquals(case.getString("name"), case.getJSONArray("expected").strings(), filterLibrary(titles, filters).map { it.id })
        }
    }

    @Test fun pickerKeepsNamesakesSeparateWithTheirOwnTitleContextAndResetRemovesPerson() {
        val choices = libraryFilterChoices(titles).people.filter { it.person.name == "Same Name" }
        assertEquals(listOf(42, 84), choices.map { it.person.tmdbPersonId })
        assertEquals(listOf("Namesake", "Special"), choices.last().titles)
        val filtered = LibraryFilters(genres = setOf("Drama"), person = LibraryPerson(42, "Same Name"))
        assertEquals(2, filtered.activeFilterCount)
        assertEquals(listOf("cast", "season"), filterLibrary(titles, filtered).map { it.id })
        assertNull(LibraryFilters().person)
    }

    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings() = (0 until length()).map(::getString)
}
