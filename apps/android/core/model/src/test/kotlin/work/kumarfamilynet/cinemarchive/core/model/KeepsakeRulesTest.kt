package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepsakeRulesTest {
    @Test fun acceptsOnlyImageTypesInsideTheSizeLimit() {
        assertTrue(KeepsakeRules.isValidPhoto("image/jpeg", 1))
        assertTrue(KeepsakeRules.isValidPhoto("image/webp", KeepsakeRules.MAX_BYTES))
        assertFalse(KeepsakeRules.isValidPhoto("image/jpeg", 0))
        assertFalse(KeepsakeRules.isValidPhoto("image/jpeg", KeepsakeRules.MAX_BYTES + 1))
        assertFalse(KeepsakeRules.isValidPhoto("image/gif", 10))
    }

    @Test fun anOutingKeepsAtMostEightPhotos() {
        assertTrue(KeepsakeRules.canAddAnother(7))
        assertFalse(KeepsakeRules.canAddAnother(8))
    }

    @Test fun objectKeysAreOwnerFolderThenId() {
        assertEquals("owner/id", KeepsakeRules.objectKey("owner", "id"))
    }

    @Test fun downscalesTheLongestEdgeKeepingTheAspectRatio() {
        assertEquals(1000 to 800, KeepsakeRules.scaledSize(1000, 800))
        assertEquals(2048 to 1536, KeepsakeRules.scaledSize(4096, 3072))
        assertEquals(1536 to 2048, KeepsakeRules.scaledSize(3000, 4000))
        assertEquals(2048 to 1, KeepsakeRules.scaledSize(100_000, 1))
    }
}
