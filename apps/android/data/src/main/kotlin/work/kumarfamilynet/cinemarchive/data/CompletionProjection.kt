package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.time.LocalDate
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity

internal fun checkedCompletionEnvelope(entry: OutboxEntity, envelope: JSONObject, ownerId: String): CompletionAcknowledgment {
    val checked = checkedCompletionResponse(entry, envelope.getJSONObject("receipt"), ownerId)
    require(checked.status in setOf("applied", "already_completed"))
    val alias = requireNotNull(checked.alias) { "Completion identity requires review." }
    require(envelope.has("currentViewing"))
    val row = if (envelope.isNull("currentViewing")) null else envelope.getJSONObject("currentViewing").also {
        require(it.getString("id") == alias.canonicalViewingId && it.getString("user_id") == ownerId &&
            it.getString("title_id") == alias.titleId)
        require(it.has("outing_id") && (it.isNull("outing_id") || it.getString("outing_id") == alias.outingId))
        it.toCompletionViewing()
    }
    return checked.copy(viewing = row)
}

internal fun JSONObject.toCompletionViewing(): ViewingEntity {
    require(setOf("id", "title_id", "user_id", "viewed_at", "rating", "notes", "venue", "companions", "outing_id", "updated_at").all(::has))
    fun text(key: String): String? = if (isNull(key)) null else getString(key)
    val date = text("viewed_at")?.also(LocalDate::parse)
    val rating = if (isNull("rating")) null else getDouble("rating").also { require(it.isFinite() && it in 0.0..5.0) }
    val companions = getJSONArray("companions").let { values -> (0 until values.length()).map { index ->
        when (val value = values.get(index)) {
            is String -> value
            is JSONObject -> value.getString("name")
            else -> error("Invalid viewing companion.")
        }
    } }
    return ViewingEntity(getString("id"), getString("title_id"), date, rating, text("notes"), text("venue"),
        companions, text("outing_id"), getString("updated_at").also(Instant::parse), getJSONArray("companions").toString())
}
