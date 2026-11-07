package work.kumarfamilynet.cinemarchive.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.model.PlanPeriod
import work.kumarfamilynet.cinemarchive.core.model.PlanState
import work.kumarfamilynet.cinemarchive.core.model.PlanStatus
import work.kumarfamilynet.cinemarchive.core.model.SubscriptionPlan
import work.kumarfamilynet.cinemarchive.core.model.SubscriptionPlanRules
import java.time.LocalDate

/**
 * The owner's theater subscription plan and the callback that changes it, handed to "At the
 * Movies" through [LocalMoviegoingPlan]. Left null (the default) for shared and friend views, so
 * the panel shows no plan row and no edit control there: the plan is owner-private
 * (docs/android-contracts/moviegoing-prefs.md).
 */
class MoviegoingPlanUi(val plan: SubscriptionPlan?, val onSetPlan: (SubscriptionPlan?) -> Unit)

val LocalMoviegoingPlan = compositionLocalOf<MoviegoingPlanUi?> { null }

/** The panel's plan line: outings used against the cap for the current period, or a prompt to
 *  set a plan. The state is spelled out in words as well as colour. */
@Composable
internal fun MoviegoingPlanRow(tripDates: List<LocalDate>) {
    val ui = LocalMoviegoingPlan.current ?: return
    var editing by rememberSaveable { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val status = remember(ui.plan, tripDates, today) { ui.plan?.let { SubscriptionPlanRules.status(it, tripDates, today) } }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Overline(if (status == null) "plan" else "plan · ${if (status.plan.period == PlanPeriod.WEEK) "this week" else "this month"}")
            TextButton(onClick = { editing = true }) { Text(if (status == null) "Set plan" else "Edit") }
        }
        if (status != null) {
            val color = planStateColor(status.state)
            Text(planSummary(status), style = MaterialTheme.typography.bodySmall, color = color)
            LinearProgressIndicator(
                progress = { (status.used.toFloat() / status.plan.cap).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = planSummary(status) },
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
    if (editing) {
        PlanDialog(
            current = ui.plan,
            onDismiss = { editing = false },
            onSave = { ui.onSetPlan(it); editing = false },
        )
    }
}

@Composable
private fun planStateColor(state: PlanState): Color = when (state) {
    PlanState.UNDER -> MaterialTheme.colorScheme.primary
    PlanState.NEAR, PlanState.AT_CAP -> MaterialTheme.colorScheme.tertiary
    PlanState.OVER -> MaterialTheme.colorScheme.error
}

/** "2 of 4 outings · 2 left", "3 of 4 outings · 1 left, nearly at your cap", "4 of 4 outings ·
 *  cap reached" or "5 of 4 outings · 1 over your cap". */
internal fun planSummary(status: PlanStatus): String {
    val head = "${status.used} of ${status.plan.cap} ${if (status.plan.cap == 1) "outing" else "outings"}"
    return when (status.state) {
        PlanState.UNDER -> "$head · ${status.remaining} left"
        PlanState.NEAR -> "$head · ${status.remaining} left, nearly at your cap"
        PlanState.AT_CAP -> "$head · cap reached"
        PlanState.OVER -> "$head · ${-status.remaining} over your cap"
    }
}

@Composable
private fun PlanDialog(current: SubscriptionPlan?, onDismiss: () -> Unit, onSave: (SubscriptionPlan?) -> Unit) {
    var capText by rememberSaveable { mutableStateOf(current?.cap?.toString() ?: "") }
    var period by rememberSaveable { mutableStateOf(current?.period ?: PlanPeriod.WEEK) }
    val cap = SubscriptionPlan.parseCap(capText)
    val showError = capText.isNotBlank() && cap == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subscription plan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = capText,
                    onValueChange = { capText = it },
                    label = { Text("Outings allowed") },
                    singleLine = true,
                    isError = showError,
                    supportingText = { if (showError) Text("Enter a whole number from 1 to ${SubscriptionPlan.MAX_CAP}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanPeriod.entries.forEach { option ->
                        FilterChip(
                            selected = period == option,
                            onClick = { period = option },
                            label = { Text(if (option == PlanPeriod.WEEK) "Per week" else "Per month") },
                        )
                    }
                }
                Text(
                    "Counts every cinema trip, Monday to Sunday for a week or by calendar month. Only you can see this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = cap != null, onClick = { cap?.let { onSave(SubscriptionPlan(it, period)) } }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
