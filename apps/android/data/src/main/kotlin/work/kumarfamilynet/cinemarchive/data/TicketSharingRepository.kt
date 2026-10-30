package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.OpenedTicket
import work.kumarfamilynet.cinemarchive.core.model.ReceivedTicket
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcode
import work.kumarfamilynet.cinemarchive.core.model.TicketGrant
import work.kumarfamilynet.cinemarchive.core.model.TicketSendingRules

/**
 * Sending an outing's ticket to accepted friends (#220, docs/android-contracts/ticket-sending.md): the
 * owner sends, sees who was sent it and who opened it, and can take it back; a friend lists what was
 * sent to them, opens one, and downloads the file. Online-only, and every call is fenced to the
 * signed-in owner this repository was built for. The ticket itself is the owner's existing private
 * attachment; nothing is copied.
 */
class TicketSharingRepository(
    private val client: SupabaseRestClient,
    private val session: () -> SupabaseSession?,
    private val ownerId: String,
    private val isCurrent: () -> Boolean,
    private val listFriends: suspend () -> List<Friendship>,
) {
    /** Accepted friends a ticket can be sent to. */
    suspend fun friends(): List<Friendship> {
        token()
        val rows = listFriends()
        token()
        return rows.filter { it.relationFor(ownerId) == FriendshipRelation.FRIENDS && it.friendUserId != ownerId }
    }

    /** Sends the outing's current ticket to [recipientIds] and returns everyone it has now been sent to. */
    suspend fun send(outingId: String, recipientIds: List<String>): List<TicketGrant> = withContext(Dispatchers.IO) {
        require(recipientIds.isNotEmpty()) { "Choose at least one friend." }
        val token = token()
        val body = JSONObject().put("p_outing_id", checkedUuid(outingId))
            .put("p_recipient_ids", JSONArray(recipientIds.distinct().map(::checkedUuid))).toString()
        val reply = try { client.rpc("send_ticket", body, token) }
        catch (e: SupabaseHttpException) { throw IllegalStateException(FriendsRules.errorMessage(e, "Could not send the ticket."), e) }
        token()
        parseTicketGrants(reply)
    }

    suspend fun grants(outingId: String): List<TicketGrant> = withContext(Dispatchers.IO) {
        val token = token()
        parseTicketGrants(client.rpc("get_ticket_grants", JSONObject().put("p_outing_id", checkedUuid(outingId)).toString(), token)).also { token() }
    }

    /** Takes the ticket back from one friend; they lose access at once. */
    suspend fun revoke(grantId: String) = withContext(Dispatchers.IO) {
        val token = token()
        client.rpc("revoke_ticket_grant", JSONObject().put("p_grant_id", checkedUuid(grantId)).toString(), token)
        token()
        Unit
    }

    suspend fun received(): List<ReceivedTicket> = withContext(Dispatchers.IO) {
        val token = token()
        parseReceivedTickets(client.rpc("get_received_tickets", "{}", token)).also { token() }
    }

    /** Opens a received ticket: the first open is recorded and the sender is told. */
    suspend fun open(grantId: String): OpenedTicket = withContext(Dispatchers.IO) {
        val token = token()
        val reply = try { client.rpc("open_received_ticket", JSONObject().put("p_grant_id", checkedUuid(grantId)).toString(), token) }
        catch (e: SupabaseHttpException) { throw IllegalStateException(FriendsRules.errorMessage(e, "This ticket is no longer available."), e) }
        token()
        parseOpenedTicket(reply) ?: throw IllegalStateException("This ticket could not be read.")
    }

    /** The ticket file, checked against the size the server reported. */
    suspend fun download(ticket: OpenedTicket): ByteArray = withContext(Dispatchers.IO) {
        val token = token()
        val bytes = client.storageDownload(BUCKET, ticket.objectKey, token)
        token()
        require(bytes.size.toLong() == ticket.byteLength) { "The downloaded ticket has an unexpected size." }
        bytes
    }

    private fun token(): String {
        check(isCurrent()) { "This sign-in has ended." }
        val current = session()
        check(current != null && current.userId == ownerId) { "Sign in to send tickets to friends." }
        return current.accessToken
    }

    private companion object {
        const val BUCKET = "ticket-attachments"
    }
}

private val UUID_SHAPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun checkedUuid(value: String): String {
    require(UUID_SHAPE.matches(value)) { "Invalid id" }
    return value
}

private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun instantOrNull(value: String?): String? = value?.takeIf { runCatching { Instant.parse(it) }.isSuccess }

/** Rows with an unexpected shape are skipped, never shown. */
internal fun parseTicketGrants(body: String): List<TicketGrant> {
    val rows = JSONArray(body)
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        TicketGrant(
            grantId = row.optString("grant_id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null,
            userId = row.optString("user_id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null,
            name = row.stringOrNull("name") ?: "A friend",
            sentAt = row.optString("sent_at"),
            openedAt = row.stringOrNull("opened_at"),
        )
    }
}

internal fun parseReceivedTickets(body: String): List<ReceivedTicket> {
    val rows = JSONArray(body)
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        ReceivedTicket(
            grantId = row.optString("grant_id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null,
            senderId = row.optString("sender_id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null,
            senderName = row.stringOrNull("sender_name") ?: "A friend",
            title = row.stringOrNull("title") ?: return@mapNotNull null,
            year = if (row.isNull("year")) null else row.optInt("year").takeIf { it > 0 },
            posterUrl = row.stringOrNull("poster_url"),
            showtime = instantOrNull(row.optString("showtime")) ?: return@mapNotNull null,
            endsAt = instantOrNull(row.optString("ends_at")) ?: return@mapNotNull null,
            venue = row.stringOrNull("venue"),
            sentAt = row.optString("sent_at"),
            openedAt = row.stringOrNull("opened_at"),
        )
    }
}

internal fun parseOpenedTicket(body: String): OpenedTicket? {
    val row = JSONObject(body)
    val barcode = row.optJSONObject("barcode")?.let { code ->
        val payload = code.optString("payload").takeIf { it.isNotEmpty() } ?: return@let null
        TicketBarcode(payload, TicketSendingRules.parseFormat(code.optString("format")))
    }
    val mime = row.optString("mimeType").takeIf { it in setOf("image/jpeg", "image/png", "image/webp") } ?: return null
    val length = row.optLong("byteLength", -1).takeIf { it > 0 } ?: return null
    return OpenedTicket(
        objectKey = row.optString("objectKey").takeIf { it.isNotEmpty() && !it.contains("..") } ?: return null,
        mimeType = mime, byteLength = length, barcode = barcode,
        title = row.stringOrNull("title") ?: return null,
        showtime = instantOrNull(row.optString("showtime")) ?: return null,
        venue = row.stringOrNull("venue"),
    )
}
