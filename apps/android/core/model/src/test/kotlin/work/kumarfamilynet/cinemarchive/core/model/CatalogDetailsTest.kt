package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CatalogDetailsTest {
    private fun detail() = TitleDetail("title",MediaType.MOVIE,"Film",2020,null,null,null,null,null,null,
        LibraryStatus.WATCHLIST,null,null,emptyList(),emptyList(),emptyList())

    @Test fun releaseDatesKeepTheirCalendarDayWhileAddedInstantsUseLocalTime() {
        val source=detail().copy(releaseDate="2026-10-08",addedAt="2026-10-08T00:30:00Z")
        val rows=source.catalogDetailRows(LocalDate.of(2026,10,7),ZoneId.of("America/Los_Angeles"))
        assertEquals(listOf(CatalogDetailRow("Releases","Oct 8, 2026"),CatalogDetailRow("Added","Oct 7, 2026")),rows)
        assertEquals("Released",source.catalogDetailRows(LocalDate.of(2026,10,8)).first().label)
        assertEquals("Oct 8, 2026",source.copy(addedAt="2026-10-08").catalogDetailRows(zone=ZoneId.of("America/Los_Angeles")).last().value)
    }

    @Test fun storedMetadataUsesReadableLanguageAndFranchiseNamesWithIndependentImdbScale() {
        val rows=detail().copy(network="HBO",runtime=90,originalLanguage="ja",studios=listOf("A Studio","B Studio"),
            collectionName="Example Collection",imdbRating=8.0,rating=4.5).catalogDetailRows()
        assertEquals(listOf(CatalogDetailRow("Network","HBO"),CatalogDetailRow("Runtime","90 min"),
            CatalogDetailRow("Language","Japanese"),CatalogDetailRow("Studio","A Studio, B Studio"),
            CatalogDetailRow("Franchise","Example"),CatalogDetailRow("IMDb","8/10")),rows)
        assertEquals(CatalogDetailRow("IMDb","0/10"),detail().copy(imdbRating=0.0).catalogDetailRows().single())
    }

    @Test fun emptyAndMalformedLegacyValuesDoNotCrashOrInventMissingMetadata() {
        assertTrue(detail().catalogDetailRows().isEmpty())
        assertTrue(detail().copy(network=" ",runtime=0,studios=listOf(""),releaseDate="",addedAt="",imdbRating=Double.NaN).catalogDetailRows().isEmpty())
        val rows=detail().copy(originalLanguage="zz",releaseDate="unknown date",addedAt="legacy value",collectionName="Collection").catalogDetailRows()
        assertEquals("ZZ",rows.first().value)
        assertEquals("unknown date",rows[1].value)
        assertEquals("Collection",rows[2].value)
        assertEquals("legacy value",rows.last().value)
    }
}
