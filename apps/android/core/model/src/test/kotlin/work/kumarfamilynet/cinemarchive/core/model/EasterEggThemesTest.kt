package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasterEggThemesTest {
    @Test fun secretPalettesStayHiddenUntilTheirTitleIsInTheLibrary() {
        assertEquals(emptySet<ArchivePalette>(), discoveredSecretPalettes(setOf(1, 2, 3)))
        assertEquals(setOf(ArchivePalette.NOIR), discoveredSecretPalettes(setOf(SPIDER_NOIR_TMDB_ID)))
        assertEquals(setOf(ArchivePalette.MATRIX), discoveredSecretPalettes(setOf(THE_MATRIX_TMDB_ID)))
    }

    @Test fun onlySecretPalettesNeedAnUnlock() {
        assertTrue(isPaletteAvailable(ArchivePalette.BRAND, emptySet()))
        assertTrue(isPaletteAvailable(ArchivePalette.CATPPUCCIN_MOCHA, emptySet()))
        assertFalse(isPaletteAvailable(ArchivePalette.NOIR, emptySet()))
        assertFalse(isPaletteAvailable(ArchivePalette.MATRIX, setOf(ArchivePalette.NOIR)))
        assertTrue(isPaletteAvailable(ArchivePalette.MATRIX, setOf(ArchivePalette.MATRIX)))
    }
}
