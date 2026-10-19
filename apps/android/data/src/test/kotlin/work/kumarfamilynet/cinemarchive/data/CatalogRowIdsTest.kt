package work.kumarfamilynet.cinemarchive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Fixture values were produced by the web app's apps/web/src/lib/catalogRowIds.ts and checked
 * against Python's independent `uuid.uuid5`. Both clients must derive the same row ids.
 */
class CatalogRowIdsTest {
    private val titleId = "6f1c2a4e-3b5d-4c7e-9f10-2a3b4c5d6e7f"

    @Test fun seasonIdsMatchWebFixtures() {
        assertEquals("b4dbb8b4-f1a6-51fa-82c8-426d696ec1d0", catalogSeasonId(titleId, 1))
        assertEquals("98e0668f-ad5a-5a97-8685-39ab2fd7a253", catalogSeasonId(titleId, 12))
    }

    @Test fun episodeIdsMatchWebFixtures() {
        assertEquals("35e09bcb-7fa4-5638-bec1-5b345c62fa9c", catalogEpisodeId(titleId, 1, 1))
        assertEquals("dc3381fc-16c6-5bab-a639-b1faa7629659", catalogEpisodeId(titleId, 2, 10))
        assertEquals("cbca903b-3d65-568c-a569-e55a03906367", catalogEpisodeId(titleId, 0, 3))
    }

    @Test fun idsIgnoreTitleIdCaseAndSeparateRowKinds() {
        assertEquals(catalogSeasonId(titleId, 1), catalogSeasonId(titleId.uppercase(), 1))
        assertNotEquals(catalogSeasonId(titleId, 1), catalogEpisodeId(titleId, 1, 1))
        assertNotEquals(catalogEpisodeId(titleId, 1, 12), catalogEpisodeId(titleId, 11, 2))
    }
}
