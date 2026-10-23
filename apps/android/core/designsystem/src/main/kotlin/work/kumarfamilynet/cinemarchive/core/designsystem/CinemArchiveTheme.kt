package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontFamily
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontScale
import work.kumarfamilynet.cinemarchive.core.model.ArchivePalette
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode

/**
 * Color values mirror src/index.css's `--*-rgb` custom properties 1:1 (see that file's
 * `:root` / `[data-theme]` blocks) so the themes read the same on web and Android.
 * `surfaceContainer*` map onto the web's `--ink`/`--ink-1`/`--ink-2`/`--ink-3` layer scale;
 * `outline`/`outlineVariant` mirror `--line-2`/`--line`.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE9B266),
    onPrimary = Color(0xFF25160A),
    primaryContainer = Color(0xFF4A3216),
    onPrimaryContainer = Color(0xFFF7CD86),
    secondary = Color(0xFF8FB6CB),
    onSecondary = Color(0xFF0B2430),
    tertiary = Color(0xFFE58E6F),
    onTertiary = Color(0xFF2E0F06),
    error = Color(0xFFD76A49),
    onError = Color(0xFF2E0906),
    background = Color(0xFF0B0907),
    onBackground = Color(0xFFF3EAD9),
    surface = Color(0xFF110D0B),
    onSurface = Color(0xFFF3EAD9),
    surfaceVariant = Color(0xFF1E1714),
    onSurfaceVariant = Color(0xFFB7A994),
    surfaceContainerLowest = Color(0xFF080605),
    surfaceContainerLow = Color(0xFF17120F),
    surfaceContainer = Color(0xFF1E1714),
    surfaceContainerHigh = Color(0xFF281F19),
    surfaceContainerHighest = Color(0xFF322820),
    outline = Color(0x29F5E6D2),
    outlineVariant = Color(0x16F5E6D2),
    inverseSurface = Color(0xFFF3EAD9),
    inverseOnSurface = Color(0xFF110D0B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF805900),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF5DEB0),
    onPrimaryContainer = Color(0xFF2E1E00),
    secondary = Color(0xFF315C9D),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFF8F0),
    onBackground = Color(0xFF211B13),
    surface = Color(0xFFFFF8F0),
    onSurface = Color(0xFF211B13),
    surfaceVariant = Color(0xFFF0E4D0),
    onSurfaceVariant = Color(0xFF5B5040),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF2E4),
    surfaceContainer = Color(0xFFF5EAD9),
    surfaceContainerHigh = Color(0xFFEFE2CC),
    surfaceContainerHighest = Color(0xFFE9DAC0),
    outline = Color(0x33211B13),
    outlineVariant = Color(0x1A211B13),
)

private val NoirScheme = DarkScheme.copy(
    primary = Color(0xFFDDD7CD),
    onPrimary = Color(0xFF1F1D1A),
    primaryContainer = Color(0xFF3A3833),
    onPrimaryContainer = Color(0xFFEDE9E1),
    secondary = Color(0xFF9AABC2),
    // Neutral containers rather than the material baseline's purples, which DarkScheme leaves in
    // place and Noir would otherwise inherit: the status badges and the nav's selection pill read
    // grey, as the web's noir badges do off its greyed --amber/--moon. Tertiary itself stays the
    // ember red Noir keeps as its one accent.
    secondaryContainer = Color(0xFF2E2E33),
    onSecondaryContainer = Color(0xFFDCDCE0),
    tertiaryContainer = Color(0xFF3A3A3F),
    onTertiaryContainer = Color(0xFFE6E6EA),
    background = Color(0xFF0D0D0E),
    onBackground = Color(0xFFEDECEE),
    surface = Color(0xFF171719),
    onSurface = Color(0xFFEDECEE),
    surfaceVariant = Color(0xFF201F22),
    onSurfaceVariant = Color(0xFFACA9AE),
    surfaceContainerLowest = Color(0xFF08080A),
    surfaceContainerLow = Color(0xFF17171A),
    surfaceContainer = Color(0xFF1D1D20),
    surfaceContainerHigh = Color(0xFF26262A),
    surfaceContainerHighest = Color(0xFF2F2F34),
    outline = Color(0x29DDD7CD),
    outlineVariant = Color(0x16DDD7CD),
)

private val MatrixScheme = DarkScheme.copy(
    primary = Color(0xFF9AF5B0),
    onPrimary = Color(0xFF04310F),
    primaryContainer = Color(0xFF184A28),
    onPrimaryContainer = Color(0xFFC3FBD0),
    secondary = Color(0xFF77D8FF),
    background = Color(0xFF07110A),
    onBackground = Color(0xFFE3F5E8),
    surface = Color(0xFF0D1910),
    onSurface = Color(0xFFE3F5E8),
    surfaceVariant = Color(0xFF15271A),
    onSurfaceVariant = Color(0xFF9AC2A6),
    surfaceContainerLowest = Color(0xFF040B06),
    surfaceContainerLow = Color(0xFF0D1911),
    surfaceContainer = Color(0xFF122117),
    surfaceContainerHigh = Color(0xFF182B1D),
    surfaceContainerHighest = Color(0xFF1E3524),
    outline = Color(0x299AF5B0),
    outlineVariant = Color(0x169AF5B0),
)

/**
 * Catppuccin flavors (catppuccin.com/palette), mirroring src/index.css's `catppuccin-*` blocks: each
 * flavor's mauve is the primary accent, blue the secondary, maroon the tertiary and red the error.
 * Built in full rather than as DarkScheme/LightScheme copies so no brand amber or material-baseline
 * purple leaks into a slot left unset.
 */
