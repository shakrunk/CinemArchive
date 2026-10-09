package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

data class SavedTitleChange(val titleId: String, val title: String, val count: Int, val needsReview: Boolean, val error: String?)
data class TitleMetadataValues(val tags: List<String>, val status: String, val rating: Double?,
    val watchUrl: String? = null, val homeCollection: Boolean? = null, val physicalCopies: List<String> = emptyList())

/** The displayed revision and exact queue snapshot are retained until the user acts. */
class TitleMetadataComparison internal constructor(
    val titleId: String,
    val title: String,
    val saved: TitleMetadataValues,
    val current: TitleMetadataValues?,
    internal val entries: List<Pair<String, String>>,
    internal val patchJson: String,
    internal val currentJson: String?,
) {
    val changeCount: Int get() = entries.size
    val fields: Set<String> get() = JSONObject(patchJson).keys().asSequence().toSet()
}

interface TitleMetadataRecoverySource {
    val changes: Flow<List<SavedTitleChange>>
    suspend fun compare(titleId: String): TitleMetadataComparison
    suspend fun applySaved(comparison: TitleMetadataComparison)
    suspend fun discard(comparison: TitleMetadataComparison)
    suspend fun retrySync()
}

internal fun JSONObject.titleMetadataValues() = TitleMetadataValues(
    getJSONArray("tags").let { array -> (0 until array.length()).map(array::getString) },
    getString("status"), if (isNull("rating")) null else getDouble("rating"),
    if (isNull("custom_watch_url")) null else getString("custom_watch_url"),
    if (isNull("in_home_collection")) null else getBoolean("in_home_collection"),
    if (isNull("physical_media")) emptyList() else physicalMediaItems(metadataJson(getJSONArray("physical_media"))).map {
        listOfNotNull(it.format, it.edition, it.notes).joinToString(" · ")
    },
)
