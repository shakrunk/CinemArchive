package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode

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

    fun observeThemeMode(): Flow<ArchiveThemeMode> = dataStore.data.map { preferences ->
        preferences[themeModeKey]?.let { stored ->
            runCatching { ArchiveThemeMode.valueOf(stored) }.getOrNull()
        } ?: ArchiveThemeMode.DARK
    }

    suspend fun setThemeMode(mode: ArchiveThemeMode) {
        dataStore.edit { it[themeModeKey] = mode.name }
    }
}
