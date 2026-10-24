package work.kumarfamilynet.cinemarchive.core.model

const val THE_MATRIX_TMDB_ID = 603

/** Palettes that stay out of Appearance until earned — mirrors the web's `easterEggThemes.ts`. */
val ArchivePalette.isSecret: Boolean
    get() = this == ArchivePalette.NOIR || this == ArchivePalette.MATRIX

/** The title whose presence in the library reveals a secret palette's (locked) card. */
fun ArchivePalette.secretTmdbId(): Int? = when (this) {
    ArchivePalette.NOIR -> SPIDER_NOIR_TMDB_ID
    ArchivePalette.MATRIX -> THE_MATRIX_TMDB_ID
    else -> null
}

/**
 * Secret palettes stay hidden entirely until the linked title is in the library in any form —
 * they are meant to be stumbled on, not spoiled by a "locked" card. Once discovered they show
 * as locked; earning the unlock is tracked separately (see PreferencesRepository.unlockPalette).
 */
fun discoveredSecretPalettes(libraryTmdbIds: Set<Int>): Set<ArchivePalette> =
    ArchivePalette.entries.filterTo(mutableSetOf()) { palette -> palette.secretTmdbId()?.let { it in libraryTmdbIds } == true }

fun isPaletteAvailable(palette: ArchivePalette, unlocked: Set<ArchivePalette>): Boolean =
    !palette.isSecret || palette in unlocked
