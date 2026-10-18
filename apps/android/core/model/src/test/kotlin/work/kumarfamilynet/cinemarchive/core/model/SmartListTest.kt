package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartListTest {

    private fun title(id: String, status: LibraryStatus) = LibraryTitle(
        id = id, name = id, year = null, posterUrl = null, status = status,
        type = MediaType.MOVIE, director = null, network = null, rating = null,
    )

    private val library = listOf(
        title("queued", LibraryStatus.WATCHLIST),
        title("midway", LibraryStatus.WATCHING),
        title("done", LibraryStatus.WATCHED),
        title("queued2", LibraryStatus.WATCHLIST),
        title("gaveUp", LibraryStatus.DROPPED),
    )

    @Test
    fun `members follow each title's status in library order`() {
        assertEquals(listOf("queued", "queued2"), SmartList.WATCHLIST.members(library).map { it.id })
        assertEquals(listOf("midway"), SmartList.WATCHING.members(library).map { it.id })
    }

    @Test
    fun `ids resolve only within the reserved smart prefix`() {
        assertEquals(SmartList.WATCHLIST, SmartList.fromId("smart:watchlist"))
        assertEquals(SmartList.WATCHING, SmartList.fromId("smart:watching"))
        assertNull(SmartList.fromId("watchlist"))
        assertNull(SmartList.fromId(null))
    }
}
