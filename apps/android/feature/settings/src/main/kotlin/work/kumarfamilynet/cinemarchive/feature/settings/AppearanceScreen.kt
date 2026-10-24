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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.GroupedSeamGap
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.cinemArchiveTypography
import work.kumarfamilynet.cinemarchive.core.designsystem.groupedItemShape
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontFamily
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontScale
import work.kumarfamilynet.cinemarchive.core.model.ArchivePalette
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode
import work.kumarfamilynet.cinemarchive.core.model.discoveredSecretPalettes
import work.kumarfamilynet.cinemarchive.core.model.isPaletteAvailable
import work.kumarfamilynet.cinemarchive.core.model.isSecret
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository

private data class PaletteSwatch(
    val palette: ArchivePalette,
    val caption: String,
    val c1: Color,
    val c2: Color,
    val c3: Color,
    /** Shown instead of [caption] while a secret palette is discovered but not yet earned. */
    val lockedCaption: String? = null,
)

private val PALETTE_SWATCHES = listOf(
    PaletteSwatch(ArchivePalette.BRAND, "The cinematic amber default", Color(0xFFE9B266), Color(0xFF8FB6CB), Color(0xFFD76A49)),
    PaletteSwatch(ArchivePalette.MATERIAL_YOU, "Dynamic color from your device", Color(0xFFBCC2FF), Color(0xFFC6C2DB), Color(0xFFE6B8D0)),
    PaletteSwatch(
        ArchivePalette.NOIR, "Unlocked · watched Spider-Man: Noir in B&W", Color(0xFFC8C8CD), Color(0xFFA0AAB4), Color(0xFFC85A41),
        lockedCaption = "Unlock it: watch every episode of Spider-Noir in black & white.",
    ),
    PaletteSwatch(
        ArchivePalette.MATRIX, "Unlocked · took the red pill in The Matrix", Color(0xFF4ADE80), Color(0xFF5AD2D2), Color(0xFFDC4646),
        lockedCaption = "Unlock it: take the red pill after logging a viewing of The Matrix.",
    ),
    // Stands for all four Catppuccin flavors: one card, with the flavor picked inside it once
    // selected, so they don't crowd the list. Mocha's mauve, blue and red are the swatch.
    PaletteSwatch(ArchivePalette.CATPPUCCIN_MOCHA, "Soothing pastels in four flavors", Color(0xFFCBA6F7), Color(0xFF89B4FA), Color(0xFFF38BA8)),
)

private val CATPPUCCIN_FLAVORS = listOf(
    ChoiceOption(ArchivePalette.CATPPUCCIN_LATTE, "Latte"),
    ChoiceOption(ArchivePalette.CATPPUCCIN_FRAPPE, "Frappé"),
    ChoiceOption(ArchivePalette.CATPPUCCIN_MACCHIATO, "Macchiato"),
    ChoiceOption(ArchivePalette.CATPPUCCIN_MOCHA, "Mocha"),
)

