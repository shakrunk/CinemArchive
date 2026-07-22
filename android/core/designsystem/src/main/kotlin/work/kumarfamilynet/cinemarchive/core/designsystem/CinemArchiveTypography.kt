package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontFamily

/**
 * Mirrors src/index.css's `--display` (Fraunces), `--ui` (Hanken Grotesk), and `--mono`
 * (DM Mono) font stacks, resolved as downloadable Google Fonts via Play services so the
 * native app doesn't need to bundle font binaries. Requires
 * [R.array.com_google_android_gms_fonts_certs] (font_certs.xml).
 */
private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun downloadableFamily(name: String, weights: List<FontWeight>): FontFamily {
    val font = GoogleFont(name)
    return FontFamily(weights.map { weight -> Font(googleFont = font, fontProvider = GoogleFontsProvider, weight = weight) })
}

/** Serif marquee face — headings, titles. */
val FrauncesFamily: FontFamily = downloadableFamily(
    "Fraunces",
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold),
)

/** UI sans — body copy, labels, controls. */
val HankenGroteskFamily: FontFamily = downloadableFamily(
    "Hanken Grotesk",
    listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold),
)

/** Tabular mono — stats and numbers (applied ad hoc, not part of [Typography]'s roles). */
val DmMonoFamily: FontFamily = downloadableFamily("DM Mono", listOf(FontWeight.Normal, FontWeight.Medium))

/** Dyslexia-friendly accessibility face — see [ArchiveFontFamily.DYSLEXIA_FRIENDLY]. Replaces
 *  both [FrauncesFamily] and [HankenGroteskFamily] across every role so headings and body copy
 *  read consistently, rather than mixing an accessible sans with the branded display serif. */
val LexendFamily: FontFamily = downloadableFamily(
    "Lexend",
    listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold),
)

private val Base = Typography()

private val BrandTypography: Typography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.SemiBold),
    displayMedium = Base.displayMedium.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.SemiBold),
    displaySmall = Base.displaySmall.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.Medium),
    headlineLarge = Base.headlineLarge.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.Medium),
    titleLarge = Base.titleLarge.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.Medium),
    titleMedium = Base.titleMedium.copy(fontFamily = HankenGroteskFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontFamily = HankenGroteskFamily, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontFamily = HankenGroteskFamily),
    bodyMedium = Base.bodyMedium.copy(fontFamily = HankenGroteskFamily),
    bodySmall = Base.bodySmall.copy(fontFamily = HankenGroteskFamily),
    labelLarge = Base.labelLarge.copy(fontFamily = HankenGroteskFamily, fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontFamily = HankenGroteskFamily, fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontFamily = HankenGroteskFamily, fontWeight = FontWeight.Medium),
)

private val DyslexiaFriendlyTypography: Typography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = LexendFamily, fontWeight = FontWeight.SemiBold),
    displayMedium = Base.displayMedium.copy(fontFamily = LexendFamily, fontWeight = FontWeight.SemiBold),
    displaySmall = Base.displaySmall.copy(fontFamily = LexendFamily, fontWeight = FontWeight.Medium),
    headlineLarge = Base.headlineLarge.copy(fontFamily = LexendFamily, fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = LexendFamily, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = LexendFamily, fontWeight = FontWeight.Medium),
    titleLarge = Base.titleLarge.copy(fontFamily = LexendFamily, fontWeight = FontWeight.Medium),
    titleMedium = Base.titleMedium.copy(fontFamily = LexendFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontFamily = LexendFamily, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontFamily = LexendFamily),
    bodyMedium = Base.bodyMedium.copy(fontFamily = LexendFamily),
    bodySmall = Base.bodySmall.copy(fontFamily = LexendFamily),
    labelLarge = Base.labelLarge.copy(fontFamily = LexendFamily, fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontFamily = LexendFamily, fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontFamily = LexendFamily, fontWeight = FontWeight.Medium),
)

fun cinemArchiveTypography(fontFamily: ArchiveFontFamily): Typography = when (fontFamily) {
    ArchiveFontFamily.DEFAULT -> BrandTypography
    ArchiveFontFamily.DYSLEXIA_FRIENDLY -> DyslexiaFriendlyTypography
}

/** Matches src/index.css's `--radius: 0.6rem` (~9.6px), scaled up slightly for touch-sized
 *  Android components. */
val CinemArchiveShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
