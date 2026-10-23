package work.kumarfamilynet.cinemarchive.core.model

/** Light/dark resolution, independent of [ArchivePalette]. [SYSTEM] follows the device
 *  setting; [LIGHT]/[DARK] pin it. Applies only to [ArchivePalette.BRAND]/[ArchivePalette.MATERIAL_YOU];
 *  every other palette has one fixed appearance regardless of this setting. */
enum class ArchiveThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Color palette. [BRAND] and [MATERIAL_YOU] resolve light/dark via [ArchiveThemeMode];
 *  [NOIR] and [MATRIX] are unlockable easter-egg palettes with one fixed appearance each, as are
 *  the four Catppuccin flavors ([CATPPUCCIN_LATTE] light, the rest dark). */
enum class ArchivePalette {
    BRAND,
    MATERIAL_YOU,
    NOIR,
    MATRIX,
    CATPPUCCIN_LATTE,
    CATPPUCCIN_FRAPPE,
    CATPPUCCIN_MACCHIATO,
    CATPPUCCIN_MOCHA,
}
