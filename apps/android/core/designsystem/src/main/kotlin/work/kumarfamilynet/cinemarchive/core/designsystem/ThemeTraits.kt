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
 * @param film The Discover film strip's stock, frame and caption-ink colors. The strip stays dark
 *   on every palette, like poster art, but takes each palette's own tone (Noir's grey, Matrix's
 *   green-black, a Catppuccin flavor's slate) rather than the brand's warm brown. Mirrors src/index.css's `--film-*` tokens.
 */
@Immutable
data class ThemeTraits(val monochrome: Boolean = false, val film: FilmColors = FilmColors.Brand)

/** [stock] is the sprocket rail and frame dividers, [frame] each frame's fill around the art,
 *  [ink] the caption text. */
@Immutable
data class FilmColors(val stock: Color, val frame: Color, val ink: Color) {
    companion object {
        val Brand = FilmColors(stock = Color(0xFF262420), frame = Color(0xFF0A0A0A), ink = Color(0xFFF3EAD9))
        val Noir = FilmColors(stock = Color(0xFF242426), frame = Color(0xFF0A0A0A), ink = Color(0xFFEDECEE))
        val Matrix = FilmColors(stock = Color(0xFF122413), frame = Color(0xFF050A06), ink = Color(0xFFC8F5D2))
        // Each flavor's surface0 for the stock, its crust darkened for the frame, its text for ink.
        // Latte uses Mocha's, so the strip stays dark stock on the light page.
        val CatppuccinFrappe = FilmColors(stock = Color(0xFF414559), frame = Color(0xFF15171F), ink = Color(0xFFC6D0F5))
        val CatppuccinMacchiato = FilmColors(stock = Color(0xFF363A4F), frame = Color(0xFF0E0F17), ink = Color(0xFFCAD3F5))
        val CatppuccinMocha = FilmColors(stock = Color(0xFF313244), frame = Color(0xFF0A0A10), ink = Color(0xFFCDD6F4))
    }
}

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
