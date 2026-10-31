package work.kumarfamilynet.cinemarchive.feature.library

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.core.model.KeepsakeRules
import work.kumarfamilynet.cinemarchive.core.model.KeepsakeSaveException
import work.kumarfamilynet.cinemarchive.core.model.OutingKeepsake
import work.kumarfamilynet.cinemarchive.core.model.PendingKeepsake
import work.kumarfamilynet.cinemarchive.data.KeepsakeRepository
import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled
import work.kumarfamilynet.cinemarchive.data.userMessage

/** A photo ready to upload: downscaled and re-encoded, so it fits the bucket's limits. */
internal class PreparedKeepsake(val bytes: ByteArray, val mimeType: String)

/**
 * Decodes [uri] (applying its EXIF rotation), downscales it so its longest edge is at most
 * [KeepsakeRules.MAX_EDGE_PX], and re-encodes it as JPEG, lowering the quality until it fits
 * [KeepsakeRules.MAX_BYTES]. Re-encoding also drops embedded metadata such as GPS position.
 */
internal fun prepareKeepsake(resolver: ContentResolver, uri: Uri): PreparedKeepsake {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val (width, height) = KeepsakeRules.scaledSize(info.size.width, info.size.height)
        if (width != info.size.width || height != info.size.height) decoder.setTargetSize(width, height)
    }
    try {
        for (quality in intArrayOf(85, 70, 50)) {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "Could not process that photo." }
            val bytes = out.toByteArray()
            if (KeepsakeRules.isValidPhoto(KeepsakeRules.JPEG, bytes.size)) return PreparedKeepsake(bytes, KeepsakeRules.JPEG)
        }
        error("That photo is too large to keep.")
    } finally {
        bitmap.recycle()
    }
}

