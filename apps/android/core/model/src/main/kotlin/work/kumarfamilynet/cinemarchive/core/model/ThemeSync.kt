package work.kumarfamilynet.cinemarchive.core.model

/**
 * The cross-client vocabulary and apply rules for opt-in appearance sync
 * (docs/android-contracts/theme-sync.md; cases pinned in fixtures/theme-sync.json).
 */

/** `CATPPUCCIN_MOCHA` → `catppuccin-mocha`, `MATERIAL_YOU` → `material-you`. */
val ArchivePalette.syncId: String get() = name.lowercase().replace('_', '-')

fun archivePaletteForSyncId(id: String): ArchivePalette? = ArchivePalette.entries.firstOrNull { it.syncId == id }

val ArchiveThemeMode.syncId: String get() = name.lowercase()

fun archiveThemeModeForSyncId(id: String): ArchiveThemeMode? = ArchiveThemeMode.entries.firstOrNull { it.syncId == id }

data class SyncedAppearance(val palette: ArchivePalette, val mode: ArchiveThemeMode, val pureBlack: Boolean)

/**
 * What [remote] resolves to on this device. Brand and Material You carry their mode; every other
 * palette has one fixed appearance, so this device keeps its own mode for when it returns to
 * Brand. A secret palette not unlocked here keeps the current palette. Pure black always applies.
 */
fun applySyncedAppearance(
    remote: SyncedAppearance,
    current: SyncedAppearance,
    unlocked: Set<ArchivePalette>,
): SyncedAppearance = when {
    remote.palette == ArchivePalette.BRAND || remote.palette == ArchivePalette.MATERIAL_YOU -> remote
    !isPaletteAvailable(remote.palette, unlocked) -> current.copy(pureBlack = remote.pureBlack)
    else -> current.copy(palette = remote.palette, pureBlack = remote.pureBlack)
}
