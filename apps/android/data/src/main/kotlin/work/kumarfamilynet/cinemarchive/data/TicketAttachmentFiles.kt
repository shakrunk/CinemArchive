package work.kumarfamilynet.cinemarchive.data

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import work.kumarfamilynet.cinemarchive.core.model.TicketAttachment
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcode
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

data class StoredTicketOriginal(val attachment: TicketAttachment, val file: File)

/** Immutable private originals. Call on IO. A Room intent may reference only a completed write.
 * Files left by a crash/failed Room commit are retained, never automatically adopted or deleted.
 * The directory and descriptor both identify the configured project and authenticated owner. */
class TicketAttachmentFiles(
    root: File,
    val scope: TicketOwnerScope,
    private val isCurrentOwner: () -> Boolean,
    private val syncDirectory: (File) -> Unit = ::syncTicketDirectory,
) {
    private val root = root.canonicalFile
    private val directory: File

    init {
        checkedTicketScope(scope)
        directory = File(File(this.root, ticketDigest(scope.projectId.toByteArray())), ticketDigest(scope.ownerId.toByteArray())).canonicalFile
        require(directory.path.startsWith(this.root.path + File.separator))
    }

    fun capture(attachmentId: String, mimeType: String, input: InputStream, barcode: TicketBarcode? = null): StoredTicketOriginal {
        current(); checkedTicketUuid(attachmentId)
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp")) { "Use an original JPEG, PNG, or WebP ticket image." }
        ensureDirectory()
        val temporary = File(directory, ".$attachmentId.${UUID.randomUUID()}.partial")
        var byteLength = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(12)
        var headerSize = 0
        // Do not close the caller's stream; its owner controls capture/content-provider lifetime.
        FileOutputStream(temporary).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                current()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                byteLength += count
                require(byteLength <= MAX_TICKET_BYTES) { "Ticket photos must be no larger than 20 MiB." }
                val headerCount = minOf(count, header.size - headerSize)
                if (headerCount > 0) { buffer.copyInto(header, headerSize, 0, headerCount); headerSize += headerCount }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            require(byteLength > 0 && ticketMime(header.copyOf(headerSize)) == mimeType) { "Ticket bytes do not match their image format." }
            output.fd.sync()
        }
        val descriptor = checkedTicketAttachment(scope, TicketAttachment(attachmentId, "${scope.ownerId}/$attachmentId/original",
            mimeType, byteLength, digest.digest().toTicketHex(), barcode))
        return publish(temporary, descriptor)
    }

    private fun publish(temporary: File, descriptor: TicketAttachment): StoredTicketOriginal {
        val original = originalFile(descriptor.id)
        publishLock.withLock {
            current()
            if (original.exists()) {
                verify(original, descriptor)
                // The identical immutable original is already durable; this temporary is redundant.
                check(temporary.delete()) { "Could not finish the duplicate ticket capture." }
            } else {
                Files.move(temporary.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            syncDirectory(directory)
            current()
        }
        return StoredTicketOriginal(descriptor, original)
    }

    /** Downloads use this same admission path; an existing UUID must have exactly these bytes. */
    fun cache(attachment: TicketAttachment, input: InputStream): StoredTicketOriginal {
        checkedTicketAttachment(scope, attachment)
        // Validate BEFORE publishing at the immutable name, even when the remote response lies.
        ensureDirectory()
        val download = File(directory, ".${attachment.id}.${UUID.randomUUID()}.download")
        FileOutputStream(download).use { output ->
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            while (true) {
                current()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                size += count
                require(size <= attachment.byteLength) { "Downloaded ticket exceeds its recorded size." }
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
        verify(download, attachment)
        return publish(download, attachment)
    }

    fun read(attachment: TicketAttachment): File {
        current(); checkedTicketAttachment(scope, attachment)
        val file = originalFile(attachment.id)
        verify(file, attachment)
        current()
        return file
    }

    /** Only a user-confirmed recovery action may call this. A synced raw path proves no owner. */
    fun captureLegacy(outingId: String, legacyPath: String, legacyRoot: File, attachmentId: String,
        mimeType: String, userConfirmedOwner: Boolean, barcode: TicketBarcode? = null): StoredTicketOriginal {
        current(); checkedTicketUuid(outingId)
        require(userConfirmedOwner) { "Confirm this photo belongs to the signed-in account before uploading it." }
        val file = File(legacyPath).canonicalFile
        require(file.parentFile == legacyRoot.canonicalFile && file.name.substringBeforeLast('.') == outingId && file.isFile) {
            "The original device-local ticket file is unavailable."
        }
        return FileInputStream(file).use { capture(attachmentId, mimeType, it, barcode) }
    }

    private fun ensureDirectory() {
        current()
        require(directory.mkdirs() || directory.isDirectory) { "Cannot create private ticket storage." }
        // Persist newly-created directory entries before an intent can reference an original.
        syncDirectory(checkNotNull(directory.parentFile))
        syncDirectory(root)
        root.parentFile?.let(syncDirectory)
        current()
    }

    private fun originalFile(id: String): File = File(directory, "${checkedTicketUuid(id)}.original").also {
        require(it.canonicalFile.parentFile == directory) { "Invalid private ticket location." }
    }

    private fun verify(file: File, attachment: TicketAttachment) {
        current()
        require(file.isFile && file.length() == attachment.byteLength) { "Original ticket is unavailable or incomplete." }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val header = ByteArray(12)
        var headerSize = 0
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                current()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                size += count
                require(size <= attachment.byteLength)
                val headerCount = minOf(count, header.size - headerSize)
                if (headerCount > 0) { buffer.copyInto(header, headerSize, 0, headerCount); headerSize += headerCount }
                digest.update(buffer, 0, count)
            }
        }
        require(size == attachment.byteLength && digest.digest().toTicketHex() == attachment.sha256 &&
            ticketMime(header.copyOf(headerSize)) == attachment.mimeType) { "Original ticket failed its integrity check." }
        current()
    }

    private fun current() { check(isCurrentOwner()) { "This sign-in has ended. Ticket work remains with its original account." } }

    private companion object { val publishLock = ReentrantLock() }
}

private fun syncTicketDirectory(directory: File) {
    val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
}

internal fun ticketDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toTicketHex()
private fun ByteArray.toTicketHex(): String = joinToString("") { "%02x".format(it) }

internal fun ticketMime(bytes: ByteArray): String? = when {
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
    bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
    else -> null
}
