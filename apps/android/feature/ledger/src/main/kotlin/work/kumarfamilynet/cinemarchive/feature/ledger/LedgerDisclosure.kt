package work.kumarfamilynet.cinemarchive.feature.ledger

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Progressive disclosure for the Ledger's widget cards.
 *
 * Every panel in this feature has the same anatomy — heading, a plain-language subtitle, an
 * insight sentence and/or a headline figure, a visual, then a run of detail rows. Before this,
 * all of it was crammed into a fixed 400dp card that scrolled internally, which put a scroll
 * container inside `LedgerScreen`'s own `LazyColumn`: two scroll axes stacked on one another,
 * and every widget showing about a third of what it had to say.
 *
 * The split here is "overview first, details on demand" (Shneiderman's visual
 * information-seeking mantra; Nielsen's progressive disclosure). Everything that answers *what
 * is this widget telling me* — heading, subtitle, insight, visual — is permanently visible and
 * never behind a tap. Only the enumerated rows underneath, which restate at row-level precision
 * what the visual already shows in shape, collapse. The card wraps its content in both states,
 * so a widget never scrolls internally and the board has exactly one scroller.
 *
 * The known failure mode of collapsed content is that users forget it exists, so the toggle is
 * never a bare chevron: it always counts what's hidden and names it ("Show 8 more decades").
 */
internal data class PanelDisclosure(
    val expanded: Boolean,
    /** Null renders the summary alone with no toggle — used by the edit-mode palette
     *  thumbnail, which is a non-interactive scaled-down render and has no room for detail. */
    val onToggle: (() -> Unit)?,
) {
    companion object {
        val Preview = PanelDisclosure(expanded = false, onToggle = null)
    }
}

/**
 * Fewer hidden rows than this and a disclosure costs more than it saves — the toggle is itself
 * a ~44dp tap target, so hiding two 20dp rows behind one is a net loss in height *and* adds an
 * interaction. Panels below the threshold just render everything.
 */
private const val MIN_HIDDEN_ROWS = 3

/**
 * The detail half of a panel: [content] renders only when expanded, with the labelled toggle
 * underneath it in both states so the control stays put rather than jumping to the bottom of a
 * newly-expanded block.
 */
@Composable
internal fun ColumnScope.PanelDetail(
    disclosure: PanelDisclosure,
    collapsedLabel: String,
    spacing: Dp = 3.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (disclosure.onToggle == null) return
    AnimatedVisibility(
        visible = disclosure.expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
    DisclosureToggle(disclosure, collapsedLabel)
}

/**
 * The common case: one list where the first [previewCount] rows stay visible and the tail
 * collapses. [noun] completes the toggle's label and should be plural and lowercase
 * ("decades", "months", "more titles" reads as "Show 8 more titles").
 *
 * Panels whose rows *are* the visualization (Critical Record's distribution bars) deliberately
 * don't call this — there's no summary left once you take the rows away.
 */
@Composable
internal fun <T> ColumnScope.DisclosedList(
    items: List<T>,
    disclosure: PanelDisclosure,
    noun: String,
    previewCount: Int = 0,
    spacing: Dp = 3.dp,
    row: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    val hidden = (items.size - previewCount).coerceAtLeast(0)
    val shownUpFront = if (hidden < MIN_HIDDEN_ROWS) items.size else previewCount
    if (shownUpFront > 0) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items.take(shownUpFront).forEach { row(it) }
        }
    }
    val rest = items.drop(shownUpFront)
    if (rest.isEmpty()) return
    PanelDetail(disclosure, "Show ${rest.size} more $noun", spacing) {
        rest.forEach { row(it) }
    }
}

/** The expander itself. Kept above Material's 44dp minimum target and given a real
 *  [stateDescription] so TalkBack announces collapsed/expanded rather than leaving the label
 *  to imply it. */
@Composable
private fun DisclosureToggle(disclosure: PanelDisclosure, collapsedLabel: String) {
    val onToggle = disclosure.onToggle ?: return
    val expanded = disclosure.expanded
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "ledger-disclosure-chevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                onClickLabel = if (expanded) "Hide the detail" else "Show the detail",
                role = Role.Button,
                onClick = onToggle,
            )
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (expanded) "Show less" else collapsedLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(18.dp)
                .graphicsLayer { rotationZ = chevronRotation },
        )
    }
}
