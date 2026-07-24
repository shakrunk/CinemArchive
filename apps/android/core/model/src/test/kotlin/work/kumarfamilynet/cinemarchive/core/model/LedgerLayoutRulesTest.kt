package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerLayoutRulesTest {

    @Test
    fun `default layout has one widget per panel in canonical order`() {
        val widgets = LedgerLayoutRules.defaultLedgerWidgets()

        assertEquals(LedgerWidgetId.entries.size, widgets.size)
        assertEquals(LedgerWidgetId.entries, widgets.map { it.panel })
        assertTrue(widgets.all { it.width == LedgerWidgetWidth.FULL })
    }

    @Test
    fun `normalize drops widgets with an unknown panel`() {
        val raw = listOf(
            RawLedgerWidget(id = "widget-unknown-1", panel = "no-longer-exists", width = "lg", settings = null),
        )

        assertTrue(LedgerLayoutRules.normalize(raw).isEmpty())
    }

    @Test
    fun `normalize backfills an invalid width to full`() {
        val raw = listOf(
            RawLedgerWidget(id = "widget-bad-width-1", panel = "decades", width = "huge", settings = null),
        )

        val result = LedgerLayoutRules.normalize(raw)

        assertEquals(1, result.size)
        assertEquals(LedgerWidgetWidth.FULL, result.single().width)
    }

    @Test
    fun `normalize drops a missing width the same way as an invalid one`() {
        val raw = listOf(RawLedgerWidget(id = "widget-1", panel = "genres", width = null, settings = null))

        assertEquals(LedgerWidgetWidth.FULL, LedgerLayoutRules.normalize(raw).single().width)
    }

    @Test
    fun `normalize clamps topN into 3 to 12 and drops unrecognized settings keys`() {
        val raw = listOf(
            RawLedgerWidget(
                id = "widget-bad-settings-1",
                panel = "genres",
                width = "md",
                settings = RawLedgerWidgetSettings(timeRange = null, scope = null, topN = 99, title = null),
            ),
        )

        val settings = LedgerLayoutRules.normalize(raw).single().settings

        assertEquals(12, settings?.topN)
    }

    @Test
    fun `normalize clamps topN below the minimum up to 3`() {
        val raw = listOf(
            RawLedgerWidget(
                id = "widget-1",
                panel = "genres",
                width = "md",
                settings = RawLedgerWidgetSettings(timeRange = null, scope = null, topN = 1, title = null),
            ),
        )

        assertEquals(3, LedgerLayoutRules.normalize(raw).single().settings?.topN)
    }

    @Test
    fun `normalize truncates title to 60 chars`() {
        val longTitle = "x".repeat(100)
        val raw = listOf(
            RawLedgerWidget(
                id = "widget-1",
                panel = "genres",
                width = "md",
                settings = RawLedgerWidgetSettings(timeRange = null, scope = null, topN = null, title = longTitle),
            ),
        )

        assertEquals(60, LedgerLayoutRules.normalize(raw).single().settings?.title?.length)
    }

    @Test
    fun `normalize drops an unrecognized timeRange or scope value`() {
        val raw = listOf(
            RawLedgerWidget(
                id = "widget-1",
                panel = "genres",
                width = "md",
                settings = RawLedgerWidgetSettings(timeRange = "decade", scope = "documentaries", topN = null, title = null),
            ),
        )

        val settings = LedgerLayoutRules.normalize(raw).single().settings
        assertNull(settings?.timeRange)
        assertNull(settings?.scope)
    }

    @Test
    fun `normalize keeps a valid timeRange and scope`() {
        val raw = listOf(
            RawLedgerWidget(
                id = "widget-1",
                panel = "genres",
                width = "md",
                settings = RawLedgerWidgetSettings(timeRange = "12mo", scope = "movies", topN = null, title = null),
            ),
        )

        val settings = LedgerLayoutRules.normalize(raw).single().settings
        assertEquals("12mo", settings?.timeRange)
        assertEquals("movies", settings?.scope)
    }
}
