package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.cinemArchiveTypography
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontFamily
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontScale
import work.kumarfamilynet.cinemarchive.core.model.ArchivePalette
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository

private data class PaletteSwatch(val palette: ArchivePalette, val caption: String, val c1: Color, val c2: Color, val c3: Color)

private val PALETTE_SWATCHES = listOf(
    PaletteSwatch(ArchivePalette.BRAND, "The cinematic amber default", Color(0xFFE9B266), Color(0xFF8FB6CB), Color(0xFFD76A49)),
    PaletteSwatch(ArchivePalette.MATERIAL_YOU, "Dynamic color from your device", Color(0xFFBCC2FF), Color(0xFFC6C2DB), Color(0xFFE6B8D0)),
    PaletteSwatch(ArchivePalette.NOIR, "Unlocked · watched Spider-Man: Noir in B&W", Color(0xFFC8C8CD), Color(0xFFA0AAB4), Color(0xFFC85A41)),
    PaletteSwatch(ArchivePalette.MATRIX, "Unlocked · took the red pill in The Matrix", Color(0xFF4ADE80), Color(0xFF5AD2D2), Color(0xFFDC4646)),
)

internal fun ArchiveFontFamily.label(): String = when (this) {
    ArchiveFontFamily.DEFAULT -> "Default"
    ArchiveFontFamily.DYSLEXIA_FRIENDLY -> "Dyslexia-friendly"
}

internal fun ArchiveFontScale.label(): String = when (this) {
    ArchiveFontScale.SMALL -> "Small"
    ArchiveFontScale.DEFAULT -> "Default"
    ArchiveFontScale.LARGE -> "Large"
    ArchiveFontScale.EXTRA_LARGE -> "Extra Large"
}

@Composable
fun AppearanceRoute(preferencesRepository: PreferencesRepository, onBack: () -> Unit) {
    val themeMode by preferencesRepository.observeThemeMode().collectAsStateWithLifecycle(initialValue = ArchiveThemeMode.DARK)
    val palette by preferencesRepository.observePalette().collectAsStateWithLifecycle(initialValue = ArchivePalette.BRAND)
    val fontFamily by preferencesRepository.observeFontFamily().collectAsStateWithLifecycle(initialValue = ArchiveFontFamily.DEFAULT)
    val fontScale by preferencesRepository.observeFontScale().collectAsStateWithLifecycle(initialValue = ArchiveFontScale.DEFAULT)
    val scope = rememberCoroutineScope()

    AppearanceScreen(
        themeMode = themeMode,
        palette = palette,
        fontFamily = fontFamily,
        fontScale = fontScale,
        onSetThemeMode = { mode -> scope.launch { preferencesRepository.setThemeMode(mode) } },
        onSetPalette = { p -> scope.launch { preferencesRepository.setPalette(p) } },
        onSetFontFamily = { f -> scope.launch { preferencesRepository.setFontFamily(f) } },
        onSetFontScale = { s -> scope.launch { preferencesRepository.setFontScale(s) } },
        onBack = onBack,
    )
}

@Composable
private fun AppearanceScreen(
    themeMode: ArchiveThemeMode,
    palette: ArchivePalette,
    fontFamily: ArchiveFontFamily,
    fontScale: ArchiveFontScale,
    onSetThemeMode: (ArchiveThemeMode) -> Unit,
    onSetPalette: (ArchivePalette) -> Unit,
    onSetFontFamily: (ArchiveFontFamily) -> Unit,
    onSetFontScale: (ArchiveFontScale) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Appearance", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }

        LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp)) {
            item {
                Text(
                    "THEME",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                SegmentedGroup(
                    options = listOf(
                        ChoiceOption(ArchiveThemeMode.SYSTEM, "System"),
                        ChoiceOption(ArchiveThemeMode.LIGHT, "Light"),
                        ChoiceOption(ArchiveThemeMode.DARK, "Dark"),
                    ),
                    selected = themeMode,
                    onSelect = onSetThemeMode,
                    modifier = Modifier.padding(bottom = 24.dp),
                )

                Text(
                    "COLOR PALETTE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            items(PALETTE_SWATCHES) { swatch ->
                PaletteCard(
                    swatch = swatch,
                    label = swatch.palette.label(),
                    selected = swatch.palette == palette,
                    onClick = { onSetPalette(swatch.palette) },
                )
            }
            item {
                Text(
                    "TEXT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
                )
                TextSettingsCard(
                    fontFamily = fontFamily,
                    fontScale = fontScale,
                    onApply = { family, scale ->
                        onSetFontFamily(family)
                        onSetFontScale(scale)
                    },
                )
            }
        }
    }
}

