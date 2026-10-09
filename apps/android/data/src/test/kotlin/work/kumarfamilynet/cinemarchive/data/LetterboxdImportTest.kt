package work.kumarfamilynet.cinemarchive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType

class LetterboxdImportTest {
    @Test
    fun `rejects a csv without a Name column`() {
        assertThrows(IllegalArgumentException::class.java) { parseLetterboxdCsv("Foo,Bar\n1,2") }
    }

    @Test
    fun `parses quoted fields with commas and doubled quotes`() {
        val rows = parseLetterboxdCsv(
            "Date,Name,Year,Letterboxd URI,Rating,Watched Date\n" +
                "2024-03-05,\"Hello, \"\"World\"\"\",1999,https://boxd.it/1,4.5,2024-03-01\n",
        )
        assertEquals(1, rows.size)
        assertEquals("Hello, \"World\"", rows[0].name)
        assertEquals(1999, rows[0].year)
        assertEquals(4.5, rows[0].rating!!, 0.0)
        assertEquals("2024-03-01", rows[0].watchedDate)
    }

    @Test
    fun `falls back to the log date when there is no watched date column`() {
        val rows = parseLetterboxdCsv("Date,Name,Year\n2024-03-05,Heat,1995\n")
        assertEquals("2024-03-05", rows[0].watchedDate)
        assertNull(rows[0].rating)
    }

    @Test
    fun `groups rewatches into one item with accumulated dates and the latest rating`() {
        val rows = listOf(
            LetterboxdRow("Heat", 1995, 3.0, "2023-01-01"),
            LetterboxdRow("Heat", 1995, 4.5, "2024-06-01"),
            LetterboxdRow("heat", 1995, null, "2022-01-01"),
        )
        val items = letterboxdToSyncItems(rows, LibraryStatus.WATCHED)
        assertEquals(1, items.size)
        assertEquals(4.5, items[0].rating!!, 0.0)
        assertEquals(listOf("2022-01-01", "2023-01-01", "2024-06-01"), items[0].watchedDates)
        assertEquals(MediaType.MOVIE, items[0].type)
        assertEquals(SyncProvider.LETTERBOXD, items[0].provider)
    }

    @Test
    fun `an undated ratings row always wins`() {
        val rows = listOf(
            LetterboxdRow("Heat", 1995, 3.0, "2024-06-01"),
            LetterboxdRow("Heat", 1995, 5.0, null),
        )
        assertEquals(5.0, letterboxdToSyncItems(rows, LibraryStatus.WATCHED)[0].rating!!, 0.0)
    }
}
