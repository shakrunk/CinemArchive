package work.kumarfamilynet.cinemarchive.feature.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.ProfileAvatarButton
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.core.model.TitleList
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.ListsRepository

class ListsViewModel(
    private val listsRepository: ListsRepository,
    libraryRepository: LibraryRepository,
) : ViewModel() {
    val lists = listsRepository.observeLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val memberships = listsRepository.observeMembershipsByList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val titles = libraryRepository.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** listId -> its member LibraryTitles, resolved against the whole library — the Android
     *  analogue of the web app's `titles.filter(t => listMemberships[id]?.has(t.id))`. */
    val membersByList = combine(memberships, titles) { m, t ->
        m.mapValues { (_, titleIds) -> t.filter { titleIds.contains(it.id) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun createList(name: String) {
        viewModelScope.launch { listsRepository.createList(name, description = null) }
    }

    fun renameList(id: String, name: String) {
        viewModelScope.launch { listsRepository.renameList(id, name, description = null) }
    }

    fun deleteList(id: String) {
        viewModelScope.launch { listsRepository.deleteList(id) }
    }

    fun removeTitleFromList(listId: String, titleId: String) {
        viewModelScope.launch { listsRepository.removeTitleFromList(listId, titleId) }
    }
}

private class ListsViewModelFactory(
    private val listsRepository: ListsRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ListsViewModel(listsRepository, libraryRepository) as T
}

@Composable
fun ListsRoute(
    listsRepository: ListsRepository,
    libraryRepository: LibraryRepository,
    onTitleClick: (String) -> Unit,
    onOpenProfile: () -> Unit = {},
    profileInitial: String = "C",
    onFabExpandedChange: (Boolean) -> Unit = {},
) {
    val viewModel: ListsViewModel = viewModel(factory = ListsViewModelFactory(listsRepository, libraryRepository))
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val membersByList by viewModel.membersByList.collectAsStateWithLifecycle()

    // Lists has no scroll-collapse signal of its own yet — the shared FAB just stays expanded.
    androidx.compose.runtime.LaunchedEffect(Unit) { onFabExpandedChange(true) }

    var selectedListId by remember { mutableStateOf<String?>(null) }
    val selectedList = lists.find { it.id == selectedListId }

    if (selectedList != null) {
        ListDetailScreen(
            list = selectedList,
            members = membersByList[selectedList.id] ?: emptyList(),
            onBack = { selectedListId = null },
            onTitleClick = onTitleClick,
            onRemoveTitle = { titleId -> viewModel.removeTitleFromList(selectedList.id, titleId) },
            onDelete = { viewModel.deleteList(selectedList.id); selectedListId = null },
        )
    } else {
        ListsGridScreen(
            lists = lists,
            membersByList = membersByList,
            onOpenList = { selectedListId = it },
            onCreateList = viewModel::createList,
            onOpenProfile = onOpenProfile,
            profileInitial = profileInitial,
        )
    }
}

@Composable
private fun ListsGridScreen(
    lists: List<TitleList>,
    membersByList: Map<String, List<LibraryTitle>>,
    onOpenList: (String) -> Unit,
    onCreateList: (String) -> Unit,
    onOpenProfile: () -> Unit,
    profileInitial: String,
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateListDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name -> onCreateList(name); showCreateDialog = false },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("YOUR OWN REELS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("Lists", style = MaterialTheme.typography.headlineLarge)
                }
                ProfileAvatarButton(initial = profileInitial, onClick = onOpenProfile)
            }
        }

        if (lists.isEmpty()) {
            item {
                ReadingWidthColumn {
                    EmptyLists(onCreate = { showCreateDialog = true })
                }
            }
        } else {
            item {
                ReadingWidthColumn {
                    TextButton(onClick = { showCreateDialog = true }) { Text("+ New list") }
                }
            }
            items(lists, key = TitleList::id) { list ->
                ReadingWidthColumn {
                    ListRow(list, membersByList[list.id] ?: emptyList(), onClick = { onOpenList(list.id) })
                }
            }
        }
    }
}

@Composable
private fun EmptyLists(onCreate: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "No lists yet.",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Group titles into custom lists — a marathon, a ranked shortlist, anything you like.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        TextButton(onClick = onCreate) { Text("Create your first list") }
    }
}

@Composable
private fun ListRow(list: TitleList, members: List<LibraryTitle>, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.weight(1f)) {
                members.take(3).forEachIndexed { index, title ->
                    PosterSurface(
                        tint = tintForKey(title.id),
                        imageUrl = title.posterUrl,
                        modifier = Modifier
                            .size(width = 36.dp, height = 50.dp)
                            .padding(start = if (index == 0) 0.dp else 0.dp),
                        aspectRatio = 36f / 50f,
                        cornerRadius = 8.dp,
                    )
                }
            }
            Column(modifier = Modifier.weight(2f).padding(start = 12.dp)) {
                Text(list.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${members.size} ${if (members.size == 1) "title" else "titles"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ListDetailScreen(
    list: TitleList,
    members: List<LibraryTitle>,
    onBack: () -> Unit,
    onTitleClick: (String) -> Unit,
    onRemoveTitle: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete \"${list.name}\"?") },
            text = { Text("This removes the list. Titles stay in your library.") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All lists") }
            Text(list.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { confirmingDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete list", tint = MaterialTheme.colorScheme.error)
            }
        }

        if (members.isEmpty()) {
            Text(
                "Nothing in this list yet — add titles from their detail screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 100.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(members, key = LibraryTitle::id) { title ->
                    Box {
                        PosterSurface(
                            tint = tintForKey(title.id),
                            imageUrl = title.posterUrl,
                            onClick = { onTitleClick(title.id) },
                        )
                        IconButton(
                            onClick = { onRemoveTitle(title.id) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove ${title.name} from ${list.name}",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateListDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New list") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
