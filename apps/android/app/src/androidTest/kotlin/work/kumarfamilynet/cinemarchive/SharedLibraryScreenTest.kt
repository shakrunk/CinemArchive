package work.kumarfamilynet.cinemarchive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.data.*

@RunWith(AndroidJUnit4::class)
class SharedLibraryScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun repository(body: String): SharingRepository = SharingRepository(
        SupabaseRestClient("https://unused.invalid", "public-test-key"), { null },
        object : SharedLibraryTransport {
            override fun rpc(name: String, paramsJson: String): String {
                assertEquals("get_shared_library", name)
                return body
            }
        },
    )

    @Test fun anonymousViewerShowsFullDetailAndReadonlyLedger() {
        val response = """{
          "ownerUserId":"shared-owner","hasMore":false,
          "ledgerLayout":[{"id":"movies","panel":"moviegoing","width":"full","settings":{"title":"Shared cinema history"}}],
          "titles":[{"id":"film","user_id":"shared-owner","tmdb_id":1,"type":"movie","title":"Shared Film",
            "year":2020,"status":"watched","rating":4.5,"genres":["Drama"],"added_at":"2026-01-01T00:00:00Z",
            "notes":"Shared film notes","viewings":[{"id":"v","title_id":"film","user_id":"shared-owner","viewed_at":"2026-01-01","notes":"Shared viewing notes","venue":"Cinema"}]}]
        }"""
        var closed = false
        compose.setContent { CinemArchiveTheme { SharedLibraryRoute("synthetic", repository(response), { closed = true }) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Shared Film").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Read only").assertIsDisplayed()
        compose.onNodeWithText("Shared Film").performClick()
        compose.onNodeWithText("Shared film notes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Shared viewing notes").performScrollTo().assertIsDisplayed()
        listOf("Edit", "Log viewing", "Book an outing", "Delete", "Add to list").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("The Ledger").performClick()
        compose.onNodeWithText("Shared cinema history").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Edit").assertCountEquals(0)
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(closed) }
    }

    @Test fun validEmptySharedArchiveIsDistinctFromInvalidLink() {
        compose.setContent { CinemArchiveTheme {
            SharedLibraryRoute("empty", repository("""{"ownerUserId":"owner","titles":[],"ledgerLayout":null,"hasMore":false}"""), {})
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("No titles are shared by this link.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No titles are shared by this link.").assertIsDisplayed()
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
    }
}