private fun ArchivePalette.isCatppuccin(): Boolean = CATPPUCCIN_FLAVORS.any { it.value == this }

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
fun AppearanceRoute(
    preferencesRepository: PreferencesRepository,
    onBack: () -> Unit,
    // False in the wide/split settings layout, where this screen sits permanently in the
    // trailing pane rather than having been navigated to — a back arrow there would point at
    // nothing to go back to.
    showBack: Boolean = true,
    // TMDB ids of everything in the library: a secret palette's card appears (locked) only once
    // its title has been added in some form.
    libraryTmdbIds: Flow<Set<Int>> = emptyFlow(),
) {
    val themeMode by preferencesRepository.observeThemeMode().collectAsStateWithLifecycle(initialValue = ArchiveThemeMode.DARK)
    val palette by preferencesRepository.observePalette().collectAsStateWithLifecycle(initialValue = ArchivePalette.BRAND)
    val unlockedPalettes by preferencesRepository.observeUnlockedPalettes().collectAsStateWithLifecycle(initialValue = emptySet())
    val discoveredPalettes by remember(libraryTmdbIds) { libraryTmdbIds.map(::discoveredSecretPalettes) }
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val fontFamily by preferencesRepository.observeFontFamily().collectAsStateWithLifecycle(initialValue = ArchiveFontFamily.DEFAULT)
    val fontScale by preferencesRepository.observeFontScale().collectAsStateWithLifecycle(initialValue = ArchiveFontScale.DEFAULT)
    val glassBlur by preferencesRepository.observeGlassBlur().collectAsStateWithLifecycle(initialValue = true)
    val pureBlack by preferencesRepository.observePureBlack().collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()

    AppearanceScreen(
        themeMode = themeMode,
        palette = palette,
        unlockedPalettes = unlockedPalettes,
        discoveredPalettes = discoveredPalettes,
        fontFamily = fontFamily,
        fontScale = fontScale,
        glassBlur = glassBlur,
        pureBlack = pureBlack,
        onSetThemeMode = { mode -> scope.launch { preferencesRepository.setThemeMode(mode) } },
        onSetPalette = { p -> scope.launch { preferencesRepository.setPalette(p) } },
        onSetFontFamily = { f -> scope.launch { preferencesRepository.setFontFamily(f) } },
        onSetFontScale = { s -> scope.launch { preferencesRepository.setFontScale(s) } },
        onSetGlassBlur = { enabled -> scope.launch { preferencesRepository.setGlassBlur(enabled) } },
        onSetPureBlack = { enabled -> scope.launch { preferencesRepository.setPureBlack(enabled) } },
        onBack = onBack,
        showBack = showBack,
    )
}

