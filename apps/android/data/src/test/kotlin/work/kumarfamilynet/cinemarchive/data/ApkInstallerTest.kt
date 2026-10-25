package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult

@RunWith(RobolectricTestRunner::class)
class ApkInstallerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val updatesDir = File(context.cacheDir, "updates")

    // A ZIP local-file header followed by filler is all the installer's own checks look at;
    // reading the manifest is the injected inspector's job.
    private val apkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(4_096) { it.toByte() }
    private val apkSha = sha256(apkBytes)

    private val release = UpdateCheckResult.Available(
        currentVersion = "1.44.0",
        latestVersion = "1.45.0",
        apkUrl = "https://example.test/CinemArchive-v1.45.0.apk",
        releasePageUrl = "https://example.test/release",
        apkSizeBytes = apkBytes.size.toLong(),
        checksumUrl = "https://example.test/SHA256SUMS",
    )

    @After
    fun cleanUp() {
        updatesDir.deleteRecursively()
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun installer(
        apk: ByteArray = apkBytes,
        checksums: String = "$apkSha  CinemArchive-v1.45.0.apk\n",
        archive: ApkArchiveInfo? = ApkArchiveInfo(context.packageName, "1.45.0"),
        currentVersion: String = "1.44.0",
    ) = ApkInstaller(
        context = context,
        httpClient = fakeHttp(apk, checksums),
        currentVersionName = currentVersion,
        inspectArchive = { archive },
    )

    @Test
    fun `verified download is staged and ready`() = runBlocking {
        val installer = installer()

        assertTrue(installer.download(release).isSuccess)

        assertEquals(ApkInstallState.Ready("1.45.0"), installer.installState.value)
        assertTrue(File(updatesDir, "CinemArchive-v1.45.0.apk").isFile)
        assertFalse(File(updatesDir, "CinemArchive-v1.45.0.apk.part").exists())
    }

    @Test
    fun `checksum mismatch fails the download and leaves nothing staged`() = runBlocking {
        val installer = installer(checksums = "${"0".repeat(64)}  CinemArchive-v1.45.0.apk\n")

        assertTrue(installer.download(release).isFailure)

        val state = installer.installState.value as ApkInstallState.Failed
        assertEquals(ApkInstallState.Failed.Stage.DOWNLOAD, state.stage)
        assertTrue(updatesDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a body that isn't an APK is rejected`() = runBlocking {
        val installer = installer(apk = "<html>rate limited</html>".toByteArray(), checksums = "")

        assertTrue(installer.download(release.copy(checksumUrl = null)).isFailure)
        assertTrue(installer.installState.value is ApkInstallState.Failed)
    }

    @Test
    fun `an APK for another package is rejected`() = runBlocking {
        val installer = installer(archive = ApkArchiveInfo("com.example.other", "1.45.0"))

        assertTrue(installer.download(release).isFailure)
        assertTrue(updatesDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `an APK that isn't newer than the running build is rejected`() = runBlocking {
        val installer = installer(archive = ApkArchiveInfo(context.packageName, "1.44.0"))

        assertTrue(installer.download(release).isFailure)
    }

    @Test
    fun `a staged update is restored in a later process without downloading again`() = runBlocking {
        installer().download(release)

        // A fresh installer whose network would fail stands in for the next process start.
        val nextProcess = installer(apk = ByteArray(0), checksums = "")
        assertTrue(nextProcess.restoreIfStaged("1.45.0"))
        assertEquals(ApkInstallState.Ready("1.45.0"), nextProcess.installState.value)
    }

    @Test
    fun `a tampered staged file is discarded instead of restored`() = runBlocking {
        installer().download(release)
        File(updatesDir, "CinemArchive-v1.45.0.apk").appendBytes(byteArrayOf(1))

        assertFalse(installer().restoreIfStaged("1.45.0"))
        assertFalse(File(updatesDir, "CinemArchive-v1.45.0.apk").exists())
    }

    @Test
    fun `launching on the staged version deletes the staged APK`() = runBlocking {
        installer().download(release)

        assertFalse(installer(currentVersion = "1.44.0").reconcileAfterLaunch())
        assertTrue(installer(currentVersion = "1.45.0").reconcileAfterLaunch())

        assertFalse(File(updatesDir, "CinemArchive-v1.45.0.apk").exists())
    }

    @Test
    fun `an abandoned prompt offers the install again without forgetting the attempt`() = runBlocking {
        val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        val installer = installer()
        installer.download(release)
        // Stands in for install() having committed: it records the version before the prompt.
        prefs.edit().putString("pending_update_version", "1.45.0").commit()
        installer.onPromptLaunched()

        installer.resetIfPromptAbandoned()

        assertEquals(ApkInstallState.Ready("1.45.0"), installer.installState.value)
        // The user may still accept that prompt from recents; the next launch must know.
        assertEquals("1.45.0", prefs.getString("pending_update_version", null))
    }

    @Test
    fun `the abandon check does nothing before the prompt was launched`() = runBlocking {
        val installer = installer()
        installer.download(release)
        installer.publishInstallState(ApkInstallState.AwaitingConfirmation)

        installer.resetIfPromptAbandoned()

        assertEquals(ApkInstallState.AwaitingConfirmation, installer.installState.value)
    }

    @Test
    fun `just-updated version is reported once, and only when it matches the running build`() {
        val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

        prefs.edit().putString("pending_update_version", "1.45.0").commit()
        val updated = installer(currentVersion = "1.45.0")
        updated.reconcileAfterLaunch()
        assertEquals("1.45.0", updated.consumeJustUpdatedVersion())
        assertNull(updated.consumeJustUpdatedVersion())

        // The install never landed: still on the old build, so the attempt is forgotten.
        prefs.edit().putString("pending_update_version", "1.45.0").commit()
        val notUpdated = installer(currentVersion = "1.44.0")
        notUpdated.reconcileAfterLaunch()
        assertNull(notUpdated.consumeJustUpdatedVersion())
    }

    @Test
    fun `sha256sum output is matched by file name`() {
        val sums = """
            ${"a".repeat(64)}  CinemArchive-v1.44.0.apk
            ${"B".repeat(64)} *CinemArchive-v1.45.0.apk
        """.trimIndent()

        assertEquals("b".repeat(64), parseSha256Sums(sums, "CinemArchive-v1.45.0.apk"))
        assertNull(parseSha256Sums(sums, "CinemArchive-v1.46.0.apk"))
    }

    private fun fakeHttp(apk: ByteArray, checksums: String) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("SHA256SUMS")) {
                checksums.toResponseBody("text/plain".toMediaType())
            } else {
                apk.toResponseBody("application/vnd.android.package-archive".toMediaType())
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(body).build()
        })
        .build()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
