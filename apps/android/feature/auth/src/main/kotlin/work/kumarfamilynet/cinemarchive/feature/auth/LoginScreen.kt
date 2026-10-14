package work.kumarfamilynet.cinemarchive.feature.auth

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.data.AuthRepository

private enum class LoginMethod { MAGIC_LINK, INVITE }

/**
 * Shown whenever [AuthRepository.observeSession] is null — see MainActivity's app-shell gate.
 * Two real flows, both backed by the same backend as the web app: Email (magic link, the exact
 * `signInWithOtp(shouldCreateUser = false)` call web uses for both its "magic link" and its
 * "passkey" buttons) and Invite (`redeem-invite` Edge Function, then the magic link).
 *
 * Deliberately absent: a Passkey tab and a Scan-QR tab. Web's "Sign in with passkey" is the same
 * OTP email (no WebAuthn assertion is ever performed — see apps/web/src/lib/auth.ts
 * `signInWithPasskey`), and no QR pairing producer exists on any client, so both were
 * non-functional placeholders. See docs/android-parity-matrix.md / the channel for the audit.
 */
@Composable
fun LoginRoute(authRepository: AuthRepository, modifier: Modifier = Modifier) {
    LoginScreen(
        onSendMagicLink = { email -> withContext(Dispatchers.IO) { authRepository.sendMagicLink(email) } },
        onRedeemInvite = { email, code -> withContext(Dispatchers.IO) { authRepository.redeemInviteAndSendLink(email, code) } },
        modifier = modifier,
    )
}

@Composable
private fun LoginScreen(
    onSendMagicLink: suspend (String) -> Unit,
    onRedeemInvite: suspend (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var method by remember { mutableStateOf(LoginMethod.MAGIC_LINK) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(24.dp),
        ) {
            Text("CinemArchive", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Sign in to sync your library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
            )

            SegmentedGroup(
                options = listOf(
                    ChoiceOption(LoginMethod.MAGIC_LINK, "Email"),
                    ChoiceOption(LoginMethod.INVITE, "Invite"),
                ),
                selected = method,
                onSelect = { method = it },
            )

            Spacer(modifier = Modifier.height(28.dp))

            when (method) {
                LoginMethod.MAGIC_LINK -> MagicLinkPane(onSend = onSendMagicLink)
                LoginMethod.INVITE -> InvitePane(onRedeem = onRedeemInvite)
            }
        }
    }
}

private sealed interface MagicLinkStatus {
    data object Idle : MagicLinkStatus
    data object Sending : MagicLinkStatus
    data object Sent : MagicLinkStatus
    data class Error(val message: String) : MagicLinkStatus
}

@Composable
private fun MagicLinkPane(onSend: suspend (String) -> Unit, modifier: Modifier = Modifier) {
    var email by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<MagicLinkStatus>(MagicLinkStatus.Idle) }
    val scope = rememberCoroutineScope()
    val isSending = status is MagicLinkStatus.Sending

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                if (status !is MagicLinkStatus.Idle) status = MagicLinkStatus.Idle
            },
            label = { Text("Email") },
            singleLine = true,
            enabled = !isSending,
            leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                status = MagicLinkStatus.Sending
                scope.launch {
                    status = runCatching { onSend(email.trim()) }.fold(
                        onSuccess = { MagicLinkStatus.Sent },
                        onFailure = { MagicLinkStatus.Error(it.message ?: "Something went wrong — try again.") },
                    )
                }
            },
            enabled = email.isNotBlank() && !isSending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Send link")
            }
        }
        when (val current = status) {
            is MagicLinkStatus.Sent -> Text(
                "Check your email — tap the link on this phone to finish signing in.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
            is MagicLinkStatus.Error -> Text(
                current.message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
            else -> Unit
        }
    }
}

/** Invite-only sign-up (web `InviteRedeemForm`): email + invite code, then the same magic link. */
@Composable
private fun InvitePane(onRedeem: suspend (String, String) -> Unit, modifier: Modifier = Modifier) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<MagicLinkStatus>(MagicLinkStatus.Idle) }
    val scope = rememberCoroutineScope()
    val isBusy = status is MagicLinkStatus.Sending

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                if (status !is MagicLinkStatus.Idle) status = MagicLinkStatus.Idle
            },
            label = { Text("Email") },
            singleLine = true,
            enabled = !isBusy,
            leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it.uppercase()
                if (status !is MagicLinkStatus.Idle) status = MagicLinkStatus.Idle
            },
            label = { Text("Invite code") },
            singleLine = true,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                status = MagicLinkStatus.Sending
                scope.launch {
                    status = runCatching { onRedeem(email.trim(), code.trim()) }.fold(
                        onSuccess = { MagicLinkStatus.Sent },
                        onFailure = { MagicLinkStatus.Error(it.message ?: "Failed to redeem invite code.") },
                    )
                }
            },
            enabled = email.isNotBlank() && code.isNotBlank() && !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Create account")
            }
        }
        when (val current = status) {
            is MagicLinkStatus.Sent -> Text(
                "Account created — check your inbox and tap the magic link on this phone to finish signing in.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
            is MagicLinkStatus.Error -> Text(
                current.message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
            else -> Unit
        }
    }
}
