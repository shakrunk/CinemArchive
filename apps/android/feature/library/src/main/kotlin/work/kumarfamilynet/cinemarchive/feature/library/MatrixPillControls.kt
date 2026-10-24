package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.THE_MATRIX_TMDB_ID
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail

private val BluePill = Color(0xFF4A9EFF)
private val RedPill = Color(0xFFF87171)
private val MatrixGreen = Color(0xFF00FF41)

/**
 * The Matrix's red/blue pill choice, shown once a viewing is logged (or the title is watched).
 * Taking the red pill earns the secret "The Construct" palette — mirrors the web's
 * MatrixPillSelector. The blue pill is a deliberate no-op: the story ends.
 */
@Composable
fun MatrixPillControls(detail: TitleDetail, onRedPill: () -> Unit) {
    if (detail.tmdbId != THE_MATRIX_TMDB_ID) return
    if (detail.viewings.isEmpty() && detail.status != LibraryStatus.WATCHED) return
    var welcome by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        PillChip("Blue Pill", BluePill, onClick = {})
        PillChip("Red Pill", RedPill, onClick = {
            welcome = true
            onRedPill()
        })
    }

    if (welcome) {
        LaunchedEffect(Unit) {
            delay(2_200)
            welcome = false
        }
        Dialog(onDismissRequest = { welcome = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color(0xF2000500)), contentAlignment = Alignment.Center) {
                Text(
                    "Welcome to the real world.",
                    color = MatrixGreen,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun PillChip(label: String, color: Color, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontFamily = FontFamily.Monospace, color = color) },
        leadingIcon = { Box(Modifier.size(8.dp).background(color, CircleShape)) },
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = color.copy(alpha = 0.4f)),
    )
}
