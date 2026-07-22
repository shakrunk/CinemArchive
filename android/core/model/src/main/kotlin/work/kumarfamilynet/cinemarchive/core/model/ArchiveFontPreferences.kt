package work.kumarfamilynet.cinemarchive.core.model

/** Body/UI typeface. [DEFAULT] is the brand pairing (Fraunces + Hanken Grotesk); [DYSLEXIA_FRIENDLY]
 *  swaps every text role to Lexend, whose research-backed letterforms are commonly recommended
 *  for dyslexic and low-vision readers. */
enum class ArchiveFontFamily {
    DEFAULT,
    DYSLEXIA_FRIENDLY,
}

/** Global text-size multiplier, applied on top of the device's own font scale via
 *  `LocalDensity` so it reaches every Composable's text — not just the named styles in
 *  `CinemArchiveTypography`. */
enum class ArchiveFontScale(val multiplier: Float) {
    SMALL(0.85f),
    DEFAULT(1f),
    LARGE(1.15f),
    EXTRA_LARGE(1.3f),
}
