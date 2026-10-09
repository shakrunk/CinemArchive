package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.data.AccountRepository
import work.kumarfamilynet.cinemarchive.data.ProfileEdit
import work.kumarfamilynet.cinemarchive.data.ProfileRules

/** Identity editor — web `Profile.tsx`'s Identity section: display name (≤60), username
 *  (3–24, lowercase), the same validation and "taken" error; Save is disabled until dirty. */
@Composable
fun IdentityRoute(accountRepository: AccountRepository, onBack: () -> Unit, showBack: Boolean = true) {
    val profile by accountRepository.profile.collectAsStateWithLifecycle()
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(profile == null) }
    LaunchedEffect(Unit) {
        try {
            accountRepository.refreshProfile()
            loadError = null
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load your profile."
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            if (showBack) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
            Text("Identity", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp, 4.dp, 20.dp, 28.dp)) {
            ReadingWidthColumn {
                val current = profile
                when {
                    current != null -> IdentityForm(accountRepository, current.displayName.orEmpty(), current.username.orEmpty(), current.email)
                    loading -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text(loadError ?: "No profile found.", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun IdentityForm(accountRepository: AccountRepository, savedName: String, savedUsername: String, email: String) {
    var displayName by remember(savedName) { mutableStateOf(savedName) }
    var username by remember(savedUsername) { mutableStateOf(savedUsername) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

    val dirty = savedName != displayName.trim() || savedUsername != username.trim().lowercase()

    Text(
        "How you appear to friends — in their friend lists, activity feeds, and recommendation inboxes.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 16.dp),
    )
    OutlinedTextField(
        value = email,
        onValueChange = {},
        readOnly = true,
        label = { Text("Email") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = displayName,
        onValueChange = { displayName = it.take(ProfileRules.DISPLAY_NAME_MAX) },
        label = { Text("Display name") },
        placeholder = { Text("e.g. Norma Desmond") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    OutlinedTextField(
        value = username,
        onValueChange = { username = it.take(ProfileRules.USERNAME_MAX) },
        label = { Text("Username") },
        placeholder = { Text("e.g. norma-d") },
        supportingText = { Text("Optional, unique across CinemArchive. Shown when you haven't set a display name.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    message?.let { (ok, text) ->
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    Button(
        enabled = dirty && !saving,
        onClick = {
            when (val edit = ProfileRules.validate(displayName, username)) {
                is ProfileEdit.Invalid -> message = false to edit.message
                is ProfileEdit.Valid -> {
                    saving = true
                    message = null
                    scope.launch {
                        message = try {
                            accountRepository.updateProfile(edit)
                            true to "Profile saved."
                        } catch (e: Exception) {
                            false to (e.message?.takeIf { !it.startsWith("PATCH ") } ?: "Failed to save profile.")
                        }
                        saving = false
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) { Text(if (saving) "Saving…" else "Save changes") }
}
