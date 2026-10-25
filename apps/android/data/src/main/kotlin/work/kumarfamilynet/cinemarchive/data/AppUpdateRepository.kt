package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.InstallSource
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult
import work.kumarfamilynet.cinemarchive.core.model.compareVersions

/** The repo's latest published (non-draft, non-prerelease) release. */
private const val LATEST_RELEASE_URL =
    "https://api.github.com/repos/shakrunk/CinemArchive/releases/latest"

private const val RELEASES_PAGE_URL =
    "https://github.com/shakrunk/CinemArchive/releases/latest"

/**
 * Update checks for sideloaded installs, against the repo's GitHub Releases.
 *
 * Play-installed builds are Play's to update, so [checkForUpdate] reports up-to-date for them
 * rather than offering a GitHub APK that would fight the store's own copy.
 */
class AppUpdateRepository(
    private val installSourceProvider: InstallSourceProvider,
    private val currentVersionName: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    /** Stand-in for the GitHub endpoint, for exercising updates on a device (debug builds only). */
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
) {
    val installSource: InstallSource get() = installSourceProvider.source

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (installSource == InstallSource.PLAY_STORE) {
            return@withContext UpdateCheckResult.UpToDate(currentVersionName)
        }
        runCatching { fetchLatestRelease() }.fold(
            onSuccess = { it },
            onFailure = { e ->
                UpdateCheckResult.Failed(e.userMessage("Couldn't reach GitHub to check for updates"))
            },
        )
    }

    private fun fetchLatestRelease(): UpdateCheckResult {
        val request = Request.Builder()
            .url(latestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // A repo with no published release yet 404s — that isn't a failure to report,
                // it just means there is nothing newer to install.
                if (response.code == 404) return UpdateCheckResult.UpToDate(currentVersionName)
                return UpdateCheckResult.Failed("GitHub returned ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return UpdateCheckResult.Failed("Empty response from GitHub")
            return parseLatestRelease(body, currentVersionName)
        }
    }
}

/**
 * Maps a `releases/latest` response body to a check result. Kept apart from the HTTP call so
 * the mapping can be tested against fixture JSON without a network.
 */
internal fun parseLatestRelease(body: String, currentVersionName: String): UpdateCheckResult {
    val json = JSONObject(body)
    val tag = json.optString("tag_name").takeIf { it.isNotBlank() }
        ?: return UpdateCheckResult.Failed("Release has no tag")
    val latest = tag.removePrefix("v")

    if (compareVersions(latest, currentVersionName) <= 0) {
        return UpdateCheckResult.UpToDate(currentVersionName)
    }

    val assets = json.optJSONArray("assets")
    val apk = assets.firstAsset { it.optString("name").endsWith(".apk", ignoreCase = true) }
    val checksums = assets.firstAsset { it.optString("name") == CHECKSUMS_ASSET_NAME }

    return UpdateCheckResult.Available(
        currentVersion = currentVersionName,
        latestVersion = latest,
        apkUrl = apk?.downloadUrl(),
        releasePageUrl = json.optString("html_url").takeIf { it.isNotBlank() }
            ?: RELEASES_PAGE_URL,
        // optString maps a JSON null to the string "null", so a missing body is checked first.
        releaseNotes = if (json.isNull("body")) "" else json.optString("body").trim(),
        publishedAt = json.optString("published_at").takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() },
        apkSizeBytes = apk?.optLong("size")?.takeIf { it > 0 },
        checksumUrl = checksums?.downloadUrl(),
    )
}

/** The checksum file `deploy.yml` attaches next to each release APK. */
private const val CHECKSUMS_ASSET_NAME = "SHA256SUMS"

private fun JSONArray?.firstAsset(predicate: (JSONObject) -> Boolean): JSONObject? {
    if (this == null) return null
    for (i in 0 until length()) {
        val asset = optJSONObject(i) ?: continue
        if (predicate(asset)) return asset
    }
    return null
}

private fun JSONObject.downloadUrl(): String? =
    optString("browser_download_url").takeIf { it.isNotBlank() }
