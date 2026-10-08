package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.model.LibraryPerson
import work.kumarfamilynet.cinemarchive.core.model.LibraryPersonChoice
import work.kumarfamilynet.cinemarchive.core.model.PersonCredit

/** ID-based links remain useful offline, including credits belonging only to a season or episode. */
@Composable
internal fun PersonCreditsSection(label: String, credits: List<PersonCredit>, onBrowsePerson: ((LibraryPerson) -> Unit)?) {
    if (credits.isEmpty()) return
    val people = remember(credits) { credits.groupBy { it.tmdbPersonId }.values.map { rows ->
        rows.first().copy(role = rows.mapNotNull { it.role?.takeIf(String::isNotBlank) }.distinct().joinToString(" · "))
    } }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(people, key = PersonCredit::tmdbPersonId) { credit ->
                TextButton(
                    onClick = { onBrowsePerson?.invoke(credit.person) }, enabled = onBrowsePerson != null,
                    modifier = Modifier.widthIn(max = 200.dp).testTag("credit-$label-${credit.tmdbPersonId}"),
                ) {
                    Column {
                        Text(credit.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        credit.role?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryPersonPicker(choices: List<LibraryPersonChoice>, onSelect: (LibraryPerson) -> Unit, onDismiss: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val matching = remember(choices, search) { choices.filter { it.person.name.contains(search, ignoreCase = true) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Featuring") }, confirmButton = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
    }, text = {
        Column {
            OutlinedTextField(search, onValueChange = { search = it }, label = { Text("Search people") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (matching.isEmpty()) Text("No matching people in your library", modifier = Modifier.padding(top = 12.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(matching, key = { it.person.tmdbPersonId }) { choice ->
                    TextButton(onClick = { onSelect(choice.person) }, modifier = Modifier.fillMaxWidth().testTag("person-choice-${choice.person.tmdbPersonId}")) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(choice.person.name)
                            Text(choice.titles.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    })
}
