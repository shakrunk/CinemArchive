package work.kumarfamilynet.cinemarchive.feature.library

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.core.designsystem.TicketScreen
import work.kumarfamilynet.cinemarchive.core.designsystem.TicketBarcodeImage
import work.kumarfamilynet.cinemarchive.core.designsystem.decodeTicketFile
import work.kumarfamilynet.cinemarchive.data.TicketRuntime
import work.kumarfamilynet.cinemarchive.data.TicketConflictReview
import work.kumarfamilynet.cinemarchive.data.TicketViewState

/** The account runtime is replaced on sign-in changes; every IO call is generation fenced. */
@Composable
fun PortableTicketRoute(runtime: TicketRuntime, outingId: String, titleName: String,
    onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by remember(runtime, outingId) { runtime.observe(outingId) }
        .collectAsState(TicketViewState(null, null, emptyList()))
    var photo by remember(runtime, outingId) { mutableStateOf<File?>(null) }
    var busy by remember(runtime, outingId) { mutableStateOf(false) }
    var error by remember(runtime, outingId) { mutableStateOf<String?>(null) }
    var codeMode by rememberSaveable(outingId) { mutableStateOf(false) }
    var confirmLegacy by remember(runtime, outingId) { mutableStateOf(false) }
    var confirmRemove by remember(runtime, outingId) { mutableStateOf(false) }
    var review by remember(runtime, outingId) { mutableStateOf<TicketConflictReview?>(null) }
    var currentPhoto by remember(runtime, outingId) { mutableStateOf<File?>(null) }
    var savedPhoto by remember(runtime, outingId) { mutableStateOf<File?>(null) }
    var exporting by rememberSaveable(outingId) { mutableStateOf<String?>(null) }
    var cameraPath by rememberSaveable(outingId) { mutableStateOf<String?>(null) }
    var cameraOwner by rememberSaveable(outingId) { mutableStateOf<String?>(null) }
    var pickerOwner by rememberSaveable(outingId) { mutableStateOf<String?>(null) }
    var legacyPhoto by remember(runtime, outingId) { mutableStateOf<File?>(null) }
    var photoReload by remember { mutableIntStateOf(0) }
    fun perform(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not finish. Your saved ticket is retained." }
            finally { busy = false }
        }
    }
    fun capture(uri: Uri, mime: String?) = perform {
        withContext(Dispatchers.IO) {
            val format = mime ?: context.contentResolver.getType(uri) ?: error("Could not read the image format.")
            context.contentResolver.openInputStream(uri)?.use { runtime.capture(outingId, format, it, ::decodeTicketFile) }
                ?: error("Could not open the original image.")
        }
        photoReload++; onSaved()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && pickerOwner == runtime.captureSessionKey) capture(uri, null)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = cameraPath
        if (saved && path != null && cameraOwner == runtime.captureSessionKey) capture(FileProvider.getUriForFile(context, "${context.packageName}.tickets", File(path)), "image/jpeg")
    }
    fun launchCamera() = perform {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "ticket-camera")
            check(directory.mkdirs() || directory.isDirectory)
            File.createTempFile("ticket-${UUID.randomUUID()}-", ".jpg", directory)
        }
        cameraPath = file.absolutePath
        cameraOwner = runtime.captureSessionKey
        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.tickets", file))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) launchCamera() else error = "Camera permission was not granted. You can still choose a photo."
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exporting
        if (uri != null && id != null) perform {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { runtime.exportOriginal(id, it) }
                    ?: error("Could not open the export destination.")
            }
            exporting = null
        }
    }
    LaunchedEffect(runtime, outingId, state.association, photoReload) {
        photo = null
        if (state.association?.attachment != null) {
            try { photo = runtime.photo(outingId)?.file }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Original unavailable. Reconnect to download it." }
        }
    }
    LaunchedEffect(runtime, outingId) {
        try { runtime.refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Same-owner cached originals remain available offline. */ }
    }
    val outing = state.outing
    val content: @Composable () -> Unit = {
        val descriptor = state.association?.attachment
        Text(if (descriptor != null) "Private ticket · available on this device after download" else "No portable ticket photo")
        if (codeMode && descriptor?.barcode != null) TicketBarcodeImage(descriptor.barcode!!)
        else photo?.let { TicketPhoto(it, "Original ticket photo") }
        if (descriptor?.barcode != null) TextButton(onClick = { codeMode = !codeMode }) {
            Text(if (codeMode) "Show original photo" else "Show decoded ${descriptor.barcode!!.format.name} code")
        }
        if (descriptor != null && photo == null) TextButton(onClick = { photoReload++ }, enabled = !busy) { Text("Download original") }
        outing?.bookingRef?.let { Text("Booking reference: $it") }
        if (outing != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { pickerOwner = runtime.captureSessionKey; gallery.launch("image/*") }, enabled = !busy) { Text(if (descriptor == null) "Choose photo" else "Replace photo") }
            TextButton(onClick = {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                else permission.launch(Manifest.permission.CAMERA)
            }, enabled = !busy) { Text("Take photo") }
        }
        if (descriptor != null) TextButton(onClick = { confirmRemove = true }, enabled = !busy) { Text("Remove photo") }
        if (outing.ticketImagePath != null) {
            Text(if (state.association == null) "An older device-local photo is recorded. It has not been verified for this account."
                else "The old device-local photo is retained separately for recovery; it is not the current ticket.")
            TextButton(onClick = { confirmLegacy = true }, enabled = !busy) { Text("View device-local original") }
            legacyPhoto?.let { old ->
                TicketPhoto(old, "Confirmed device-local original")
                TextButton(onClick = { perform {
                    runtime.migrateLegacy(outingId, File(context.filesDir, "tickets"), true, ::decodeTicketFile)
                    legacyPhoto = null; photoReload++; onSaved()
                } }, enabled = !busy) { Text("Make available on other devices") }
            }
        }
        }
        if (busy) { CircularProgressIndicator(); Text("Saving or confirming…") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.changes.isNotEmpty()) Text("Saved ticket changes", style = MaterialTheme.typography.titleMedium)
        state.changes.forEach { change ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(if (change.pending) change.message ?: "Saved on this device · waiting to sync" else "Retained original change")
                if (change.pending) TextButton(onClick = { perform { runtime.retry(change.id); photoReload++; onSaved() } }, enabled = !busy) { Text("Retry / confirm delivery") }
                if (change.canReview) TextButton(onClick = { perform {
                    review = runtime.review(change.id)
                    savedPhoto = if (change.hasOriginal) runtime.original(change.id).file else null
                    currentPhoto = null
                    try { currentPhoto = runtime.currentPhoto(checkNotNull(review))?.file }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Identity and revision comparison remain explicit if a photo cannot download. */ }
                } }, enabled = !busy) { Text("Compare with current ticket") }
                if (change.hasOriginal) TextButton(onClick = { perform {
                    val original = runtime.original(change.id)
                    val extension = when (original.attachment.mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                    exporting = change.id; export.launch("ticket-original-${change.id}.$extension")
                } }, enabled = !busy) { Text("Export retained original") }
            }
        }
    }
    if (outing != null) TicketScreen(titleName, outing, onBack, ticketContent = content)
    else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This outing is unavailable. Saved ticket changes remain recoverable.")
        TextButton(onClick = onBack) { Text("Back") }
        content()
    }
    if (confirmLegacy) AlertDialog(onDismissRequest = { if (!busy) confirmLegacy = false },
        title = { Text("Recover device-local photo?") },
        text = { Column {
            Text("A synced file path does not prove who owns the photo. Confirm this original belongs to your current account before opening it. You can then choose to make a portable copy; the old file is retained.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { perform {
            legacyPhoto = runtime.legacyPhoto(outingId, File(context.filesDir, "tickets"), true)
            confirmLegacy = false
        } }) { Text("This photo is mine") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmLegacy = false }) { Text("Cancel") } })
    if (confirmRemove) AlertDialog(onDismissRequest = { if (!busy) confirmRemove = false }, title = { Text("Remove current ticket photo?") },
        text = { Column {
            Text("The removal is saved offline and synced to your other devices. Retained originals remain exportable for recovery.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { perform { runtime.remove(outingId); confirmRemove = false; onSaved() } }) { Text("Remove photo") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmRemove = false }) { Text("Cancel") } })
    review?.let { comparison -> AlertDialog(onDismissRequest = { if (!busy) review = null }, title = { Text("Resolve rejected ticket change") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Current plan: ${comparison.venue ?: "No venue"} · ${comparison.showtime ?: "Outing deleted"}")
            Text("Current photo: ${comparison.currentAttachmentId ?: "None"}")
            currentPhoto?.let { TicketPhoto(it, "Current server ticket") }
            Text("Saved original:")
            savedPhoto?.let { TicketPhoto(it, "Rejected original ticket") } ?: Text("Remove photo")
            Text("Only this confirmed rejected change is resolved. Later saved changes keep their original intent.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { Column {
            TextButton(enabled = !busy, onClick = { perform { runtime.resolve(comparison, false); review = null; photoReload++; onSaved() } }) { Text("Use current ticket") }
            if (comparison.canReapply) TextButton(enabled = !busy, onClick = { perform { runtime.resolve(comparison, true); review = null; photoReload++; onSaved() } }) { Text("Save my change again") }
        } }, dismissButton = { TextButton(enabled = !busy, onClick = { review = null }) { Text("Cancel") } }) }
}

@Composable
private fun TicketPhoto(file: File, label: String) {
    AsyncImage(model = file, contentDescription = label, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 340.dp).background(Color.White))
}

@Composable
fun SavedTicketsSection(runtime: TicketRuntime, onOpen: (String, String) -> Unit) {
    val entries by remember(runtime) { runtime.observeSavedOutings() }.collectAsState(emptyList())
    if (entries.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Saved tickets", style = MaterialTheme.typography.titleMedium)
        entries.forEach { entry -> TextButton(onClick = { onOpen(entry.id, entry.title) }) {
            Text(entry.title + if (entry.pendingCount > 0) " · ${entry.pendingCount} pending" else " · retained originals")
        } }
    }
}
