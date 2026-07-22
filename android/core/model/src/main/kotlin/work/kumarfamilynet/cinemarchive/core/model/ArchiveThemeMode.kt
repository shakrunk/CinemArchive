package work.kumarfamilynet.cinemarchive.core.model

/** Light/dark resolution, independent of [ArchivePalette]. [SYSTEM] follows the device
 *  setting; [LIGHT]/[DARK] pin it. Doesn't apply to [ArchivePalette.NOIR]/[ArchivePalette.MATRIX],
 *  which are single fixed-dark palettes regardless of this setting. */
enum class ArchiveThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Color palette. [BRAND] and [MATERIAL_YOU] resolve light/dark via [ArchiveThemeMode];
 *  [NOIR] and [MATRIX] are unlockable easter-egg palettes with one fixed appearance each. */
enum class ArchivePalette {
    BRAND,
    MATERIAL_YOU,
    NOIR,
    MATRIX,
}
