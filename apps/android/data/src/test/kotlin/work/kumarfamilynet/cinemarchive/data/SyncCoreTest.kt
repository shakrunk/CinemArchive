package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType

class SyncCoreTest {
    private fun item(
        type: MediaType = MediaType.MOVIE,
        status: LibraryStatus = LibraryStatus.WATCHED,
        rating: Double? = null,
        dates: List<String> = emptyList(),
    ) = SyncItem(SyncProvider.SIMKL, "1", type, "Heat", 1995, ExternalIds(), status, rating, dates)

    private fun snapshot(
        status: LibraryStatus = LibraryStatus.WATCHLIST,
        rating: Double? = null,
        dates: Set<String> = emptySet(),
    ) = TitleSyncSnapshot(status, rating, dates)

    @Test
    fun `ratings convert between 1-10 and half stars`() {
        assertEquals(5.0, ratingFromTen(10.0)!!, 0.0)
        assertEquals(3.5, ratingFromTen(7.0)!!, 0.0)
        assertEquals(0.5, ratingFromTen(1.0)!!, 0.0)
        assertNull(ratingFromTen(0.0))
        assertNull(ratingFromTen(null))
        assertEquals(7, ratingToTen(3.5))
        assertEquals(1, ratingToTen(0.5))
    }

    @Test
    fun `parseGuids reads modern and legacy guids and ignores junk`() {
        assertEquals(
            ExternalIds(603, "tt0133093", 78901),
            parseGuids(listOf("tmdb://603", "imdb://tt0133093", "tvdb://78901")),
        )
        assertEquals(ExternalIds(imdb = "tt0133093"), parseGuids(listOf("com.plexapp.agents.imdb://tt0133093?lang=en")))
        assertEquals(ExternalIds(), parseGuids(listOf(null, "plex://movie/5d77", "imdb://nope")))
    }

    @Test
    fun `toDateOnly normalizes timestamps and epoch seconds`() {
        assertEquals("2024-03-05", toDateOnly("2024-03-05T22:10:00Z"))
        assertEquals("2024-03-05", toDateOnly("2024-03-05"))
        assertNull(toDateOnly("garbage"))
        assertEquals("2024-05-01", toDateOnly(1714600000L))
    }

    @Test
    fun `planMerge is a no-op when nothing is new`() {
        val existing = snapshot(LibraryStatus.WATCHED, 4.0, setOf("2024-01-01"))
        assertNull(planMerge(existing, MediaType.MOVIE, item(rating = 2.0, dates = listOf("2024-01-01"))))
    }

    @Test
    fun `planMerge never overwrites an existing rating`() {
        assertNull(planMerge(snapshot(LibraryStatus.WATCHED, 4.0), MediaType.MOVIE, item(rating = 1.0)))
    }

    @Test
    fun `planMerge fills an empty rating and promotes the watchlist`() {
        val patch = planMerge(snapshot(), MediaType.MOVIE, item(rating = 4.0))!!
        assertEquals(LibraryStatus.WATCHED, patch.status)
        assertEquals(4.0, patch.rating!!, 0.0)
    }

    @Test
    fun `planMerge adds only unseen viewing dates and only for movies`() {
        val existing = snapshot(LibraryStatus.WATCHED, dates = setOf("2024-01-01"))
        val patch = planMerge(existing, MediaType.MOVIE, item(dates = listOf("2024-01-01", "2024-02-02")))!!
        assertEquals(listOf("2024-02-02"), patch.newViewingDates)
        assertNull(planMerge(existing, MediaType.TV, item(type = MediaType.TV, dates = listOf("2024-02-02"))))
    }

