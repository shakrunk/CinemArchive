package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.model.PhysicalMediaItem

val physicalCopyFormats = listOf("DVD", "Blu-ray", "4K UHD", "VHS", "LaserDisc", "Other")

data class TitleSourcesValues(val watchUrl: String, val homeCollection: Boolean, val copiesJson: String) {
    val copies: List<PhysicalMediaItem> get() = physicalMediaItems(copiesJson)
}

/** org.json's Android parser rounds opaque numbers; metadata edits must retain unknown fields. */
internal fun exactMetadataValue(raw: String): Any {
    val parsed = LibraryBackupCodec.parse("{\"titles\":[],\"value\":$raw}")
    require(parsed is LibraryBackupCodec.ParseResult.Success) { "Saved collection data could not be read safely." }
    return parsed.document.extra.get("value")
}
internal fun exactMetadataObject(raw: String): JSONObject = exactMetadataValue(raw) as JSONObject
internal fun exactMetadataArray(raw: String): JSONArray = exactMetadataValue(raw) as JSONArray
internal fun metadataJson(value: Any): String = LibraryBackupCodec.encodeJsonValue(value)

fun titleSourcesValues(opening: String): TitleSourcesValues {
    val values = exactMetadataObject(opening).getJSONObject("values")
    return TitleSourcesValues(if (values.isNull("custom_watch_url")) "" else values.getString("custom_watch_url"),
        !values.isNull("in_home_collection") && values.getBoolean("in_home_collection"),
        if (values.isNull("physical_media")) "[]" else metadataJson(values.getJSONArray("physical_media")))
}

fun addPhysicalCopy(raw: String, format: String, edition: String): String {
    require(format in physicalCopyFormats)
    val row = JSONObject().put("id", UUID.randomUUID().toString()).put("format", format)
    edition.trim().takeIf { it.isNotEmpty() }?.let { row.put("edition", it) }
    return metadataJson(exactMetadataArray(raw).put(row))
}

fun removePhysicalCopy(raw: String, id: String): String {
    val before = exactMetadataArray(raw)
    return metadataJson(JSONArray().apply {
        for (i in 0 until before.length()) if (before.optJSONObject(i)?.opt("id") != id) put(before.get(i))
    })
}

internal fun validateMetadataText(value: Any?) {
    when (value) {
        is String -> checkedPreferenceText(value, Int.MAX_VALUE)
        is JSONObject -> value.keys().forEach { validateMetadataText(it); validateMetadataText(value.get(it)) }
        is JSONArray -> (0 until value.length()).forEach { validateMetadataText(value.get(it)) }
    }
}

/** Opening proof belongs to this owner and survives a form restore; saves never rebase it. */
internal class TitleSourcesEditing(private val titles: TitleDao, private val outbox: MutationOutbox,
    private val owner: String, private val current: () -> Boolean) {
    private fun checkOwner() = check(current()) { "This sign-in has ended. Reopen this title in the current account." }

    suspend fun capture(titleId: String): String = outbox.atomically {
        checkOwner()
        val title = checkNotNull(titles.getById(titleId)) { "This title was removed." }
        val prior = pendingTitleIntents(outbox.pendingEntries(), titleId).lastOrNull()
        val validPrior = prior?.takeIf { runCatching { checkedTitlePredecessor(it, owner) }.isSuccess }
        val values = JSONObject().put("custom_watch_url", title.customWatchUrl ?: JSONObject.NULL)
            .put("in_home_collection", title.inHomeCollection ?: JSONObject.NULL)
            .put("physical_media", title.physicalMediaJson?.let(::exactMetadataArray) ?: JSONObject.NULL)
        metadataJson(JSONObject().put("ownerId", owner).put("titleId", titleId).put("openingId", UUID.randomUUID().toString())
            .put("baseline", if (prior == null) title.updatedAt else JSONObject.NULL)
            .put("prior", validPrior?.id ?: JSONObject.NULL).put("values", values))
    }

    suspend fun save(titleId: String, opening: String, desired: TitleSourcesValues) = outbox.atomically {
        checkOwner()
        val captured = exactMetadataObject(opening)
        require(captured.getString("ownerId") == owner && captured.getString("titleId") == titleId)
        val original = titleSourcesValues(opening)
        val patch = JSONObject()
        val url = desired.watchUrl.trim()
        if (url != original.watchUrl) patch.put("custom_watch_url", url.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        if (desired.homeCollection != original.homeCollection) patch.put("in_home_collection", desired.homeCollection)
        val copies = exactMetadataArray(desired.copiesJson)
        if (!sameCommandJson(copies, exactMetadataArray(original.copiesJson))) patch.put("physical_media", copies)
        if (patch.length() > 0) {
            checkedTitlePatch(patch)
            val previous = checkNotNull(titles.getById(titleId)) { "This title was removed." }
            val baseline = if (captured.isNull("baseline")) null else captured.getString("baseline")
            val prior = if (captured.isNull("prior")) null else captured.getString("prior")
            val payload = titleMetadataPayload(owner, titleId, patch, baseline, prior)
            val bytes = metadataJson(payload)
            require(bytes.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024) { "This collection is too large to save in one change." }
            val id = UUID.nameUUIDFromBytes((captured.getString("openingId") + "\n" + bytes).toByteArray(Charsets.UTF_8)).toString()
            if (outbox.enqueueCaptured(id, "title", titleId, if (baseline == null && prior == null) "review" else TITLE_METADATA_COMMAND, bytes)) {
                titles.upsertAll(listOf(previous.withTitleMetadata(patch)))
            }
        }
        checkOwner()
    }
}
