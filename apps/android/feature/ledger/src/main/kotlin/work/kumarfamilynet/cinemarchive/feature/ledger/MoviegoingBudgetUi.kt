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
import work.kumarfamilynet.cinemarchive.core.model.BudgetPeriod
import work.kumarfamilynet.cinemarchive.core.model.BudgetState
import work.kumarfamilynet.cinemarchive.core.model.BudgetStatus
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingBudget
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingBudgetRules
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingPricedTrip
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The owner's moviegoing budget and the callback that changes it, handed to "At the Movies"
 * through [LocalMoviegoingBudget]. Left null (the default) for shared and friend views, so
 * the panel shows no budget row and no edit control there: the budget is owner-private
 * (docs/android-contracts/moviegoing-prefs.md).
 */
class MoviegoingBudgetUi(val budget: MoviegoingBudget?, val onSetBudget: (MoviegoingBudget?) -> Unit)

val LocalMoviegoingBudget = compositionLocalOf<MoviegoingBudgetUi?> { null }

/** The panel's budget line: spend against the cap for the current period, or a prompt to set
 *  one. The state is spelled out in words as well as colour. */
@Composable
internal fun MoviegoingBudgetRow(trips: List<MoviegoingPricedTrip>) {
    val ui = LocalMoviegoingBudget.current ?: return
    var editing by rememberSaveable { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val status = remember(ui.budget, trips, today) { ui.budget?.let { MoviegoingBudgetRules.status(it, trips, today) } }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Overline(if (status == null) "budget" else "budget · ${periodLabel(status, today)}")
            TextButton(onClick = { editing = true }) { Text(if (status == null) "Set budget" else "Edit") }
        }
        if (status != null) {
            val color = stateColor(status.state)
            Text(
                budgetSummary(status),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
            LinearProgressIndicator(
                progress = { status.fraction.coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = budgetSummary(status) },
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
    if (editing) {
        BudgetDialog(
            current = ui.budget,
            onDismiss = { editing = false },
            onSave = { ui.onSetBudget(it); editing = false },
        )
    }
}

@Composable
private fun stateColor(state: BudgetState): Color = when (state) {
    BudgetState.UNDER -> MaterialTheme.colorScheme.primary
    BudgetState.NEAR -> MaterialTheme.colorScheme.tertiary
    BudgetState.OVER -> MaterialTheme.colorScheme.error
}

private fun periodLabel(status: BudgetStatus, today: LocalDate): String = when (status.budget.period) {
    BudgetPeriod.MONTH -> today.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    BudgetPeriod.YEAR -> today.year.toString()
}

/** "$18.00 of $40.00 · $22.00 left" / "near your cap" / "over by $5.00". */
internal fun budgetSummary(status: BudgetStatus): String {
    val head = "%s of %s".format(money(status.spent), money(status.budget.amount))
    return when (status.state) {
        BudgetState.UNDER -> "$head · ${money(status.remaining)} left"
        BudgetState.NEAR -> "$head · near your cap, ${money(status.remaining)} left"
        BudgetState.OVER -> "$head · over budget by ${money(-status.remaining)}"
    }
}

private fun money(amount: Double): String = "$%,.2f".format(amount)

@Composable
private fun BudgetDialog(current: MoviegoingBudget?, onDismiss: () -> Unit, onSave: (MoviegoingBudget?) -> Unit) {
    var amountText by rememberSaveable { mutableStateOf(current?.amount?.let { "%.2f".format(it) } ?: "") }
    var period by rememberSaveable { mutableStateOf(current?.period ?: BudgetPeriod.MONTH) }
    val amount = MoviegoingBudget.parseAmount(amountText)
    val showError = amountText.isNotBlank() && amount == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Moviegoing budget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    prefix = { Text("$") },
                    singleLine = true,
                    isError = showError,
                    supportingText = { if (showError) Text("Enter an amount above 0 and up to 100,000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BudgetPeriod.entries.forEach { option ->
                        FilterChip(
                            selected = period == option,
                            onClick = { period = option },
                            label = { Text(if (option == BudgetPeriod.MONTH) "Per month" else "Per year") },
                        )
                    }
                }
                Text(
                    "Counts ticket prices on outings, by calendar month or year. Only you can see this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = amount != null, onClick = { amount?.let { onSave(MoviegoingBudget(it, period)) } }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