    @Test
    fun `pickBestMatch prefers exact title and year and refuses lookalikes`() {
        val remake = MediaSearchResult(2, "Heat", 2023, MediaType.MOVIE, null, null)
        val original = MediaSearchResult(1, "Heat", 1995, MediaType.MOVIE, null, null)
        assertEquals(1, pickBestMatch(listOf(remake, original), "Heat", 1995, MediaType.MOVIE)!!.tmdbId)
        assertNull(pickBestMatch(listOf(MediaSearchResult(3, "Cold", 1960, MediaType.MOVIE, null, null)), "Heat", 1995, MediaType.MOVIE))
        val show = MediaSearchResult(9, "Severance", 2022, MediaType.TV, null, null)
        assertNull(pickBestMatch(listOf(show), "Different Show", null, MediaType.TV))
    }

    @Test
    fun `plex mapping keeps watched or rated items with guids`() {
        val items = JSONArray(
            """[
              {"ratingKey":"1","type":"movie","title":"Heat","year":1995,"userRating":8,"viewCount":2,"lastViewedAt":1714600000,
               "Guid":[{"id":"tmdb://949"},{"id":"imdb://tt0113277"}]},
              {"ratingKey":"2","type":"movie","title":"Unwatched"},
              {"ratingKey":"3","type":"show","title":"Severance","viewCount":1,"leafCount":9,"viewedLeafCount":9,"Guid":[{"id":"tvdb://371980"}]},
              {"ratingKey":"4","type":"show","title":"Slow Horses","leafCount":30,"viewedLeafCount":6}
            ]""",
        )
        val out = mapPlexItems(items)
        assertEquals(3, out.size)
        assertEquals(LibraryStatus.WATCHING, out[2].status)
        assertEquals(4.0, out[0].rating!!, 0.0)
        assertEquals(ExternalIds(949, "tt0113277"), out[0].ids)
        assertEquals(listOf("2024-05-01"), out[0].watchedDates)
        assertEquals(MediaType.TV, out[1].type)
        assertEquals(emptyList<String>(), out[1].watchedDates)
    }

    @Test
    fun `emby mapping reads provider ids and played state`() {
        val items = JSONArray(
            """[
              {"Id":"a","Type":"Movie","Name":"Heat","ProductionYear":1995,
               "ProviderIds":{"Tmdb":"949","Imdb":"tt0113277"},
               "UserData":{"Played":true,"LastPlayedDate":"2024-05-01T10:00:00.0000000Z"}},
              {"Id":"b","Type":"Movie","Name":"Nope","UserData":{"Played":false}},
              {"Id":"c","Type":"Series","Name":"Slow Horses","RecursiveItemCount":30,"UserData":{"Played":false,"UnplayedItemCount":24}}
            ]""",
        )
        val out = mapEmbyItems(items)
        assertEquals(2, out.size)
        assertEquals(LibraryStatus.WATCHING, out[1].status)
        assertEquals(ExternalIds(949, "tt0113277"), out[0].ids)
        assertEquals(listOf("2024-05-01"), out[0].watchedDates)
    }

    @Test
    fun `simkl mapping converts ratings and keeps the last watch date for watched movies`() {
        val items = JSONArray(
            """[
              {"externalId":"1","type":"movie","title":"Heat","year":1995,"ids":{"imdb":"tt0113277"},"status":"watched","rating":9,"lastWatchedAt":"2024-05-01T20:00:00Z"},
              {"externalId":"2","type":"tv","title":"Severance","ids":{},"status":"watched","lastWatchedAt":"2024-05-01T00:00:00Z"}
            ]""",
        )
        val out = mapSimklItems(items)
        assertEquals(4.5, out[0].rating!!, 0.0)
        assertEquals(listOf("2024-05-01"), out[0].watchedDates)
        assertEquals(emptyList<String>(), out[1].watchedDates)
    }

    @Test
    fun `normalizeEmbyUrl adds https and trims`() {
        assertEquals("https://media.example.com", normalizeEmbyUrl("media.example.com/"))
        assertEquals("https://x.test:8920", normalizeEmbyUrl("https://x.test:8920/emby"))
        assertThrows(IllegalArgumentException::class.java) { normalizeEmbyUrl("  ") }
    }
}