private val CatppuccinLatteScheme = lightColorScheme(
    primary = Color(0xFF8839EF),
    onPrimary = Color(0xFFEFF1F5),
    primaryContainer = Color(0xFFDCD0F4),
    onPrimaryContainer = Color(0xFF4B1F83),
    inversePrimary = Color(0xFFCBA6F7),
    secondary = Color(0xFF1E66F5),
    onSecondary = Color(0xFFEFF1F5),
    secondaryContainer = Color(0xFFC9D8F5),
    onSecondaryContainer = Color(0xFF113887),
    tertiary = Color(0xFFE64553),
    onTertiary = Color(0xFFEFF1F5),
    tertiaryContainer = Color(0xFFEDD2D8),
    onTertiaryContainer = Color(0xFF7F262E),
    error = Color(0xFFD20F39),
    onError = Color(0xFFEFF1F5),
    errorContainer = Color(0xFFEAC8D3),
    onErrorContainer = Color(0xFF74081F),
    background = Color(0xFFEFF1F5),
    onBackground = Color(0xFF4C4F69),
    surface = Color(0xFFEFF1F5),
    onSurface = Color(0xFF4C4F69),
    surfaceVariant = Color(0xFFE6E9EF),
    onSurfaceVariant = Color(0xFF6C6F85),
    surfaceTint = Color(0xFF8839EF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEBEDF2),
    surfaceContainer = Color(0xFFE6E9EF),
    surfaceContainerHigh = Color(0xFFDCE0E8),
    surfaceContainerHighest = Color(0xFFCCD0DA),
    surfaceDim = Color(0xFFDCE0E8),
    surfaceBright = Color(0xFFFFFFFF),
    outline = Color(0x334C4F69),
    outlineVariant = Color(0x1A4C4F69),
    inverseSurface = Color(0xFF4C4F69),
    inverseOnSurface = Color(0xFFEFF1F5),
)

