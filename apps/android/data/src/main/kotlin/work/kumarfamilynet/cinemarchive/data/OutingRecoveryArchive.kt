package work.kumarfamilynet.cinemarchive.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** Owner-scoped durable originals and attempts. This store never contains credentials. */
interface OutingRecoveryArchive {
    val records: Flow<Map<String, String>>
    suspend fun put(id: String, record: String)
}

class DataStoreOutingRecoveryArchive(private val store: DataStore<Preferences>) : OutingRecoveryArchive {
    override val records = store.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith(PREFIX) && value is String) key.name.removePrefix(PREFIX) to value else null
        }.toMap()
    }
    override suspend fun put(id: String, record: String) { store.edit { it[stringPreferencesKey(PREFIX + id)] = record } }
    private companion object { const val PREFIX = "outing_review_" }
}

internal suspend fun OutingRecoveryArchive.record(id: String): JSONObject? = records.first()[id]?.let(::JSONObject)
internal fun OutboxEntity.originalRecord(): JSONObject = JSONObject()
    .put("version", 1).put("state", "pending")
    .put("original", JSONObject().put("id", id).put("entityType", entityType).put("entityId", entityId)
        .put("operation", operation).put("payloadJson", payloadJson).put("createdAt", createdAt)
        .put("attemptCount", attemptCount).put("lastError", lastError ?: JSONObject.NULL))
