package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
) {
    val installSource: InstallSource get() = installSourceProvider.source

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (installSource == InstallSource.PLAY_STORE) {
            return@withContext UpdateCheckResult.UpToDate(currentVersionName)
        }
        runCatching { fetchLatestRelease() }.fold(
            onSuccess = { it },
            onFailure = { e ->
                UpdateCheckResult.Failed(e.message ?: "Couldn't reach GitHub to check for updates")
            },
        )
    }

    private fun fetchLatestRelease(): UpdateCheckResult {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
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

            val json = JSONObject(body)
            val tag = json.optString("tag_name").takeIf { it.isNotBlank() }
                ?: return UpdateCheckResult.Failed("Release has no tag")
            val latest = tag.removePrefix("v")

            if (compareVersions(latest, currentVersionName) <= 0) {
                return UpdateCheckResult.UpToDate(currentVersionName)
            }

            return UpdateCheckResult.Available(
                currentVersion = currentVersionName,
                latestVersion = latest,
                apkUrl = firstApkAsset(json),
                releasePageUrl = json.optString("html_url").takeIf { it.isNotBlank() }
                    ?: RELEASES_PAGE_URL,
            )
        }
    }

    private fun firstApkAsset(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return asset.optString("browser_download_url").takeIf { it.isNotBlank() }
            }
        }
        return null
    }
}
