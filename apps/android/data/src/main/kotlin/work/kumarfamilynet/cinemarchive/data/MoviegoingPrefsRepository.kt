package work.kumarfamilynet.cinemarchive.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.BudgetPeriod
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingBudget
import java.time.Instant

/**
 * A validated `moviegoing_prefs.prefs` payload (docs/android-contracts/moviegoing-prefs.md).
 * [json] is the whole payload, kept so keys this client doesn't know (written by a newer one)
 * survive a local edit and the push that follows it.
 */
class SyncedMoviegoingPrefs(val budget: MoviegoingBudget?, val updatedAt: Instant, val json: JSONObject)

/** Rejects the whole payload on a missing/unparseable `updatedAt` or an invalid `budget`
 *  rather than half-applying it. An absent or null `budget` is valid: it means "no budget". */
fun parseMoviegoingPrefs(json: JSONObject): SyncedMoviegoingPrefs? {
    val updatedAt = (json.opt("updatedAt") as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val raw = json.opt("budget")
    val budget = if (raw == null || raw == JSONObject.NULL) null else parseBudget(raw) ?: return null
    return SyncedMoviegoingPrefs(budget, updatedAt, json)
}

private fun parseBudget(value: Any): MoviegoingBudget? {
    val obj = value as? JSONObject ?: return null
    val amount = (obj.opt("amount") as? Number)?.toDouble() ?: return null
    if (!MoviegoingBudget.isValidAmount(amount)) return null
    val period = BudgetPeriod.fromWire(obj.opt("period") as? String) ?: return null
    return MoviegoingBudget(amount, period)
}

/** A copy of [base] (or an empty payload) with [budget] set — or explicitly nulled when
 *  clearing, so the clear carries a fresh [updatedAt] and propagates — and every other key kept. */
fun moviegoingPrefsWithBudget(base: JSONObject?, budget: MoviegoingBudget?, updatedAt: String): JSONObject {
    val next = JSONObject((base ?: JSONObject()).toString())
    next.put(
        "budget",
        budget?.let { JSONObject().put("amount", it.amount).put("period", it.period.wire) } ?: JSONObject.NULL,
    )
    return next.put("updatedAt", updatedAt)
}

/** [MoviegoingPrefsRepository.reconcile]'s pure merge decision, split out for unit testing. */
sealed interface MoviegoingPrefsReconciliation {
    data object Keep : MoviegoingPrefsReconciliation
    data object PushLocal : MoviegoingPrefsReconciliation
    data class ApplyRemote(val prefs: SyncedMoviegoingPrefs) : MoviegoingPrefsReconciliation
}

/**
 * [remoteJson] is the fetched `prefs` object, or null when there is no row or it is still the
 * empty default. Last write wins on `updatedAt`; a present-but-invalid remote copy is left
 * alone (neither applied nor overwritten), as is an unparseable local one.
 */
fun resolveMoviegoingPrefsReconciliation(remoteJson: JSONObject?, local: SyncedMoviegoingPrefs?): MoviegoingPrefsReconciliation {
    val remote = remoteJson?.let(::parseMoviegoingPrefs)
    if (remoteJson != null && remote == null) return MoviegoingPrefsReconciliation.Keep
    return when {
        remote == null -> if (local != null) MoviegoingPrefsReconciliation.PushLocal else MoviegoingPrefsReconciliation.Keep
        local == null || remote.updatedAt.isAfter(local.updatedAt) -> MoviegoingPrefsReconciliation.ApplyRemote(remote)
        local.updatedAt.isAfter(remote.updatedAt) -> MoviegoingPrefsReconciliation.PushLocal
        else -> MoviegoingPrefsReconciliation.Keep
    }
}

/**
 * The signed-in account's owner-private moviegoing settings. The local DataStore copy is the
 * source of truth for reads ([observeBudget] never hits the network); [setBudget] writes it
 * first, then pushes best-effort, and [reconcile] resolves any difference last-write-wins. A
 * failed fetch, a missing table or an invalid payload leaves both sides untouched, and a push
 * that failed offline is retried by the next reconcile.
 */
class MoviegoingPrefsRepository(
    private val dataStore: DataStore<Preferences>,
    private val session: SessionSource,
    private val client: SupabaseRestClient,
    private val isCurrent: () -> Boolean,
    private val now: () -> Instant = Instant::now,
) {
    private val prefsKey = stringPreferencesKey("moviegoing_prefs")

    fun observeBudget(): Flow<MoviegoingBudget?> = dataStore.data.map { readLocal(it)?.budget }.distinctUntilChanged()

    /** Sets, replaces or (with null) clears the budget. An invalid amount is rejected. */
    suspend fun setBudget(budget: MoviegoingBudget?) {
        require(budget == null || MoviegoingBudget.isValidAmount(budget.amount)) { "Invalid budget amount" }
        var next: JSONObject? = null
        dataStore.edit { stored ->
            val updated = moviegoingPrefsWithBudget(readLocal(stored)?.json, budget, now().toString())
            next = updated
            stored[prefsKey] = updated.toString()
        }
        push(next ?: return)
    }

    /** Call on sign-in/launch. */
    suspend fun reconcile() {
        val active = session.currentSession() ?: return
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val rows = JSONArray(client.get("moviegoing_prefs", "user_id=eq.${active.userId}&select=prefs", active.accessToken))
                rows.optJSONObject(0)?.optJSONObject("prefs")?.takeIf { it.length() > 0 }
            }
        }
        if (fetched.isFailure || !isCurrent()) return
        val local = readLocal(dataStore.data.first())
        when (val decision = resolveMoviegoingPrefsReconciliation(fetched.getOrNull(), local)) {
            MoviegoingPrefsReconciliation.Keep -> Unit
            MoviegoingPrefsReconciliation.PushLocal -> local?.let { push(it.json) }
            is MoviegoingPrefsReconciliation.ApplyRemote -> dataStore.edit { it[prefsKey] = decision.prefs.json.toString() }
        }
    }

    private fun readLocal(stored: Preferences): SyncedMoviegoingPrefs? =
        stored[prefsKey]?.let { runCatching { JSONObject(it) }.getOrNull() }?.let(::parseMoviegoingPrefs)

    private suspend fun push(prefs: JSONObject) {
        val active = session.currentSession() ?: return
        val body = JSONObject().put("user_id", active.userId).put("prefs", prefs)
        withContext(Dispatchers.IO) {
            runCatching { client.upsert("moviegoing_prefs", active.accessToken, body.toString(), onConflict = "user_id") }
        }
    }
}
