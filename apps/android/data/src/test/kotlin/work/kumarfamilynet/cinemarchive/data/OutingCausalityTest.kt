package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

@RunWith(RobolectricTestRunner::class)
class OutingCausalityTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val outingId = "22222222-2222-4222-8222-222222222222"
    private val scope = TicketOwnerScope("https://project.example", owner)
    private val revision = "2026-10-08T10:00:00.123456Z"
    private val outing = CinemaOutingEntity(outingId, "title", "2026-10-08T19:00:00Z", runtimeMinutes = 90,
        endsAt = "2026-10-08T21:00:00Z", venue = "Cinema", companions = emptyList(), format = null,
        ticketPrice = null, notes = null, createdAt = revision, updatedAt = revision)
    private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun command(n: Int, predecessor: String? = null, create: Boolean = false): OutboxEntity {
        val intent = if (create) outing.mutationPayload() else JSONObject().put("id", outingId).put("venue", "Saved venue")
        return OutboxEntity(id(n), "cinema_outing", outingId, OUTING_COMMAND,
            outingCommandPayload(intent, create, revision.takeIf { predecessor == null && !create }, predecessor).toString(), 100L - n)
    }
    private fun ticket(n: Int, predecessor: String? = null, guarded: Boolean = true, ticketScope: TicketOwnerScope = scope): OutboxEntity {
        val command = TicketAttachmentCommand(id(n), ticketScope, outingId, null, null,
            revision.takeIf { guarded && predecessor == null }, predecessor.takeIf { guarded })
        return OutboxEntity(id(n), TICKET_COMMAND_ENTITY, outingId, TICKET_COMMAND_OPERATION, command.toTicketJson().toString(), 100L - n)
    }
    private fun resolve(vararg entries: OutboxEntity, selectedScope: TicketOwnerScope? = scope) =
        resolveOutingPrecondition(outing, entries.toList(), selectedScope)

    @Test fun unchangedOwnerPlanKeepsExactMicrosecondBaseline() {
        assertEquals(OutingPrecondition.Literal(revision), resolve())
    }

    @Test fun createTicketEditChainUsesLastGuardedOperationDespiteBackwardClock() {
        val create = command(1, create = true)
        val ticket = ticket(2, create.id)
        val edit = command(3, ticket.id)
        assertEquals(OutingPrecondition.Operation(create.id), resolve(create))
        assertEquals(OutingPrecondition.Operation(ticket.id), resolve(create, ticket))
        assertEquals(OutingPrecondition.Operation(edit.id), resolve(create, ticket, edit))
    }

    @Test fun guardedTicketAfterAcknowledgedPredecessorRemainsValidAcrossRestart() {
        val original = ticket(2, id(1))
        val reopened = original.copy(payloadJson = JSONObject(original.payloadJson).toString())
        assertEquals(OutingPrecondition.Operation(original.id), resolve(reopened))
    }

    @Test fun legacyTicketCannotAuthorizeUnrelatedPlanChanges() {
        assertTrue(resolve(ticket(1, guarded = false)) is OutingPrecondition.Review)
        assertTrue(resolve(ticket(1, guarded = false), command(2, id(1))) is OutingPrecondition.Review)
    }

    @Test fun missingOrDifferentOwnerAndProjectFailClosed() {
        assertTrue(resolve(ticket(1), selectedScope = null) is OutingPrecondition.Review)
        assertTrue(resolve(ticket(1, ticketScope = scope.copy(ownerId = id(9)))) is OutingPrecondition.Review)
        assertTrue(resolve(ticket(1, ticketScope = scope.copy(projectId = "https://other.example"))) is OutingPrecondition.Review)
    }

    @Test fun malformedTicketAndIdentityMismatchAreNotCausalWitnesses() {
        val saved = ticket(1)
        assertTrue(resolve(saved.copy(payloadJson = "{}")) is OutingPrecondition.Review)
        assertTrue(resolve(saved.copy(id = id(2))) is OutingPrecondition.Review)
    }

    @Test fun laterValidCommandCannotHideReviewOrUnrelatedBaseline() {
        val review = command(1).copy(operation = "review")
        assertTrue(resolve(review, command(2, review.id)) is OutingPrecondition.Review)
        assertTrue(resolve(command(1), ticket(2)) is OutingPrecondition.Review)
        assertTrue(resolve(ticket(1), command(2, id(9))) is OutingPrecondition.Review)
    }

    @Test fun pendingCompletionAndTypedAwaitingIntentRequireDedicatedConversion() {
        assertTrue(resolve(command(1).copy(entityType = "outing_completion", operation = OUTING_COMPLETION)) is OutingPrecondition.Review)
        assertTrue(resolve(command(1).copy(operation = AWAITING_COMPLETION)) is OutingPrecondition.Review)
    }

    @Test fun unrelatedRowsDoNotChangeBaselineOrHideRelevantPredecessor() {
        val other = ticket(1).copy(entityId = id(99))
        assertEquals(OutingPrecondition.Literal(revision), resolve(other))
        val relevant = command(2)
        assertEquals(OutingPrecondition.Operation(relevant.id), resolve(other, relevant))
    }
}
