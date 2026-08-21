package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One list row's name and whether the sheet's title is currently a member. */
data class ListMembershipOption(val listId: String, val name: String, val isMember: Boolean)

/**
 * "Add to list" picker — the many-to-many analogue of [PostShowSheet]'s stateless,
 * callback-driven shape, and lives here (not `feature:lists`) for the same reason
 * [PostShowSheet] does: `feature:library`'s title detail screen needs to show it too, and
 * feature modules can't depend on each other. Each toggle fires immediately — no batched
 * Save button — since [onToggle] is already expected to be an optimistic, fire-and-forget
 * write (mirrors the web app's AddToListSheet.tsx).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToListSheet(
    titleName: String,
    lists: List<ListMembershipOption>,
    onToggle: (listId: String) -> Unit,
    onCreateList: (name: String) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    var newListName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp, 0.dp, 20.dp, 28.dp)) {
            Text("ADD TO LIST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(titleName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 16.dp))

            if (lists.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lists.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = option.isMember, onValueChange = { onToggle(option.listId) })
                                .padding(vertical = 6.dp),
                        ) {
                            Checkbox(checked = option.isMember, onCheckedChange = { onToggle(option.listId) })
                            Text(option.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp))
                            if (option.isMember) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    label = { Text("New list name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onCreateList(newListName.trim()); newListName = "" },
                    enabled = newListName.isNotBlank(),
                ) { Text("Add") }
            }
        }
    }
}