/**
 * Font family + size are staged locally and only committed via [onApply] — lets the reader
 * try a few slider positions against the [TextPreview] before touching the real setting.
 * Pending state is keyed off the persisted values so it resets to match whenever they change
 * out from under this composable (e.g. navigating back in and out of Appearance).
 */
@Composable
private fun TextSettingsCard(
    fontFamily: ArchiveFontFamily,
    fontScale: ArchiveFontScale,
    onApply: (ArchiveFontFamily, ArchiveFontScale) -> Unit,
) {
    var pendingFontFamily by remember(fontFamily) { mutableStateOf(fontFamily) }
    var pendingFontScale by remember(fontScale) { mutableStateOf(fontScale) }
    val fontScaleSteps = ArchiveFontScale.entries

    Text(
        "Font",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    SegmentedGroup(
        options = listOf(
            ChoiceOption(ArchiveFontFamily.DEFAULT, ArchiveFontFamily.DEFAULT.label()),
            ChoiceOption(ArchiveFontFamily.DYSLEXIA_FRIENDLY, ArchiveFontFamily.DYSLEXIA_FRIENDLY.label()),
        ),
        selected = pendingFontFamily,
        onSelect = { pendingFontFamily = it },
        modifier = Modifier.padding(bottom = 20.dp),
    )

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Text(
            "Size",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            pendingFontScale.label(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Slider(
        value = fontScaleSteps.indexOf(pendingFontScale).toFloat(),
        onValueChange = { pendingFontScale = fontScaleSteps[it.toInt().coerceIn(fontScaleSteps.indices)] },
        valueRange = 0f..(fontScaleSteps.size - 1).toFloat(),
        steps = fontScaleSteps.size - 2,
        colors = SliderDefaults.colors(
            activeTrackColor = MaterialTheme.colorScheme.primary,
            thumbColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.padding(bottom = 16.dp),
    )

    TextPreview(
        fontFamily = pendingFontFamily,
        fontScale = pendingFontScale,
        modifier = Modifier.padding(bottom = 16.dp),
    )

    val hasPendingChanges = pendingFontFamily != fontFamily || pendingFontScale != fontScale
    Button(
        onClick = { onApply(pendingFontFamily, pendingFontScale) },
        enabled = hasPendingChanges,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (hasPendingChanges) "Apply" else "Applied")
    }
}

/** Live sample rendered at the staged (not-yet-applied) font family + scale, so the reader can
 *  judge readability before committing — mirrors how [work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme]
 *  applies [ArchiveFontScale] globally via `LocalDensity`, scoped to just this card. */
@Composable
private fun TextPreview(fontFamily: ArchiveFontFamily, fontScale: ArchiveFontScale, modifier: Modifier = Modifier) {
    val baseDensity = LocalDensity.current
    val previewDensity = remember(baseDensity, fontScale) {
        Density(density = baseDensity.density, fontScale = baseDensity.fontScale * fontScale.multiplier)
    }
    val previewTypography = cinemArchiveTypography(fontFamily)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        CompositionLocalProvider(LocalDensity provides previewDensity) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Casablanca", style = previewTypography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "\"Here's looking at you, kid.\" A preview of how titles and body text will look across the app.",
                    style = previewTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PaletteCard(swatch: PaletteSwatch, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(14.dp, 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(swatch.c1, offset = 0.dp)
                Swatch(swatch.c2, offset = (-8).dp)
                Swatch(swatch.c3, offset = (-16).dp)
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(swatch.caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, offset: Dp) {
    Surface(
        shape = CircleShape,
        color = color,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.background),
        modifier = Modifier.size(22.dp).offset(x = offset),
    ) {}
}
