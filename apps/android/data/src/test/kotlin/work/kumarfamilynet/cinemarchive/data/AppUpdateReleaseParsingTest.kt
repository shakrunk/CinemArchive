package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult

class AppUpdateReleaseParsingTest {

    private fun release(
        tag: String = "v1.45.0",
        body: String? = "\"## Added\\n- Update sheet\\n\"",
        assets: String = """
            [
              {"name": "CinemArchive-v1.45.0.apk", "size": 43600000,
               "browser_download_url": "https://example.test/CinemArchive-v1.45.0.apk"},
              {"name": "SHA256SUMS", "size": 90,
               "browser_download_url": "https://example.test/SHA256SUMS"}
            ]
        """,
    ) = """
        {
          "tag_name": "$tag",
          "html_url": "https://github.com/shakrunk/CinemArchive/releases/tag/$tag",
          "published_at": "2026-10-24T18:30:00Z",
          "body": ${body ?: "null"},
          "assets": $assets
        }
    """

    @Test
    fun `newer release maps every field the update sheet needs`() {
        val result = parseLatestRelease(release(), currentVersionName = "1.44.0")

        assertTrue(result is UpdateCheckResult.Available)
        result as UpdateCheckResult.Available
        assertEquals("1.44.0", result.currentVersion)
        assertEquals("1.45.0", result.latestVersion)
        assertEquals("https://example.test/CinemArchive-v1.45.0.apk", result.apkUrl)
        assertEquals("https://github.com/shakrunk/CinemArchive/releases/tag/v1.45.0", result.releasePageUrl)
        assertEquals("## Added\n- Update sheet", result.releaseNotes)
        assertEquals(Instant.parse("2026-10-24T18:30:00Z"), result.publishedAt)
        assertEquals(43_600_000L, result.apkSizeBytes)
        assertEquals("https://example.test/SHA256SUMS", result.checksumUrl)
    }

    @Test
    fun `same or older release is up to date`() {
        assertTrue(parseLatestRelease(release(tag = "v1.44.0"), "1.44.0") is UpdateCheckResult.UpToDate)
        assertTrue(parseLatestRelease(release(tag = "v1.9.0"), "1.10.0") is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `release whose APK is still uploading has no apk url or size`() {
        val result = parseLatestRelease(release(assets = "[]"), "1.44.0") as UpdateCheckResult.Available

        assertNull(result.apkUrl)
        assertNull(result.apkSizeBytes)
        assertNull(result.checksumUrl)
    }

    @Test
    fun `null release body becomes blank notes rather than the string null`() {
        val result = parseLatestRelease(release(body = null), "1.44.0") as UpdateCheckResult.Available

        assertEquals("", result.releaseNotes)
    }

    @Test
    fun `release without a tag is reported as a failure`() {
        val result = parseLatestRelease(release(tag = ""), "1.44.0")

        assertTrue(result is UpdateCheckResult.Failed)
    }
}
