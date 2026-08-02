package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material's "medium" window-size-class threshold — switches phone-first chrome (bottom nav,
 *  single-column widget packing) to the wider unfolded/tablet layout. */
val MediumWindowBreakpoint: Dp = 600.dp

/** Cap for reading-width chrome (search pill, settings rows, detail body, list cards) on wide
 *  screens — sized to roughly one phone-width, not half of an unfolded foldable's display. */
val ContentReadingMaxWidth: Dp = 440.dp

/**
 * Centers [content] within a [ContentReadingMaxWidth] column inside whatever full-width slot
 * it's given — for use inside `LazyColumn` `item`/`items` blocks, where each item slot is
 * independently full-width and so can't just have its modifier swapped like a plain `Column`
 * can. No-ops (content simply fills the slot) below the cap, e.g. on phones.
 */
@Composable
fun ReadingWidthColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ContentReadingMaxWidth), content = content)
    }
}