/** Decodes at about [targetEdge] pixels on the longest side, so a thumbnail never holds a full photo. */
internal fun decodeKeepsake(bytes: ByteArray, targetEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetEdge) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * A private photo album for one outing (#208): add from the gallery or the camera, view, replace,
 * remove, and finish saving a photo whose upload was interrupted.
 * Needs a connection (see [KeepsakeRepository]); the account runtime is replaced on sign-in
 * changes, so the repository is keyed by the caller.
 */
@Composable
fun KeepsakesRoute(repository: KeepsakeRepository, outingId: String, titleName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keepsakes by remember(repository, outingId) { mutableStateOf<List<OutingKeepsake>?>(null) }
    var busy by remember(repository, outingId) { mutableStateOf(false) }
    var error by remember(repository, outingId) { mutableStateOf<String?>(null) }
    var reload by remember(repository, outingId) { mutableStateOf(0) }
    var viewing by remember(repository, outingId) { mutableStateOf<OutingKeepsake?>(null) }
    var removing by remember(repository, outingId) { mutableStateOf<OutingKeepsake?>(null) }
    var cameraPath by rememberSaveable(outingId) { mutableStateOf<String?>(null) }
    val photos = remember(repository, outingId) { mutableStateMapOf<String, ByteArray>() }
    val failed = remember(repository, outingId) { mutableStateListOf<String>() }
    // Photos whose bytes uploaded but whose row did not save, remembered on this device (KeepsakeJournal).
    var pending by remember(repository, outingId) { mutableStateOf(repository.pending(outingId)) }
    // The photo a gallery pick replaces, or null when it is a plain add.
    var replacing by rememberSaveable(outingId) { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    fun perform(failure: String, work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { cause.rethrowIfCancelled(); error = cause.userMessage(failure) }
            finally { busy = false; pending = repository.pending(outingId) }
        }
    }

    fun add(uri: Uri) = perform("Couldn't add that photo. Check your connection and try again.") {
        val saved = withContext(Dispatchers.IO) {
            val prepared = prepareKeepsake(context.contentResolver, uri)
            repository.add(outingId, prepared.bytes, prepared.mimeType).also { photos[it.id] = prepared.bytes }
        }
        keepsakes = keepsakes.orEmpty() + saved
    }

    fun replace(old: OutingKeepsake, uri: Uri) {
        val failure = "Couldn't replace that photo. Check your connection and try again."
        perform(failure) {
            try {
                val (result, bytes) = withContext(Dispatchers.IO) {
                    val prepared = prepareKeepsake(context.contentResolver, uri)
                    repository.replace(old, prepared.bytes, prepared.mimeType, keepsakes.orEmpty().size) to prepared.bytes
                }
                photos[result.saved.id] = bytes
                keepsakes = keepsakes.orEmpty().filterNot { result.oldRemoved && it.id == old.id } + result.saved
                if (!result.oldRemoved) throw IllegalStateException("The new photo is saved, but the old one couldn't be removed. Remove it to finish the swap.")
            } catch (cause: KeepsakeSaveException) {
                if (cause.originalRemoved) keepsakes = keepsakes.orEmpty().filterNot { it.id == old.id }
                throw IllegalStateException(
                    if (cause.originalRemoved) "${cause.message} Your original photo was removed to make room: finish saving the new one below, or add the original again."
                    else "${cause.message} Your original photo is unchanged.",
                )
            } catch (cause: IllegalStateException) {
                throw cause
            } catch (cause: Exception) {
                cause.rethrowIfCancelled()
                throw IllegalStateException("${cause.userMessage(failure)} Your original photo is unchanged.")
            }
        }
    }

    fun finish(photo: PendingKeepsake) = perform("Couldn't save that photo. Check your connection and try again.") {
        val saved = repository.finish(photo)
        if (keepsakes.orEmpty().none { it.id == saved.id }) keepsakes = keepsakes.orEmpty() + saved
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = replacing
        replacing = null
        if (uri != null) {
            val old = target?.let { id -> keepsakes.orEmpty().firstOrNull { it.id == id } }
            if (old != null) replace(old, uri) else add(uri)
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = cameraPath
        cameraPath = null
        if (saved && path != null) {
            val file = File(path)
            add(FileProvider.getUriForFile(context, "${context.packageName}.tickets", file))
            // The upload has its own re-encoded copy; the full-size camera file is not kept.
            scope.launch(Dispatchers.IO) { runCatching { file.delete() } }
        } else if (path != null) File(path).delete()
    }
    fun launchCamera() = perform("Couldn't open the camera.") {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "ticket-camera")
            check(directory.mkdirs() || directory.isDirectory)
            File.createTempFile("keepsake-${UUID.randomUUID()}-", ".jpg", directory)
        }
        cameraPath = file.absolutePath
        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.tickets", file))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) launchCamera() else error = "Camera permission was not granted. You can still choose a photo."
    }

    LaunchedEffect(repository, outingId, reload) {
        keepsakes = null
        try {
            val list = repository.list(outingId)
            // A pending photo that already has a row was saved after all: its reply was lost.
            repository.pending(outingId).filter { photo -> list.any { it.id == photo.id } }.forEach { repository.discardPending(it.id) }
            pending = repository.pending(outingId)
            keepsakes = list
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) { cause.rethrowIfCancelled(); keepsakes = emptyList(); error = cause.userMessage("Couldn't load your photos. Check your connection.") }
    }
    // Thumbnails download one at a time as the list appears; one that fails is retried only by reopening.
    LaunchedEffect(keepsakes) {
        for (item in keepsakes.orEmpty()) {
            if (item.id in photos || item.id in failed) continue
            try { photos[item.id] = repository.download(item) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { cause.rethrowIfCancelled(); failed += item.id }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Photos · $titleName", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Text(
                "Keep a ticket stub, the poster board or a selfie with this trip. Photos are private to you and need a connection to add or view.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val list = keepsakes
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("No photos yet.")
                else -> list.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { item ->
                            Box(Modifier.weight(1f).aspectRatio(1f).clickable(onClickLabel = "View photo") { viewing = item }, contentAlignment = Alignment.Center) {
                                val bytes = photos[item.id]
                                val thumbnail = remember(bytes) { bytes?.let { decodeKeepsake(it, 480)?.asImageBitmap() } }
                                when {
                                    thumbnail != null -> Image(thumbnail, contentDescription = "Keepsake photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    item.id in failed -> Text("Couldn't load", style = MaterialTheme.typography.bodySmall)
                                    else -> CircularProgressIndicator(Modifier.size(24.dp))
                                }
                            }
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            val canAdd = KeepsakeRules.canAddAnother(keepsakes.orEmpty().size)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && canAdd && keepsakes != null, onClick = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Choose photo") }
                TextButton(enabled = !busy && canAdd && keepsakes != null, onClick = {
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                    else permission.launch(Manifest.permission.CAMERA)
                }) { Text("Take photo") }
            }
            if (!canAdd) Text("This trip keeps up to ${KeepsakeRules.MAX_PER_OUTING} photos. Remove one to add another, or replace one.", style = MaterialTheme.typography.bodySmall)
            pending.forEach { photo ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("A photo uploaded but wasn't saved to this trip.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy && canAdd, onClick = { finish(photo) }) { Text("Finish saving") }
                        TextButton(enabled = !busy, onClick = { repository.discardPending(photo.id); pending = repository.pending(outingId) }) { Text("Discard") }
                    }
                }
            }
            if (busy) { CircularProgressIndicator(); Text("Working…") }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { error = null; reload++ }) { Text("Try again") }
            }
        }
    }

    viewing?.let { item ->
        Dialog(onDismissRequest = { viewing = null }) {
            Surface(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val full = remember(photos[item.id]) { photos[item.id]?.let { decodeKeepsake(it, KeepsakeRules.MAX_EDGE_PX / 2)?.asImageBitmap() } }
                    if (full != null) Image(full, contentDescription = "Keepsake photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                    else Text("Couldn't load this photo.")
                    Row {
                        TextButton(onClick = { viewing = null }) { Text("Close") }
                        TextButton(onClick = {
                            replacing = item.id
                            viewing = null
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }, enabled = !busy) { Text("Replace") }
                        TextButton(onClick = { removing = item; viewing = null }, enabled = !busy) { Text("Remove") }
                    }
                }
            }
        }
    }
    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!busy) removing = null },
            title = { Text("Remove this photo?") },
            text = { Text("It is removed from this trip right away. Its stored copy is deleted within a couple of days.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    perform("Couldn't remove that photo. Check your connection and try again.") {
                        repository.remove(item.id)
                        keepsakes = keepsakes.orEmpty().filterNot { it.id == item.id }
                        photos.remove(item.id)
                        removing = null
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { removing = null }) { Text("Keep") } },
        )
    }
}
