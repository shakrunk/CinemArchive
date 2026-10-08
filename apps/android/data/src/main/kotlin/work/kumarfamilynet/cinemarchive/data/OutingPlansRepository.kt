package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingDao
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.OutingStatus
import work.kumarfamilynet.cinemarchive.core.model.seating

/** Deliberately cannot carry ticket images/barcodes, prices, booking references or private notes. */
data class PublicOutingPlan(
    val outingId: String,
    val title: String,
    val showtime: String,
    val endsAt: String,
    val venue: String?,
    val format: CinemaFormat?,
    val seat: String?,
    val companions: List<String>,
)

data class OutingPlanSnapshot(val plan: PublicOutingPlan, val scheduled: Boolean, val pending: Boolean)
class OutingPlanUnavailableException(message: String) : IllegalStateException(message)

/** Reads the plan and its queue state in one local transaction, without holding it over network IO. */
suspend fun readOutingPlanSnapshot(
    outingId: String, outingDao: CinemaOutingDao, titleDao: TitleDao, outbox: MutationOutbox,
): OutingPlanSnapshot? = outbox.atomically {
    val outing = outingDao.getById(outingId)?.toDomain() ?: return@atomically null
    val title = titleDao.getById(outing.titleId) ?: return@atomically null
    val pending = outbox.pendingEntityKeys()
    OutingPlanSnapshot(
        PublicOutingPlan(outing.id, title.title, outing.showtime, outing.endsAt, outing.venue,
            outing.format, outing.seating.short, outing.companions),
        outing.status == OutingStatus.SCHEDULED,
        "cinema_outing:$outingId" in pending || "title:${title.id}" in pending,
    )
}

interface OutingPlansSource {
    fun isActive(): Boolean
    suspend fun load(outingId: String): PublicOutingPlan
    suspend fun friends(): List<Friendship>
    suspend fun send(outingId: String, recipientId: String, operationId: String): PublicOutingPlan
}

class OutingPlansRepository(
    private val ownerId: String,
    private val sessionProvider: () -> SupabaseSession?,
    private val snapshot: suspend (String) -> OutingPlanSnapshot?,
    private val listFriends: suspend () -> List<Friendship>,
    private val share: suspend (String, String, String, String) -> PublicOutingPlan,
    private val now: () -> Instant = Instant::now,
) : OutingPlansSource {
    override fun isActive(): Boolean = sessionProvider()?.userId == ownerId
    private fun session(): SupabaseSession {
        return sessionProvider()?.takeIf { it.userId == ownerId } ?: run {
            error("Account changed. Reopen the outing from your library.")
        }
    }

    override suspend fun load(outingId: String): PublicOutingPlan {
        session()
        val fresh = snapshot(outingId) ?: throw OutingPlanUnavailableException("This outing is no longer available.")
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        session()
        if (!fresh.scheduled || !Instant.parse(fresh.plan.endsAt).isAfter(now())) throw OutingPlanUnavailableException(
            "Only a scheduled outing can be shared. Reopen this title to see its current status.")
        if (fresh.pending) throw OutingPlanUnavailableException(
            "These plans are still syncing. Connect to the internet, refresh your library, then retry sharing.")
        return fresh.plan
    }

    override suspend fun friends(): List<Friendship> {
        session()
        val rows = listFriends()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        session()
        return rows.filter { it.relationFor(ownerId) == FriendshipRelation.FRIENDS && it.friendUserId != ownerId }
    }

    override suspend fun send(outingId: String, recipientId: String, operationId: String): PublicOutingPlan {
        java.util.UUID.fromString(operationId)
        check(friends().any { it.friendUserId == recipientId }) { "Plans can only be shared with an accepted friend." }
        // Re-read after the friend request: the owner may have edited/cancelled the outing while it loaded.
        load(outingId)
        val current = session()
        val deliveredPlan = share(current.accessToken, outingId, recipientId, operationId)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        session()
        return deliveredPlan
    }

    companion object {
        const val UNKNOWN_DELIVERY = "Delivery could not be confirmed. Retry this attempt to confirm delivery without notifying your friend twice."
        fun create(
            client: SupabaseRestClient, ownerId: String, sessionProvider: () -> SupabaseSession?,
            snapshot: suspend (String) -> OutingPlanSnapshot?, friendsRepository: FriendsRepository,
        ) = OutingPlansRepository(ownerId, sessionProvider, snapshot, friendsRepository::listFriendships,
            share = { _, outingId, recipientId, operationId ->
                withContext(Dispatchers.IO) {
                    try {
                        // Dispatch can suspend: check the fenced identity again on the IO thread.
                        val active = sessionProvider()?.takeIf { it.userId == ownerId }
                            ?: error("Account changed. Reopen the outing from your library.")
                        val response = client.rpc("share_outing_plans", JSONObject()
                            .put("p_outing_id", outingId)
                            .put("p_operation_id", operationId)
                            .put("p_recipient_ids", JSONArray().put(recipientId)).toString(), active.accessToken)
                        try { parseSharedOutingReceipt(outingId, response) }
                        catch (e: Exception) { throw IllegalStateException(UNKNOWN_DELIVERY, e) }
                    } catch (e: SupabaseHttpException) {
                        throw IllegalStateException(if (e.status >= 500) UNKNOWN_DELIVERY else FriendsRules.errorMessage(e, "Could not share these plans."))
                    } catch (e: java.io.IOException) {
                        throw IllegalStateException(UNKNOWN_DELIVERY, e)
                    }
                }
            })
    }
}

/** The receipt is the actual delivered snapshot, including on an idempotent retry. */
internal fun parseSharedOutingReceipt(outingId: String, response: String): PublicOutingPlan {
    val row = JSONObject(response)
    val companions = row.getJSONArray("companions")
    val names = (0 until companions.length()).map { i -> companions.get(i).also { require(it is String) } as String }
    val format = row.optStringOrNull("format")?.let { CinemaFormat.fromWire(it) ?: CinemaFormat.OTHER }
    val showtime = row.getString("showtime").also { Instant.parse(it) }
    val endsAt = row.getString("ends_at").also { Instant.parse(it) }
    return PublicOutingPlan(outingId, row.getString("title"), showtime, endsAt,
        row.optStringOrNull("venue"), format, row.optStringOrNull("seat"), names)
}
