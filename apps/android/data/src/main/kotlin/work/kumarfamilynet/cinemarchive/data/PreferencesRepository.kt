package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontFamily
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontScale
import work.kumarfamilynet.cinemarchive.core.model.ArchivePalette
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode
import work.kumarfamilynet.cinemarchive.core.model.LibraryViewMode

private val Context.preferencesDataStore by preferencesDataStore(name = "cinemarchive_prefs")

/**
 * Local-only app preferences (theme, and future navigation/Ledger prefs) — deliberately
 * separate from [LibraryRepository] and the Room-backed sync layer: these never leave the
 * device and have no server counterpart (`docs/android-parity-matrix.md`'s
 * `user_prefs`-backed persistence is a distinct, still-unimplemented concern).
 */
class PreferencesRepository(context: Context) {
    private val dataStore = context.preferencesDataStore
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val paletteKey = stringPreferencesKey("palette")
    private val fontFamilyKey = stringPreferencesKey("font_family")
    private val fontScaleKey = stringPreferencesKey("font_scale")
    private val libraryViewModeKey = stringPreferencesKey("library_view_mode")
    private val autoCheckUpdatesKey = booleanPreferencesKey("auto_check_updates")
    private val posterGridColumnsKey = intPreferencesKey("poster_grid_columns")
    private val devSettingsUnlockedKey = booleanPreferencesKey("dev_settings_unlocked")
    private val devShowBuildBannerKey = booleanPreferencesKey("dev_show_build_banner")

    fun observeThemeMode(): Flow<ArchiveThemeMode> = dataStore.data.map { preferences ->
        preferences[themeModeKey]?.let { stored ->
            runCatching { ArchiveThemeMode.valueOf(stored) }.getOrNull()
        } ?: ArchiveThemeMode.DARK
    }

    suspend fun setThemeMode(mode: ArchiveThemeMode) {
        dataStore.edit { it[themeModeKey] = mode.name }
    }

    fun observePalette(): Flow<ArchivePalette> = dataStore.data.map { preferences ->
        preferences[paletteKey]?.let { stored ->
            runCatching { ArchivePalette.valueOf(stored) }.getOrNull()
        } ?: ArchivePalette.BRAND
    }

    suspend fun setPalette(palette: ArchivePalette) {
        dataStore.edit { it[paletteKey] = palette.name }
    }

    fun observeFontFamily(): Flow<ArchiveFontFamily> = dataStore.data.map { preferences ->
        preferences[fontFamilyKey]?.let { stored ->
            runCatching { ArchiveFontFamily.valueOf(stored) }.getOrNull()
        } ?: ArchiveFontFamily.DEFAULT
    }

    suspend fun setFontFamily(fontFamily: ArchiveFontFamily) {
        dataStore.edit { it[fontFamilyKey] = fontFamily.name }
    }

    fun observeFontScale(): Flow<ArchiveFontScale> = dataStore.data.map { preferences ->
        preferences[fontScaleKey]?.let { stored ->
            runCatching { ArchiveFontScale.valueOf(stored) }.getOrNull()
        } ?: ArchiveFontScale.DEFAULT
    }

    suspend fun setFontScale(fontScale: ArchiveFontScale) {
        dataStore.edit { it[fontScaleKey] = fontScale.name }
    }

    fun observeLibraryViewMode(): Flow<LibraryViewMode> = dataStore.data.map { preferences ->
        preferences[libraryViewModeKey]?.let { stored ->
            runCatching { LibraryViewMode.valueOf(stored) }.getOrNull()
        } ?: LibraryViewMode.GRID
    }

    suspend fun setLibraryViewMode(mode: LibraryViewMode) {
        dataStore.edit { it[libraryViewModeKey] = mode.name }
    }

    /** Whether the app checks for a newer release on its own. On by default; governs only the
     *  automatic check — the manual "Check for Updates" action ignores it. */
    fun observeAutoCheckUpdates(): Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[autoCheckUpdatesKey] ?: true
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        dataStore.edit { it[autoCheckUpdatesKey] = enabled }
    }

    /** Column count for the Library and Discover poster grids, set by pinching either one.
     *  Shared deliberately: they are the same poster grid on two tabs, and a density set on
     *  one reading differently on the other is the surprising behaviour. */
    fun observePosterGridColumns(): Flow<Int> = dataStore.data.map { preferences ->
        preferences[posterGridColumnsKey]?.takeIf { it in POSTER_GRID_COLUMN_RANGE }
            ?: DEFAULT_POSTER_GRID_COLUMNS
    }

    suspend fun setPosterGridColumns(columns: Int) {
        dataStore.edit { it[posterGridColumnsKey] = columns.coerceIn(POSTER_GRID_COLUMN_RANGE) }
    }

    /** Whether the Developer Settings row is visible in Settings. Debug builds default
     *  unlocked, release builds default locked; [defaultUnlocked] should come from
     *  `BuildConfig.DEBUG` at the call site — tapping the version number 7 times overrides
     *  it to `true` regardless of build type. */
    fun observeDevSettingsUnlocked(defaultUnlocked: Boolean): Flow<Boolean> =
        dataStore.data.map { preferences -> preferences[devSettingsUnlockedKey] ?: defaultUnlocked }

    suspend fun setDevSettingsUnlocked(unlocked: Boolean) {
        dataStore.edit { it[devSettingsUnlockedKey] = unlocked }
    }

    /** Whether a persistent debug/release build indicator is shown over the whole app.
     *  Defaults to [defaultEnabled] (debug builds on, release off) until the user overrides
     *  it from Developer Settings. */
    fun observeDevShowBuildBanner(defaultEnabled: Boolean): Flow<Boolean> =
        dataStore.data.map { preferences -> preferences[devShowBuildBannerKey] ?: defaultEnabled }

    suspend fun setDevShowBuildBanner(enabled: Boolean) {
        dataStore.edit { it[devShowBuildBannerKey] = enabled }
    }

    private companion object {
        val POSTER_GRID_COLUMN_RANGE = 1..4
        const val DEFAULT_POSTER_GRID_COLUMNS = 2
    }
}
