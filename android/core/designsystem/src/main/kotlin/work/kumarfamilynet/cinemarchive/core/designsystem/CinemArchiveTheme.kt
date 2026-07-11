package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ArchiveThemeMode { DARK, LIGHT, NOIR, MATRIX }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE9B266),
    onPrimary = Color(0xFF442B00),
    secondary = Color(0xFFAEC6FF),
    tertiary = Color(0xFFE8A4A4),
    background = Color(0xFF12100D),
    surface = Color(0xFF1B1814),
    surfaceVariant = Color(0xFF302B24),
    onSurface = Color(0xFFF2E9DA),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF805900),
    secondary = Color(0xFF315C9D),
    background = Color(0xFFFFF8F0),
    surface = Color(0xFFFFF8F0),
    onSurface = Color(0xFF211B13),
)

private val NoirScheme = DarkScheme.copy(
    primary = Color(0xFFDDD7CD),
    secondary = Color(0xFF9AABC2),
    background = Color(0xFF0D0D0E),
    surface = Color(0xFF171719),
)

private val MatrixScheme = DarkScheme.copy(
    primary = Color(0xFF9AF5B0),
    secondary = Color(0xFF77D8FF),
    background = Color(0xFF07110A),
    surface = Color(0xFF0D1910),
)

@Composable
fun CinemArchiveTheme(
    mode: ArchiveThemeMode = ArchiveThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val colors: ColorScheme = when (mode) {
        ArchiveThemeMode.DARK -> DarkScheme
        ArchiveThemeMode.LIGHT -> LightScheme
        ArchiveThemeMode.NOIR -> NoirScheme
        ArchiveThemeMode.MATRIX -> MatrixScheme
    }

    MaterialTheme(colorScheme = colors, content = content)
}
