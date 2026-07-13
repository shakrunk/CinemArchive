package work.kumarfamilynet.cinemarchive.core.model

/** The four themes CinemArchiveTheme supports — lives in core:model (not core:designsystem)
 *  so the data layer can persist a user's choice without depending on Compose theming. */
enum class ArchiveThemeMode {
    DARK,
    LIGHT,
    NOIR,
    MATRIX;

    fun next(): ArchiveThemeMode = entries[(ordinal + 1) % entries.size]
}
