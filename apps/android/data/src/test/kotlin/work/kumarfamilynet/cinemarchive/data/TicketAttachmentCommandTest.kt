package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcode
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcodeFormat

class TicketAttachmentCommandTest {
    private val fixture = TicketAttachmentFixture

    @Test fun serializedAttachAndDetachKeepExplicitNullAndImmutableScope() {
        val command = fixture.command()
        val encoded = command.toTicketJson()
        assertTrue(encoded.has("expectedAttachmentId") && encoded.isNull("expectedAttachmentId"))
        assertEquals(command, ticketCommandFromJson(JSONObject(encoded.toString())))
        val detach = command.copy(attachment = null, expectedAttachmentId = fixture.attachment)
        assertEquals("ticket.detach", detach.toTicketJson().getString("kind"))
        assertTrue(detach.toTicketJson().has("attachment") && detach.toTicketJson().isNull("attachment"))
        assertEquals(detach, ticketCommandFromJson(JSONObject(detach.toTicketJson().toString())))
    }

    @Test fun malformedOrUnknownFieldsCannotChangePersistedIntentOnRetry() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("expectedAttachmentId") }, { it.put("kind", "ticket.detach") }, { it.put("version", 2) },
            { it.put("extra", true) }, { it.put("operationId", "../foreign") },
            { it.getJSONObject("attachment").put("byteLength", 4.5) },
            { it.getJSONObject("attachment").put("byteLength", "136") },
            { it.getJSONObject("attachment").put("mimeType", "image/svg+xml") },
            { it.getJSONObject("attachment").put("objectKey", "someone-else/original") },
            { it.getJSONObject("attachment").put("sha256", "A".repeat(64)) },
        )
        mutations.forEach { change ->
            val json = fixture.command().toTicketJson(); change(json)
            assertThrows(Exception::class.java) { ticketCommandFromJson(json) }
        }
    }

    @Test fun barcodeBoundsAreUtf8AndOtherFormatRemainsPhotoFallbackMetadata() {
        val original = fixture.descriptor()
        val other = original.copy(barcode = TicketBarcode("unhandled-code", TicketBarcodeFormat.OTHER))
        assertEquals(other, ticketAttachmentFromJson(fixture.scope, other.toTicketJson()))
        assertThrows(IllegalArgumentException::class.java) {
            checkedTicketAttachment(fixture.scope, original.copy(barcode = TicketBarcode("é".repeat(16385), TicketBarcodeFormat.QR_CODE)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkedTicketAttachment(fixture.scope, original.copy(barcode = TicketBarcode("\uD800", TicketBarcodeFormat.QR_CODE)))
        }
    }

    @Test fun scopeAndReplacementIdentifiersAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { checkedTicketScope(fixture.scope.copy(projectId = "https://user:secret@tickets.supabase.co")) }
        assertThrows(IllegalArgumentException::class.java) { checkedTicketScope(fixture.scope.copy(projectId = "https://tickets.supabase.co/another-project")) }
        assertThrows(IllegalArgumentException::class.java) { checkedTicketCommand(fixture.command().copy(expectedAttachmentId = fixture.attachment)) }
        assertThrows(IllegalArgumentException::class.java) { checkedTicketAttachment(fixture.scope, fixture.descriptor().copy(byteLength = MAX_TICKET_BYTES + 1)) }
    }
}
