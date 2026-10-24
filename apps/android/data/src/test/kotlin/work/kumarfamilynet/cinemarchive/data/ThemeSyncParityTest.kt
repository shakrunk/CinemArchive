package work.kumarfamilynet.cinemarchive.data

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.model.ArchivePalette
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode
import work.kumarfamilynet.cinemarchive.core.model.SyncedAppearance
import work.kumarfamilynet.cinemarchive.core.model.applySyncedAppearance

/** Same fixture as the web's themeSync.test.ts (docs/android-contracts/theme-sync.md). */
@RunWith(RobolectricTestRunner::class)
class ThemeSyncParityTest {
    private val fixture = JSONObject(File("../../../docs/android-contracts/fixtures/theme-sync.json").readText())

    private fun JSONObject.appearance() = SyncedAppearance(
        palette = ArchivePalette.valueOf(getString("palette")),
        mode = ArchiveThemeMode.valueOf(getString("mode")),
        pureBlack = optBoolean("pureBlack"),
    )

    @Test fun encodesAndroidAppearance() {
        val cases = fixture.getJSONArray("encode")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val android = case.optJSONObject("android") ?: continue
            val encoded = android.appearance().toThemePrefsJson("2026-10-24T10:00:00.000Z")
            val expected = case.getJSONObject("expected")
            assertEquals(case.getString("name"), expected.getString("palette"), encoded.getString("palette"))
            assertEquals(case.getString("name"), expected.getString("mode"), encoded.getString("mode"))
            assertEquals(case.getString("name"), expected.getBoolean("pureBlack"), encoded.getBoolean("pureBlack"))
        }
    }

    @Test fun appliesRemoteAppearance() {
        val cases = fixture.getJSONArray("apply")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val remote = parseSyncedThemePrefs(case.getJSONObject("remote"))
            assertNotNull(case.getString("name"), remote)
            val android = case.getJSONObject("android")
            val current = android.getJSONObject("current")
            val unlockedJson = current.getJSONArray("unlocked")
            val unlocked = (0 until unlockedJson.length()).map { ArchivePalette.valueOf(unlockedJson.getString(it)) }.toSet()
            val actual = applySyncedAppearance(remote!!.appearance, current.appearance(), unlocked)
            val expected = android.getJSONObject("expected").appearance()
            assertEquals(case.getString("name"), expected, actual)
        }
    }

    @Test fun rejectsInvalidPayloads() {
        val cases = fixture.getJSONArray("invalid")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val remote = case.opt("remote")
            val parsed = (remote as? JSONObject)?.let(::parseSyncedThemePrefs)
            assertNull(case.getString("name"), parsed)
        }
    }
}
