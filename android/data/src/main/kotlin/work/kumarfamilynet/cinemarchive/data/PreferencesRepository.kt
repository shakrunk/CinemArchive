package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.edit
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
}
