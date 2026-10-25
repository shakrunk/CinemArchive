package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.runtime.compositionLocalOf

/** The device's "Hide episode spoilers" preference, provided once at the app root so episode UI
 *  in any feature module can read it without threading it through every screen. */
val LocalSpoilerProtection = compositionLocalOf { false }
