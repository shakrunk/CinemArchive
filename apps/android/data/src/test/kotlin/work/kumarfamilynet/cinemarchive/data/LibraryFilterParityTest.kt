package work.kumarfamilynet.cinemarchive.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.model.*

/** Same fixture exercises the shipped web store and the native Library selector. */
@RunWith(RobolectricTestRunner::class)
class LibraryFilterParityTest {
    private val fixture = JSONObject(File("../../../docs/android-contracts/fixtures/library-filters.json").readText())
    private val titles = fixture.getJSONArray("titles").objects().map { row -> LibraryTitle(
        id = row.getString("id"), name = row.getString("title"), year = row.getInt("year"), posterUrl = null,
        status = LibraryStatus.valueOf(row.getString("status").uppercase()), type = MediaType.valueOf(row.getString("type").uppercase()),
        director = row.optional("director"), network = row.optional("network"), rating = if (row.has("rating")) row.getDouble("rating") else null,
        genres = row.strings("genres"), tags = row.strings("tags"), studios = row.strings("studios"), castNames = row.strings("castNames"),
        originalLanguage = row.optional("originalLanguage"), addedAt = row.optional("addedAt"), lastInteractionAt = row.optional("lastInteractionAt"),
        releaseDate = row.optional("releaseDate"), collectionId = if (row.has("collectionId")) row.getInt("collectionId") else null,
        collectionName = row.optional("collectionName"),
    ) }

    @Test fun sharedSearchFacetsAndAllSortDirections() {
        fixture.getJSONArray("cases").objects().forEach { case ->
            val f = case.getJSONObject("filters")
            val filters = LibraryFilters(
                search = f.optString("search"), type = f.optional("type")?.let { MediaType.valueOf(it.uppercase()) },
                statuses = f.optional("status")?.let { setOf(LibraryStatus.valueOf(it.uppercase())) }.orEmpty(),
                genres = f.strings("genres").toSet(), tags = f.strings("tags").toSet(), networks = f.strings("networks").toSet(),
                decades = f.strings("decades").toSet(), languages = f.strings("languages").toSet(), studio = f.optional("studio"),
                minRating = f.optDouble("minRating", 0.0), sortOrder = when (f.optString("sortField")) {
                    "title" -> LibrarySortOrder.TITLE; "year" -> LibrarySortOrder.YEAR_NEWEST; "rating" -> LibrarySortOrder.RATING_HIGHEST
                    "addedAt" -> LibrarySortOrder.ADDED_AT; "director" -> LibrarySortOrder.DIRECTOR; else -> LibrarySortOrder.LAST_INTERACTION
                }, sortDirection = if (f.optString("sortDir") == "asc") LibrarySortDirection.ASCENDING else LibrarySortDirection.DESCENDING,
            )
            assertEquals(case.getString("name"), case.strings("expected"), filterLibrary(titles, filters).map { it.id })
        }
    }

    @Test fun franchiseIdentityReleaseOrderAndStandaloneTail() {
        val groups = groupLibrary(filterLibrary(titles, LibraryFilters()), LibraryGrouping.FRANCHISE)
        val expected = fixture.getJSONArray("franchiseGroups").let { array -> (0 until array.length()).map { array.getJSONArray(it).strings() } }
        assertEquals(expected, groups.map { it.titles.map(LibraryTitle::id) })
        assertEquals(listOf("Saga", "Saga", "Other titles"), groups.map { it.label })
        val nameOnly = titles[0].copy(id = "named", collectionId = null)
        assertEquals(2, groupLibrary(listOf(titles[0], nameOnly), LibraryGrouping.FRANCHISE).size)
        assertEquals(null, groupLibrary(listOf(titles[3]), LibraryGrouping.FRANCHISE).single().label)
    }

    @Test fun nativeMultiStatusPreservesItsExistingCapabilityAndFacetChoicesUseWholeLibrary() {
        val filters = LibraryFilters(statuses = setOf(LibraryStatus.WATCHLIST, LibraryStatus.WATCHING))
        assertEquals(listOf("b", "c", "e"), filterLibrary(titles, filters).map { it.id })
        assertEquals(listOf("Action", "Comedy", "Drama", "Mystery"), libraryFilterChoices(titles).genres)
        assertEquals(2, filters.copy(genres = setOf("Drama", "Comedy")).activeFilterCount)
        val groups = groupLibrary(filterLibrary(titles, filters), LibraryGrouping.STATUS)
        assertEquals(listOf("Watching", "Watchlist"), groups.map { it.label })
    }

    private fun JSONObject.optional(key: String) = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.strings(key: String) = optJSONArray(key)?.strings().orEmpty()
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
}
