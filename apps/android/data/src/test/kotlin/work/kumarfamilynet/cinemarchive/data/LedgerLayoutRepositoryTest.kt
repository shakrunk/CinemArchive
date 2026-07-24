package work.kumarfamilynet.cinemarchive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetConfig
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetId
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetWidth

/** Unit tests for [resolveLayoutReconciliation] — ledger.md §4's pull-on-sign-in/launch rule
 *  as a pure decision, split out from [LedgerLayoutRepository.reconcile] specifically so it's
 *  testable without a [android.content.Context]/DataStore, matching [MutationOutboxTest]'s
 *  scripted-inputs style. */
class LedgerLayoutRepositoryTest {

    private val localWidgets = listOf(
        LedgerWidgetConfig(id = "widget-decades-0", panel = LedgerWidgetId.DECADES, width = LedgerWidgetWidth.SM),
    )

    @Test
    fun `no server row pushes the current local layout`() {
        val decision = resolveLayoutReconciliation(serverLayoutJson = null, localWidgets = localWidgets)

        assertTrue(decision is LayoutReconciliation.PushLocal)
        assertEquals(localWidgets, (decision as LayoutReconciliation.PushLocal).widgets)
    }

    @Test
    fun `a non-null server layout always overwrites local`() {
        val serverJson = """[{"id":"widget-genres-0","panel":"genres","width":"full"}]"""

        val decision = resolveLayoutReconciliation(serverJson, localWidgets)

        assertTrue(decision is LayoutReconciliation.OverwriteLocal)
        val overwritten = (decision as LayoutReconciliation.OverwriteLocal).widgets
        assertEquals(1, overwritten.size)
        assertEquals(LedgerWidgetId.GENRES, overwritten.single().panel)
    }

    @Test
    fun `server settings round-trip through normalization`() {
        val serverJson =
            """[{"id":"widget-run-0","panel":"run","width":"md","settings":{"timeRange":"12mo","scope":"movies","topN":6}}]"""

        val decision = resolveLayoutReconciliation(serverJson, localWidgets)

        val overwritten = (decision as LayoutReconciliation.OverwriteLocal).widgets.single()
        assertEquals("12mo", overwritten.settings?.timeRange)
        assertEquals("movies", overwritten.settings?.scope)
        assertEquals(6, overwritten.settings?.topN)
    }

    @Test
    fun `malformed server json falls back to pushing local`() {
        val decision = resolveLayoutReconciliation(serverLayoutJson = "not json", localWidgets = localWidgets)

        assertTrue(decision is LayoutReconciliation.PushLocal)
        assertEquals(localWidgets, (decision as LayoutReconciliation.PushLocal).widgets)
    }

    @Test
    fun `server json that normalizes to nothing falls back to pushing local`() {
        // Every panel id here is unknown to LedgerWidgetId — normalize() drops all of them,
        // which must be treated the same as "server has no usable layout," not as "server
        // wants an empty board."
        val serverJson = """[{"id":"widget-unknown-0","panel":"totally-unknown-panel","width":"full"}]"""

        val decision = resolveLayoutReconciliation(serverJson, localWidgets)

        assertTrue(decision is LayoutReconciliation.PushLocal)
        assertEquals(localWidgets, (decision as LayoutReconciliation.PushLocal).widgets)
    }
}