@Composable
private fun AppearanceScreen(
    themeMode: ArchiveThemeMode,
    palette: ArchivePalette,
    unlockedPalettes: Set<ArchivePalette>,
    discoveredPalettes: Set<ArchivePalette>,
    fontFamily: ArchiveFontFamily,
    fontScale: ArchiveFontScale,
    glassBlur: Boolean,
    pureBlack: Boolean,
    onSetThemeMode: (ArchiveThemeMode) -> Unit,
    onSetPalette: (ArchivePalette) -> Unit,
    onSetFontFamily: (ArchiveFontFamily) -> Unit,
    onSetFontScale: (ArchiveFontScale) -> Unit,
    onSetGlassBlur: (Boolean) -> Unit,
    onSetPureBlack: (Boolean) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                "Appearance",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = if (showBack) 4.dp else 0.dp),
            )
        }

        // weight(1f): a size-less LazyColumn wrap-content-sizes to its rows, leaving the rest of
        // the screen a plain Column that doesn't own touch — a drag there fell through to
        // whatever's underneath this overlay instead of being absorbed as a no-op scroll.
        LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp), modifier = Modifier.weight(1f)) {
            item {
                ReadingWidthColumn {
                    Text(
                        "THEME",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    SegmentedGroup(
                        options = listOf(
                            ChoiceOption(ArchiveThemeMode.SYSTEM, "System", icon = Icons.Filled.BrightnessAuto),
                            ChoiceOption(ArchiveThemeMode.LIGHT, "Light", icon = Icons.Filled.LightMode),
                            ChoiceOption(ArchiveThemeMode.DARK, "Dark", icon = Icons.Filled.DarkMode),
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
            }
            // One grouped container rather than a flat run of separate cards, so the
            // palettes read as a single set of mutually exclusive options (#141).
            // Secret palettes stay off the list until their title is in the library (or they are
            // already earned/active), so an unfound easter egg isn't spoiled by a locked card.
            val visibleSwatches = PALETTE_SWATCHES.filter { swatch ->
                !swatch.palette.isSecret ||
                    swatch.palette in unlockedPalettes ||
                    swatch.palette in discoveredPalettes ||
                    swatch.palette == palette
            }
            itemsIndexed(visibleSwatches) { index, swatch ->
                val catppuccin = swatch.palette.isCatppuccin()
                val selected = if (catppuccin) palette.isCatppuccin() else swatch.palette == palette
                val locked = !isPaletteAvailable(swatch.palette, unlockedPalettes) && !selected
                ReadingWidthColumn {
                    PaletteCard(
                        swatch = swatch,
                        label = if (catppuccin) "Catppuccin" else swatch.palette.label(),
                        selected = selected,
                        locked = locked,
                        shape = groupedItemShape(
                            isFirst = index == 0,
                            isLast = index == visibleSwatches.lastIndex,
                        ),
                        // Re-tapping the selected Catppuccin card keeps its current flavor.
                        onClick = { if (!selected) onSetPalette(swatch.palette) },
                        flavorPicker = if (catppuccin && selected) {
                            {
                                SegmentedGroup(
                                    options = CATPPUCCIN_FLAVORS,
                                    selected = palette,
                                    onSelect = onSetPalette,
                                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            item {
                ReadingWidthColumn {
                    SettingSwitchCard(
                        title = "Pure black",
                        caption = "True-black background for OLED screens, on dark palettes",
                        enabled = pureBlack,
                        onToggle = onSetPureBlack,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    SettingSwitchCard(
                        title = "Blur behind navigation",
                        caption = "The navigation bar softly shows what scrolls beneath it",
                        enabled = glassBlur,
                        onToggle = onSetGlassBlur,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            item {
                ReadingWidthColumn {
                    // Divider, not just another eyebrow label, so "Theme"+"Color Palette" reads as
                    // one related cluster and "Text" as a clearly separate one (#152) — the
                    // labelSmall eyebrows alone didn't give the two groups enough visual weight.
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(top = 18.dp, bottom = 18.dp),
                    )
                    Text(
                        "TEXT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
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
}

/**
 * Font family + size are staged locally and only committed via [onApply] — lets the reader
 * try a few slider positions against the [TextPreview] before touching the real setting.
 * Pending state is keyed off the persisted values so it resets to match whenever they change
 * out from under this composable (e.g. navigating back in and out of Appearance).
 *
 * Wrapped in its own container so it carries the same weight as the palette group above it —
 * previously these controls sat loose on the page while every other choice on the screen was
 * boxed, which read as leftover content rather than a third setting.
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

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Font",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            // Each option's label is set in the face that option would actually apply, so the
            // choice is shown rather than named — "Dyslexia-friendly" written in the app's
            // current font tells you nothing about what you'd be switching to.
            SegmentedGroup(
                options = ArchiveFontFamily.entries.map { family ->
                    ChoiceOption(
                        value = family,
                        label = family.label(),
                        labelFontFamily = cinemArchiveTypography(family).labelSmall.fontFamily,
                    )
                },
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
        // A tone above its parent container, not level with it — nested at the same
        // surfaceContainer the enclosing card now uses, the preview would vanish into it.
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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

/**
 * Whether the bottom navigation bar is glass, blurring the posters that scroll on beneath it, or
 * the solid bar it used to be. The whole row toggles, not just the switch, as a settings row does.
 */
@Composable
private fun SettingSwitchCard(
    title: String,
    caption: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        checked = enabled,
        onCheckedChange = onToggle,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp, 14.dp),
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Null handler: the Surface owns the toggle, so the switch only shows its state and
            // a screen reader hears one control rather than a row and a switch.
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

@Composable
private fun PaletteCard(
    swatch: PaletteSwatch,
    label: String,
    selected: Boolean,
    shape: Shape,
    onClick: () -> Unit,
    locked: Boolean = false,
    flavorPicker: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        enabled = !locked,
        shape = shape,
        // The selected row lifts to a higher container tone as well as taking the outline —
        // inside a grouped container a border alone reads as a seam, not a selection.
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().padding(bottom = GroupedSeamGap).alpha(if (locked) 0.55f else 1f),
    ) {
        Column {
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
                        Text(
                            if (locked) swatch.lockedCaption ?: swatch.caption else swatch.caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (locked) {
                    Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            flavorPicker?.invoke()
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
