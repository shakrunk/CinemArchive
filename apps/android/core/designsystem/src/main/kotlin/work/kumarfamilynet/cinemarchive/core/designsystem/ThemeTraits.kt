package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * How the selected palette dresses components beyond its color scheme — the overrides a
 * [androidx.compose.material3.ColorScheme] has no slot for. Components read it through
 * [ThemeTraitsDefaults] rather than branching on the palette themselves, so a new override lands in
 * one place.
 *
 * @param monochrome Whether decorative accents the scheme doesn't color — the generated
 *   placeholder tints standing in for missing posters — flatten to grey. Spider-Noir sets it, so its
 *   black-and-white chrome isn't broken up by a run of colored placeholders.
 */
@Immutable
data class ThemeTraits(val monochrome: Boolean = false)

val LocalThemeTraits = staticCompositionLocalOf { ThemeTraits() }

object ThemeTraitsDefaults {
    /**
     * [base], a decorative tint such as [tintForKey]'s, or its grey of equal lightness where the
     * palette is [ThemeTraits.monochrome]. Equal lightness keeps a placeholder grid's rhythm of
     * lighter and darker tiles, and the text laid over each its contrast.
     */
    @Composable
    @ReadOnlyComposable
    fun decorativeTint(base: Color): Color {
        if (!LocalThemeTraits.current.monochrome) return base
        // Rec. 709 luma, the weighting noirColorMatrix's black-and-white filter uses
        val luma = 0.2126f * base.red + 0.7152f * base.green + 0.0722f * base.blue
        return Color(red = luma, green = luma, blue = luma, alpha = base.alpha)
    }
}