private val CatppuccinFrappeScheme = darkColorScheme(
    primary = Color(0xFFCA9EE6),
    onPrimary = Color(0xFF232634),
    primaryContainer = Color(0xFF5B5273),
    onPrimaryContainer = Color(0xFFE2CAF1),
    inversePrimary = Color(0xFF8839EF),
    secondary = Color(0xFF8CAAEE),
    onSecondary = Color(0xFF232634),
    secondaryContainer = Color(0xFF4A5575),
    onSecondaryContainer = Color(0xFFC0D0F6),
    tertiary = Color(0xFFEA999C),
    onTertiary = Color(0xFF232634),
    tertiaryContainer = Color(0xFF64505E),
    onTertiaryContainer = Color(0xFFF3C7C9),
    error = Color(0xFFE78284),
    onError = Color(0xFF232634),
    errorContainer = Color(0xFF634A57),
    onErrorContainer = Color(0xFFF2BABB),
    background = Color(0xFF303446),
    onBackground = Color(0xFFC6D0F5),
    surface = Color(0xFF363A4C),
    onSurface = Color(0xFFC6D0F5),
    surfaceVariant = Color(0xFF414559),
    onSurfaceVariant = Color(0xFFA5ADCE),
    surfaceTint = Color(0xFFCA9EE6),
    surfaceContainerLowest = Color(0xFF232634),
    surfaceContainerLow = Color(0xFF3B3F53),
    surfaceContainer = Color(0xFF414559),
    surfaceContainerHigh = Color(0xFF494E63),
    surfaceContainerHighest = Color(0xFF51576D),
    surfaceDim = Color(0xFF292C3C),
    surfaceBright = Color(0xFF51576D),
    outline = Color(0x29C6D0F5),
    outlineVariant = Color(0x16C6D0F5),
    inverseSurface = Color(0xFFC6D0F5),
    inverseOnSurface = Color(0xFF303446),
)

private val CatppuccinMacchiatoScheme = darkColorScheme(
    primary = Color(0xFFC6A0F6),
    onPrimary = Color(0xFF181926),
    primaryContainer = Color(0xFF51496F),
    onPrimaryContainer = Color(0xFFE0CBFA),
    inversePrimary = Color(0xFF8839EF),
    secondary = Color(0xFF8AADF4),
    onSecondary = Color(0xFF181926),
    secondaryContainer = Color(0xFF414D6E),
    onSecondaryContainer = Color(0xFFBFD2F9),
    tertiary = Color(0xFFEE99A0),
    onTertiary = Color(0xFF181926),
    tertiaryContainer = Color(0xFF5D4757),
    onTertiaryContainer = Color(0xFFF6C7CB),
    error = Color(0xFFED8796),
    onError = Color(0xFF181926),
    errorContainer = Color(0xFF5C4254),
    onErrorContainer = Color(0xFFF5BDC5),
    background = Color(0xFF24273A),
    onBackground = Color(0xFFCAD3F5),
    surface = Color(0xFF2A2D41),
    onSurface = Color(0xFFCAD3F5),
    surfaceVariant = Color(0xFF363A4F),
    onSurfaceVariant = Color(0xFFA5ADCB),
    surfaceTint = Color(0xFFC6A0F6),
    surfaceContainerLowest = Color(0xFF181926),
    surfaceContainerLow = Color(0xFF303448),
    surfaceContainer = Color(0xFF363A4F),
    surfaceContainerHigh = Color(0xFF40445A),
    surfaceContainerHighest = Color(0xFF494D64),
    surfaceDim = Color(0xFF1E2030),
    surfaceBright = Color(0xFF494D64),
    outline = Color(0x29CAD3F5),
    outlineVariant = Color(0x16CAD3F5),
    inverseSurface = Color(0xFFCAD3F5),
    inverseOnSurface = Color(0xFF24273A),
)

