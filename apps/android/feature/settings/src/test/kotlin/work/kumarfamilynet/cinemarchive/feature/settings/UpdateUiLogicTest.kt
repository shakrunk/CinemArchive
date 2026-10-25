package work.kumarfamilynet.cinemarchive.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult

class UpdateUiLogicTest {

    private val available = UpdateCheckResult.Available(
        currentVersion = "1.44.0",
        latestVersion = "1.45.0",
        apkUrl = "https://example.test/app.apk",
        releasePageUrl = "https://example.test/release",
    )

    @Test
    fun `banner shows for an available update the user hasn't dismissed`() {
        assertTrue(shouldShowUpdateBanner(available, ApkInstallState.Idle, dismissedVersion = null))
        assertTrue(shouldShowUpdateBanner(available, ApkInstallState.Idle, dismissedVersion = "1.44.0"))
    }

    @Test
    fun `dismissing hides the banner for that version only`() {
        assertFalse(shouldShowUpdateBanner(available, ApkInstallState.Idle, dismissedVersion = "1.45.0"))
    }

    @Test
    fun `an update under way shows even when dismissed`() {
        assertTrue(shouldShowUpdateBanner(available, ApkInstallState.Downloading(1, 10), "1.45.0"))
        assertTrue(shouldShowUpdateBanner(available, ApkInstallState.Ready("1.45.0"), "1.45.0"))
    }

    @Test
    fun `no banner without an available update`() {
        assertFalse(shouldShowUpdateBanner(UpdateCheckResult.UpToDate("1.45.0"), ApkInstallState.Idle, null))
        assertFalse(shouldShowUpdateBanner(UpdateCheckResult.Failed("offline"), ApkInstallState.Idle, null))
    }

    @Test
    fun `release notes split into headings, nested bullets and paragraphs`() {
        val blocks = parseReleaseNotes(
            """
            ## [1.45.0] - 2026-10-26

            ### Added
            - **Update sheet.** Shows what changed.
              - Nested detail
            Plain paragraph.
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                NotesBlock.Heading("[1.45.0] - 2026-10-26"),
                NotesBlock.Heading("Added"),
                NotesBlock.Bullet("**Update sheet.** Shows what changed.", depth = 0),
                NotesBlock.Bullet("Nested detail", depth = 1),
                NotesBlock.Paragraph("Plain paragraph."),
            ),
            blocks,
        )
    }

    @Test
    fun `inline markdown keeps the text and drops the markers`() {
        val text = inlineMarkdown(
            "See **bold**, `code` and [the release](https://example.test/r).",
            androidx.compose.ui.graphics.Color.Unspecified,
        )

        assertEquals("See bold, code and the release.", text.text)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
    }
}
