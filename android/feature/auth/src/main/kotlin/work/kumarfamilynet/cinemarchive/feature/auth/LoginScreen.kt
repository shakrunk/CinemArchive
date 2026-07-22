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

private enum class LoginMethod { MAGIC_LINK, PASSKEY, QR }

/**
 * Shown whenever [AuthRepository.observeSession] is null — see MainActivity's app-shell
 * gate. Only the magic-link pane has a real backend behind it (see plan doc / this
 * package's kdoc-equivalent context in CinemArchiveApplication); passkey and QR are real,
 * interactive UI wired to a "not built yet" message rather than a mockup.
 */
@Composable
fun LoginRoute(authRepository: AuthRepository, modifier: Modifier = Modifier) {
    LoginScreen(
        onSendMagicLink = { email -> withContext(Dispatchers.IO) { authRepository.sendMagicLink(email) } },
        modifier = modifier,
    )
}

@Composable
private fun LoginScreen(onSendMagicLink: suspend (String) -> Unit, modifier: Modifier = Modifier) {
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
                    ChoiceOption(LoginMethod.PASSKEY, "Passkey"),
                    ChoiceOption(LoginMethod.QR, "Scan QR"),
                ),
                selected = method,
                onSelect = { method = it },
            )

            Spacer(modifier = Modifier.height(28.dp))

            when (method) {
                LoginMethod.MAGIC_LINK -> MagicLinkPane(onSend = onSendMagicLink)
                LoginMethod.PASSKEY -> PasskeyPane(snackbarHostState = snackbarHostState)
                LoginMethod.QR -> QrScanPane(snackbarHostState = snackbarHostState)
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

@Composable
private fun PasskeyPane(snackbarHostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.Key,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Sign in without a password, using a passkey stored on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                scope.launch {
                    snackbarHostState.showSnackbar("Passkey sign-in isn't wired up yet — use email link for now.")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue with Passkey")
        }
    }
}

@Composable
private fun QrScanPane(snackbarHostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!hasCameraPermission) {
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Point your camera at the QR code shown in CinemArchive on your desktop.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                Text("Enable camera")
            }
        } else {
            var lastHandledValue by remember { mutableStateOf<String?>(null) }
            var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
            val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
            val scanner = remember { BarcodeScanning.getClient() }

            Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().height(320.dp)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener(
                            {
                                val provider = cameraProviderFuture.get()
                                cameraProvider = provider
                                val preview = Preview.Builder().build()
                                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage == null) {
                                        imageProxy.close()
                                    } else {
                                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                        scanner.process(image)
                                            .addOnSuccessListener { barcodes ->
                                                val value = barcodes.firstNotNullOfOrNull { it.rawValue }
                                                if (value != null) {
                                                    scope.launch {
                                                        if (value != lastHandledValue) {
                                                            lastHandledValue = value
                                                            snackbarHostState.showSnackbar("Desktop pairing isn't wired up yet.")
                                                        }
                                                    }
                                                }
                                            }
                                            .addOnCompleteListener { imageProxy.close() }
                                    }
                                }
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            },
                            ContextCompat.getMainExecutor(ctx),
                        )
                        previewView
                    },
                    onRelease = { cameraProvider?.unbindAll() },
                )
            }

            DisposableEffect(Unit) {
                onDispose {
                    scanner.close()
                    analysisExecutor.shutdown()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Point your camera at the QR code shown in CinemArchive on your desktop.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
