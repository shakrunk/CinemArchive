package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.temporal.ChronoUnit
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontFamily
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontScale
import work.kumarfamilynet.cinemarchive.core.model.ArchivePalette
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode
import work.kumarfamilynet.cinemarchive.core.model.LibraryViewMode
import work.kumarfamilynet.cinemarchive.core.model.NavigationDestination
import work.kumarfamilynet.cinemarchive.core.model.NavigationPreferences
import work.kumarfamilynet.cinemarchive.core.model.SyncedAppearance
import work.kumarfamilynet.cinemarchive.core.model.isSecret

private val Context.preferencesDataStore by preferencesDataStore(name = "cinemarchive_prefs")

/**
 * Device app preferences (theme, navigation, text, updates) — deliberately separate from
 * [LibraryRepository] and the Room-backed sync layer. They stay on the device, except the
 * appearance (palette, mode, pure black), which [ThemeSyncRepository] mirrors to
 * `user_prefs.theme_prefs` when the user opts in (docs/android-contracts/theme-sync.md).
 */
class PreferencesRepository(context: Context) {
    private val dataStore = context.preferencesDataStore
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val paletteKey = stringPreferencesKey("palette")
    private val unlockedPalettesKey = stringSetPreferencesKey("unlocked_palettes")
    private val fontFamilyKey =stringPreferencesKey("font_family")
    private val fontScaleKey = stringPreferencesKey("font_scale")
    private val libraryViewModeKey = stringPreferencesKey("library_view_mode")
    private val autoCheckUpdatesKey = booleanPreferencesKey("auto_check_updates")
    private val posterGridColumnsKey = intPreferencesKey("poster_grid_columns")
    private val devSettingsUnlockedKey = booleanPreferencesKey("dev_settings_unlocked")
    private val devShowBuildBannerKey = booleanPreferencesKey("dev_show_build_banner")
    private val navigationOrderKey = stringPreferencesKey("navigation_order")
    private val navigationHiddenKey = stringPreferencesKey("navigation_hidden")
    private val navigationCompactKey = booleanPreferencesKey("navigation_compact")
    private val glassBlurKey = booleanPreferencesKey("glass_blur")
    private val pureBlackKey = booleanPreferencesKey("pure_black")
    private val spoilerProtectionKey = booleanPreferencesKey("spoiler_protection")
    private val themeSyncEnabledKey = booleanPreferencesKey("theme_sync_enabled")
    // ISO instant of the last user-made appearance change here, or of the synced copy last
    // applied — what theme sync's last-write-wins compares against the account's copy.
    private val themeUpdatedAtKey = stringPreferencesKey("theme_updated_at")

    fun observeNavigation(): Flow<NavigationPreferences> = dataStore.data.map(::navigation)

    private fun navigation(prefs: androidx.datastore.preferences.core.Preferences) = NavigationPreferences.restore(
        prefs[navigationOrderKey].orEmpty().split(','), prefs[navigationHiddenKey].orEmpty().split(',').toSet(),
        prefs[navigationCompactKey] ?: false)

    private suspend fun updateNavigation(change: (NavigationPreferences) -> NavigationPreferences) {
        dataStore.edit { prefs ->
            val next = change(navigation(prefs))
            prefs[navigationOrderKey] = next.order.joinToString(",") { it.key }
            prefs[navigationHiddenKey] = next.hidden.joinToString(",") { it.key }
            prefs[navigationCompactKey] = next.compact
        }
    }

    suspend fun moveNavigation(destination: NavigationDestination, direction: Int) = updateNavigation { it.move(destination, direction) }
    suspend fun showNavigation(destination: NavigationDestination, visible: Boolean) = updateNavigation { it.show(destination, visible) }
    suspend fun setNavigationCompact(compact: Boolean) = updateNavigation { it.copy(compact = compact) }
    suspend fun resetNavigation() = updateNavigation { NavigationPreferences() }

    fun observeThemeMode(): Flow<ArchiveThemeMode> = dataStore.data.map { preferences ->
        preferences[themeModeKey]?.let { stored ->
            runCatching { ArchiveThemeMode.valueOf(stored) }.getOrNull()
        } ?: ArchiveThemeMode.DARK
    }

    suspend fun setThemeMode(mode: ArchiveThemeMode) {
        dataStore.edit {
            it[themeModeKey] = mode.name
            it[themeUpdatedAtKey] = appearanceStamp()
        }
    }

    fun observePalette(): Flow<ArchivePalette> = dataStore.data.map { preferences ->
        preferences[paletteKey]?.let { stored ->
            runCatching { ArchivePalette.valueOf(stored) }.getOrNull()
        } ?: ArchivePalette.BRAND
    }

