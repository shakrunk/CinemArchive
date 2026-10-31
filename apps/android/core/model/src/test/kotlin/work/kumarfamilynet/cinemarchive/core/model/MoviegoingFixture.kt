package work.kumarfamilynet.cinemarchive.core.model

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** The cross-client cases in docs/android-contracts/fixtures/moviegoing.json, which the web
 *  client's moviegoingRules.test.ts and moviegoingPrefsSync.test.ts load too. */
internal object MoviegoingFixture {
    val root: JSONObject = JSONObject(File("../../../../docs/android-contracts/fixtures/moviegoing.json").readText())

    fun cases(key: String): List<JSONObject> = root.getJSONArray(key).objects()
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

/** A string field, or null when the fixture holds JSON null. */
internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

internal fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)

internal fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
