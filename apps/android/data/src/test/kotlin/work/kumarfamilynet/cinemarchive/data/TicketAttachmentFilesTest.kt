package work.kumarfamilynet.cinemarchive.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TicketAttachmentFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = TicketAttachmentFixture

    private fun store(root: File, current: () -> Boolean = { true }, sync: (File) -> Unit = {}) =
        TicketAttachmentFiles(root, fixture.scope, current, sync)

    @Test fun originalSurvivesReopenAndAnIdCannotOverwriteDifferentBytes() {
        val root = temporary.newFolder("originals")
        val synced = mutableListOf<File>()
        val files = store(root, sync = synced::add)
        val original = files.capture(fixture.attachment, "image/png", fixture.bytes.inputStream())
        assertEquals(fixture.descriptor(), original.attachment)
        assertArrayEquals(fixture.bytes, store(root).read(original.attachment).readBytes())
        assertTrue(synced.contains(checkNotNull(original.file.parentFile)))
        assertEquals(original.file, files.capture(fixture.attachment, "image/png", fixture.bytes.inputStream()).file)
        assertThrows(IllegalArgumentException::class.java) {
            files.capture(fixture.attachment, "image/png", (fixture.bytes + 3.toByte()).inputStream())
        }
        assertArrayEquals(fixture.bytes, files.read(original.attachment).readBytes())
    }

    @Test fun accountAndProjectNamespacesCannotReadAnotherOriginal() {
        val root = temporary.newFolder("scopes")
        val original = store(root).capture(fixture.attachment, "image/png", fixture.bytes.inputStream())
        val otherProject = TicketAttachmentFiles(root, fixture.scope.copy(projectId = "https://other.supabase.co"), { true }, {})
        assertThrows(IllegalArgumentException::class.java) { otherProject.read(original.attachment) }
        val otherOwner = TicketAttachmentFiles(root, fixture.scope.copy(ownerId = "10000000-0000-4000-8000-000000000002"), { true }, {})
        assertThrows(IllegalArgumentException::class.java) { otherOwner.read(original.attachment) }
    }

    @Test fun accountChangeDuringInputRetainsOnlyAnUnreferencedPartialInOriginalScope() {
        val root = temporary.newFolder("fence")
        var current = true
        val input = object : ByteArrayInputStream(fixture.bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                current = false
                return count
            }
        }
        assertThrows(IllegalStateException::class.java) { store(root, { current }).capture(fixture.attachment, "image/png", input) }
        assertFalse(root.walkTopDown().any { it.name.endsWith(".original") })
        assertTrue(root.walkTopDown().any { it.name.endsWith(".partial") })
    }

    @Test fun oversizedAndMislabeledInputNeverPublishesAnOriginal() {
        val root = temporary.newFolder("bounded")
        var read = 0L
        val unbounded = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(0, offset, offset + length)
                read += length
                return length
            }
        }
        assertThrows(IllegalArgumentException::class.java) { store(root).capture(fixture.attachment, "image/png", unbounded) }
        assertTrue(read <= MAX_TICKET_BYTES + 65536)
        assertThrows(IllegalArgumentException::class.java) { store(root).capture(fixture.attachment, "image/jpeg", fixture.bytes.inputStream()) }
        assertFalse(root.walkTopDown().any { it.name.endsWith(".original") })
    }

    @Test fun aBadDownloadCannotPoisonTheImmutableCacheName() {
        val root = temporary.newFolder("downloads")
        val files = store(root)
        val bad = fixture.bytes.clone().also { it[it.lastIndex] = 0 }
        assertThrows(IllegalArgumentException::class.java) { files.cache(fixture.descriptor(), bad.inputStream()) }
        assertFalse(root.walkTopDown().any { it.name.endsWith(".original") })
        val cached = files.cache(fixture.descriptor(), fixture.bytes.inputStream())
        assertArrayEquals(fixture.bytes, files.read(cached.attachment).readBytes())
    }

    @Test fun directorySyncFailureLeavesOriginalRecoverableAndRetrySyncsAgain() {
        val root = temporary.newFolder("sync-failure")
        var fail = true
        var publishedSyncs = 0
        val files = store(root, sync = { directory ->
            if (directory.listFiles().orEmpty().any { it.name.endsWith(".original") }) {
                publishedSyncs++
                if (fail) throw IOException("directory sync failed")
            }
        })
        assertThrows(IOException::class.java) { files.capture(fixture.attachment, "image/png", fixture.bytes.inputStream()) }
        assertTrue(root.walkTopDown().any { it.name.endsWith(".original") })
        fail = false
        val retried = files.capture(fixture.attachment, "image/png", fixture.bytes.inputStream())
        assertTrue(publishedSyncs >= 2)
        assertArrayEquals(fixture.bytes, retried.file.readBytes())
    }

    @Test fun legacyMigrationRequiresExplicitOwnerAndCanonicalOutingPathAndPreservesSource() {
        val root = temporary.newFolder("portable")
        val legacy = temporary.newFolder("tickets")
        val source = File(legacy, "${fixture.outing}.png").apply { writeBytes(fixture.bytes) }
        val files = store(root)
        assertThrows(IllegalArgumentException::class.java) {
            files.captureLegacy(fixture.outing, source.path, legacy, fixture.attachment, "image/png", false)
        }
        val outside = temporary.newFile("${fixture.outing}.png").apply { writeBytes(fixture.bytes) }
        assertThrows(IllegalArgumentException::class.java) {
            files.captureLegacy(fixture.outing, outside.path, legacy, fixture.attachment, "image/png", true)
        }
        val migrated = files.captureLegacy(fixture.outing, source.path, legacy, fixture.attachment, "image/png", true)
        assertArrayEquals(fixture.bytes, source.readBytes())
        assertArrayEquals(fixture.bytes, migrated.file.readBytes())
        assertNotEquals(source.canonicalFile, migrated.file.canonicalFile)
    }
}