    suspend fun setPalette(palette: ArchivePalette) {
        dataStore.edit {
            it[paletteKey] = palette.name
            it[themeUpdatedAtKey] = appearanceStamp()
        }
    }

    /** Secret palettes earned on this device (Spider-Noir, The Construct). Only gated palettes
     *  are tracked; everything else is always available. */
    fun observeUnlockedPalettes(): Flow<Set<ArchivePalette>> = dataStore.data.map { preferences ->
        preferences[unlockedPalettesKey].orEmpty()
            .mapNotNull { stored -> runCatching { ArchivePalette.valueOf(stored) }.getOrNull() }
            .filter { it.isSecret }
            .toSet()
    }

    suspend fun unlockPalette(palette: ArchivePalette) {
        if (!palette.isSecret) return
        dataStore.edit { it[unlockedPalettesKey] = it[unlockedPalettesKey].orEmpty() + palette.name }
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

    /** Whether the bottom navigation bar blurs the content scrolling beneath it, rather than
     *  standing as a solid bar. On by default; Android-only, like the rest of these prefs. */
    fun observeGlassBlur(): Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[glassBlurKey] ?: true
    }

    suspend fun setGlassBlur(enabled: Boolean) {
        dataStore.edit { it[glassBlurKey] = enabled }
    }

    /** AMOLED "pure black": a true-black background and surface on dark palettes (light ones
     *  ignore it). Off by default; mirrors the web app's Pure black toggle. */
    fun observePureBlack(): Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[pureBlackKey] ?: false
    }

    suspend fun setPureBlack(enabled: Boolean) {
        dataStore.edit {
            it[pureBlackKey] = enabled
            it[themeUpdatedAtKey] = appearanceStamp()
        }
    }

    /** Hide the still, name and synopsis of episodes not yet watched. Off by default; a device
     *  preference like the web app's, and not part of the synced appearance. */
    fun observeSpoilerProtection(): Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[spoilerProtectionKey] ?: false
    }

    suspend fun setSpoilerProtection(enabled: Boolean) {
        dataStore.edit { it[spoilerProtectionKey] = enabled }
    }

    /** Opt-in, per device: mirror the appearance to the signed-in account. Off by default. */
    fun observeThemeSyncEnabled(): Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[themeSyncEnabledKey] ?: false
    }

    suspend fun setThemeSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[themeSyncEnabledKey] = enabled }
    }

    /** Moves only on a user-made appearance change (the setters above) or [applySyncedAppearance]. */
    fun observeThemeUpdatedAt(): Flow<String?> = dataStore.data.map { it[themeUpdatedAtKey] }

    /** One consistent read of everything theme sync compares and encodes. */
    suspend fun appearanceSnapshot(): AppearanceSnapshot {
        val prefs = dataStore.data.first()
        return AppearanceSnapshot(
            appearance = SyncedAppearance(
                palette = prefs[paletteKey]?.let { runCatching { ArchivePalette.valueOf(it) }.getOrNull() } ?: ArchivePalette.BRAND,
                mode = prefs[themeModeKey]?.let { runCatching { ArchiveThemeMode.valueOf(it) }.getOrNull() } ?: ArchiveThemeMode.DARK,
                pureBlack = prefs[pureBlackKey] ?: false,
            ),
            updatedAt = prefs[themeUpdatedAtKey],
            unlocked = prefs[unlockedPalettesKey].orEmpty()
                .mapNotNull { runCatching { ArchivePalette.valueOf(it) }.getOrNull() }.filter { it.isSecret }.toSet(),
            syncEnabled = prefs[themeSyncEnabledKey] ?: false,
        )
    }

    /** Applies a synced copy, adopting its stamp so it isn't pushed straight back. */
    suspend fun applySyncedAppearance(appearance: SyncedAppearance, updatedAt: String) {
        dataStore.edit {
            it[paletteKey] = appearance.palette.name
            it[themeModeKey] = appearance.mode.name
            it[pureBlackKey] = appearance.pureBlack
            it[themeUpdatedAtKey] = updatedAt
        }
    }

    /** Stamps a never-changed appearance so it can be compared and pushed. */
    suspend fun stampAppearanceIfUnset(): String {
        var stamp = ""
        dataStore.edit { prefs -> stamp = prefs[themeUpdatedAtKey] ?: appearanceStamp().also { prefs[themeUpdatedAtKey] = it } }
        return stamp
    }

    // Millisecond precision, matching the web client's Date#toISOString.
    private fun appearanceStamp(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

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

data class AppearanceSnapshot(
    val appearance: SyncedAppearance,
    val updatedAt: String?,
    val unlocked: Set<ArchivePalette>,
    val syncEnabled: Boolean,
)
