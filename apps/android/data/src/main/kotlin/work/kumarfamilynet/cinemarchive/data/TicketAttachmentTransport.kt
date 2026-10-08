package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketAttachment
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

/** Not routed to production until the ACK/recovery transaction and UI are integrated. */
internal class TicketAttachmentTransport(
    private val scope: TicketOwnerScope,
    private val remote: TicketAttachmentRemote,
    private val files: TicketAttachmentFiles,
    private val sessionProvider: () -> SupabaseSession?,
    private val isCurrentOwner: () -> Boolean,
) {
    init { checkedTicketScope(scope); require(remote.projectId == scope.projectId && files.scope == scope) }

    suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        val command = try { ticketCommand(entry).also { require(it.scope == scope) } }
        catch (_: Exception) { return@withContext PushResult.Review("Saved ticket intent is invalid or belongs to another account. Its original is retained for review.") }
        var accepted = false
        try {
            val session = sessionProvider() ?: error("Sign in to sync this ticket.")
            val coroutine = currentCoroutineContext()
            val current = { coroutine.ensureActive(); check(isCurrentOwner() && sessionProvider()?.userId == scope.ownerId && session.userId == scope.ownerId) { "Ticket account changed." } }
            fun rpc(name: String, args: JSONObject): String { current(); return remote.rpc(name, args.toString(), session.accessToken, current).also { current() } }
            val lookup = { rpc("get_ticket_command_receipt", JSONObject().put("p_operation_id", command.operationId)) }
            val existing = lookup().trim()
            val receipt = if (existing != "null") {
                accepted = true
                JSONObject(existing)
            } else {
                val attachment = command.attachment
                if (attachment != null) {
                    val metadata = attachment.toTicketJson().apply { remove("id"); remove("objectKey") }
                    val prepared = JSONObject(rpc("prepare_ticket_attachment", JSONObject().put("p_attachment_id", attachment.id)
                        .put("p_outing_id", command.outingId).put("p_metadata", metadata)))
                    require(ticketAttachmentFromJson(scope, prepared.getJSONObject("attachment")) == attachment)
                    when (prepared.ticketString("state")) {
                        "retired" -> {
                            val raced = lookup().trim()
                            if (raced == "null") return@withContext PushResult.Review("This ticket preparation was retired. Your photo remains available for recovery.")
                            accepted = true
                            return@withContext confirm(command, JSONObject(raced), session, current)
                        }
                        "prepared" -> {
                            current()
                            val original = files.read(attachment)
                            current()
                            try { remote.upload(attachment, original, session.accessToken, current) }
                            catch (error: SupabaseHttpException) {
                                if (!duplicateTicketObject(error)) throw error
                                current()
                                remote.download(attachment, session.accessToken, current) { input -> files.cache(attachment, input) }
                            }
                            current()
                        }
                        "attached" -> Unit
                        else -> error("Unrecognized ticket preparation state.")
                    }
                }
                val finalized = rpc(if (attachment == null) "detach_ticket_attachment" else "finalize_ticket_attachment", command.finalizeArgs())
                accepted = true
                JSONObject(finalized)
            }
            confirm(command, receipt, session, current)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: SupabaseHttpException) {
            if (!accepted && error.postgresCode in setOf("40001", "23505")) PushResult.Review("This outing or ticket changed. The pending intent and original photo are saved for review.")
            else PushResult.Retry(error.message ?: "Ticket sync was not confirmed. Retry the same saved intent.")
        } catch (error: Exception) { PushResult.Retry(error.message ?: "Ticket sync was not confirmed. Retry the same saved intent.") }
    }

    private fun confirm(command: TicketAttachmentCommand, receipt: JSONObject, session: SupabaseSession, current: () -> Unit): PushResult {
        current(); checkedTicketReceipt(command, receipt)
        val associations = JSONArray(remote.rpc("get_outing_ticket_attachments", "{}", session.accessToken, current))
        current(); ticketDescriptors(scope, associations)
        val rows = JSONArray(remote.outing(command.outingId, scope.ownerId, session.accessToken, current))
        current(); require(rows.length() <= 1)
        val envelope = JSONObject().put("receipt", receipt).put("associations", associations)
            .put("currentOuting", if (rows.length() == 0) JSONObject.NULL else rows.getJSONObject(0))
        checkedTicketEnvelope(command, envelope); current()
        return PushResult.Applied(envelope)
    }

    /** Old backends preserve cached managed projection; ACK always uses the strict path above. */
    suspend fun readDescriptors(): TicketDescriptorRead = withContext(Dispatchers.IO) {
        val session = sessionProvider() ?: error("Sign in to read private tickets.")
        val coroutine = currentCoroutineContext()
        val current = { coroutine.ensureActive(); check(isCurrentOwner() && session.userId == scope.ownerId && sessionProvider()?.userId == scope.ownerId) }
        try {
            current()
            val rows = JSONArray(remote.rpc("get_outing_ticket_attachments", "{}", session.accessToken, current))
            current(); TicketDescriptorRead(true, ticketDescriptors(scope, rows))
        } catch (error: SupabaseHttpException) {
            current()
            if (error.postgresCode == "PGRST202") TicketDescriptorRead(false, emptyList()) else throw error
        }
    }

    suspend fun download(attachment: TicketAttachment): StoredTicketOriginal = withContext(Dispatchers.IO) {
        checkedTicketAttachment(scope, attachment)
        val session = sessionProvider() ?: error("Sign in to download this ticket.")
        val coroutine = currentCoroutineContext()
        val current = { coroutine.ensureActive(); check(isCurrentOwner() && session.userId == scope.ownerId && sessionProvider()?.userId == scope.ownerId) }
        current()
        remote.download(attachment, session.accessToken, current) { input -> files.cache(attachment, input) }.also { current() }
    }
}

private fun duplicateTicketObject(error: SupabaseHttpException): Boolean {
    if (error.status == 409) return true
    val body = runCatching { JSONObject(error.responseBody) }.getOrNull() ?: return false
    return body.optString("error") == "Duplicate" || body.optString("code") == "Duplicate" || body.optString("message") == "The resource already exists"
}
