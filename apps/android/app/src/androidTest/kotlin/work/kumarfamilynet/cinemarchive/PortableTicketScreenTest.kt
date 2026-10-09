package work.kumarfamilynet.cinemarchive

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.designsystem.decodeTicketFile
import work.kumarfamilynet.cinemarchive.core.designsystem.renderTicketBarcode
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.library.PortableTicketRoute

@RunWith(AndroidJUnit4::class)
class PortableTicketScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val owner = TicketOwnerScope("https://tickets.invalid", "10000000-0000-4000-8000-000000000001")
    private val outing = "20000000-0000-4000-8000-000000000001"
    private val title = "30000000-0000-4000-8000-000000000001"
    private lateinit var db: LibraryDatabase
    private lateinit var runtime: TicketRuntime
    private var active = true
    private val visible = mutableStateOf(true)
    private fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, "ticket-screen.db").build()
    private fun runtime(): TicketRuntime {
        val outbox = MutationOutbox(db.outboxDao(), UnconfiguredRemoteMutationWriter(), ConflictHandler { _, _, _ -> }, RoomTransactor(db))
        return TicketRuntime.create(db, owner, File(context.filesDir, "ticket-screen-originals"), "public",
            { null }, { outbox }, { active }, { action -> action() })
    }
    @Before fun setup() = runBlocking {
        db = open(); runtime = runtime()
        db.titleDao().upsertAll(listOf(TitleEntity(title, 42, "MOVIE", "Ticket film", 2026, null, emptyList(), null, null,
            null, 90, null, "WATCHLIST", null, null, "2026-10-08T12:00:00Z", "2026-10-08T12:00:00Z")))
        db.cinemaOutingDao().upsert(CinemaOutingEntity(outing, title, "2099-10-08T19:00:00Z", 20, 90, "2099-10-08T20:50:00Z",
            "Cinema", emptyList(), "IMAX", null, auditorium = "4", seatRow = "E", seats = listOf("7"), bookingRef = "NOT-A-BARCODE",
            ticketImagePath = "/legacy/old.jpg", createdAt = "2026-10-08T12:00:00Z", updatedAt = "2026-10-08T12:00:00Z"))
    }
    @After fun cleanup() { db.close(); context.deleteDatabase("ticket-screen.db") }
    private fun show() { compose.setContent { MaterialTheme { if (visible.value) PortableTicketRoute(runtime, outing, "Ticket film", {}, {}) } } }

    @Test fun originalCodeRemoveAndRestart() = runBlocking {
        val bitmap = checkNotNull(renderTicketBarcode(TicketBarcode("012345678901", TicketBarcodeFormat.CODE_128)))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        runtime.capture(outing, "image/png", bytes.inputStream(), ::decodeTicketFile)
        show()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Original ticket photo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Show decoded CODE_128 code").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Actual CODE_128 ticket code").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Remove photo").performScrollTo().performClick()
        compose.onNodeWithText("Remove current ticket photo?").assertIsDisplayed()
        compose.onAllNodesWithText("Remove photo").onLast().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("No portable ticket photo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Original ticket photo").assertDoesNotExist()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        db.close(); db = open(); runtime = runtime()
        assertNotNull(db.ticketAttachmentDao().association(owner.projectId, owner.ownerId, outing))
        assertNull(db.ticketAttachmentDao().association(owner.projectId, owner.ownerId, outing)!!.attachmentId)
        assertEquals(2, db.outboxDao().getPending().size)
        assertNull(runtime.photo(outing))
    }

    @Test fun symbologyRoundTripAndPhotoFallback() {
        val samples = mapOf(TicketBarcodeFormat.QR_CODE to "Ticket QR 123", TicketBarcodeFormat.CODE_128 to "ABC123",
            TicketBarcodeFormat.PDF_417 to "Ticket PDF 123", TicketBarcodeFormat.AZTEC to "Ticket Aztec 123",
            TicketBarcodeFormat.ITF to "1234567890", TicketBarcodeFormat.CODABAR to "A123456B")
        samples.forEach { (format, payload) ->
            val bitmap = checkNotNull(renderTicketBarcode(TicketBarcode(payload, format))) { "Could not render $format" }
            val file = File(context.cacheDir, "ticket-$format.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val decoded = decodeTicketFile(file)
            assertEquals(format, decoded?.format); assertEquals(payload, decoded?.payload)
        }
        assertNull(renderTicketBarcode(TicketBarcode("binary\u0000", TicketBarcodeFormat.QR_CODE)))
        assertNull(renderTicketBarcode(TicketBarcode("other", TicketBarcodeFormat.OTHER)))
    }
}