private val CatppuccinMochaScheme = darkColorScheme(
    primary = Color(0xFFCBA6F7),
    onPrimary = Color(0xFF11111B),
    primaryContainer = Color(0xFF4E4466),
    onPrimaryContainer = Color(0xFFE2CEFB),
    inversePrimary = Color(0xFF8839EF),
    secondary = Color(0xFF89B4FA),
    onSecondary = Color(0xFF11111B),
    secondaryContainer = Color(0xFF3C4867),
    onSecondaryContainer = Color(0xFFBED6FC),
    tertiary = Color(0xFFEBA0AC),
    onTertiary = Color(0xFF11111B),
    tertiaryContainer = Color(0xFF574251),
    onTertiaryContainer = Color(0xFFF4CBD1),
    error = Color(0xFFF38BA8),
    onError = Color(0xFF11111B),
    errorContainer = Color(0xFF5A3D50),
    onErrorContainer = Color(0xFFF8BFCF),
    background = Color(0xFF1E1E2E),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF242535),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF313244),
    onSurfaceVariant = Color(0xFFA6ADC8),
    surfaceTint = Color(0xFFCBA6F7),
    surfaceContainerLowest = Color(0xFF11111B),
    surfaceContainerLow = Color(0xFF2B2B3D),
    surfaceContainer = Color(0xFF313244),
    surfaceContainerHigh = Color(0xFF3B3D4F),
    surfaceContainerHighest = Color(0xFF45475A),
    surfaceDim = Color(0xFF181825),
    surfaceBright = Color(0xFF45475A),
    outline = Color(0x29CDD6F4),
    outlineVariant = Color(0x16CDD6F4),
    inverseSurface = Color(0xFFCDD6F4),
    inverseOnSurface = Color(0xFF1E1E2E),
)

/** Resolves [ArchiveThemeMode.SYSTEM] against the device setting; [LIGHT]/[DARK] pin it.
 *  Only [ArchivePalette.BRAND]/[ArchivePalette.MATERIAL_YOU] use it — see [CinemArchiveTheme]. */
@Composable
private fun ArchiveThemeMode.resolveIsDark(): Boolean = when (this) {
    ArchiveThemeMode.SYSTEM -> isSystemInDarkTheme()
    ArchiveThemeMode.LIGHT -> false
    ArchiveThemeMode.DARK -> true
}

@Composable
fun CinemArchiveTheme(
    mode: ArchiveThemeMode = ArchiveThemeMode.DARK,
    palette: ArchivePalette = ArchivePalette.BRAND,
    fontFamily: ArchiveFontFamily = ArchiveFontFamily.DEFAULT,
    fontScale: ArchiveFontScale = ArchiveFontScale.DEFAULT,
    content: @Composable () -> Unit,
) {
    val isDark = mode.resolveIsDark()
    val context = LocalContext.current
    val colors: ColorScheme = when (palette) {
        ArchivePalette.BRAND -> if (isDark) DarkScheme else LightScheme
        ArchivePalette.MATERIAL_YOU ->
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        ArchivePalette.NOIR -> NoirScheme
        ArchivePalette.MATRIX -> MatrixScheme
        ArchivePalette.CATPPUCCIN_LATTE -> CatppuccinLatteScheme
        ArchivePalette.CATPPUCCIN_FRAPPE -> CatppuccinFrappeScheme
        ArchivePalette.CATPPUCCIN_MACCHIATO -> CatppuccinMacchiatoScheme
        ArchivePalette.CATPPUCCIN_MOCHA -> CatppuccinMochaScheme
    }

    // Stacks on top of the device's own font scale (rather than replacing it) so this setting
    // and the system accessibility text-size setting compound instead of one silently
    // overriding the other. Applied via LocalDensity, not just Typography's font sizes, so it
    // reaches every Composable's text — including ones that don't go through a named style.
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(density = baseDensity.density, fontScale = baseDensity.fontScale * fontScale.multiplier)

    val traits = ThemeTraits(
        monochrome = palette == ArchivePalette.NOIR,
        film = when (palette) {
            ArchivePalette.NOIR -> FilmColors.Noir
            ArchivePalette.MATRIX -> FilmColors.Matrix
            ArchivePalette.CATPPUCCIN_LATTE, ArchivePalette.CATPPUCCIN_MOCHA -> FilmColors.CatppuccinMocha
            ArchivePalette.CATPPUCCIN_FRAPPE -> FilmColors.CatppuccinFrappe
            ArchivePalette.CATPPUCCIN_MACCHIATO -> FilmColors.CatppuccinMacchiato
            else -> FilmColors.Brand
        },
    )

    CompositionLocalProvider(LocalDensity provides scaledDensity, LocalThemeTraits provides traits) {
        MaterialTheme(
            colorScheme = colors,
            motionScheme = ArchiveMotion,
            typography = cinemArchiveTypography(fontFamily),
            shapes = CinemArchiveShapes,
            content = content,
        )
    }
}
