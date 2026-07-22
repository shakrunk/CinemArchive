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
 * `:root` / `[data-theme]` blocks) so the four themes read the same on web and Android.
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

/** Resolves [ArchiveThemeMode.SYSTEM] against the device setting; [LIGHT]/[DARK] pin it.
 *  Ignored entirely by [ArchivePalette.NOIR]/[ArchivePalette.MATRIX] — see [CinemArchiveTheme]. */
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
    }

    // Stacks on top of the device's own font scale (rather than replacing it) so this setting
    // and the system accessibility text-size setting compound instead of one silently
    // overriding the other. Applied via LocalDensity, not just Typography's font sizes, so it
    // reaches every Composable's text — including ones that don't go through a named style.
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(density = baseDensity.density, fontScale = baseDensity.fontScale * fontScale.multiplier)

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = colors,
            typography = cinemArchiveTypography(fontFamily),
            shapes = CinemArchiveShapes,
            content = content,
        )
    }
}
