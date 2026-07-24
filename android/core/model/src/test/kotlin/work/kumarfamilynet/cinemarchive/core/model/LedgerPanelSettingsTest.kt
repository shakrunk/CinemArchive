package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [effectiveLedgerSettings]/[honorsLedgerSetting] — the port of
 *  ledgerPanels.ts's `effectiveLedgerSettings()`/`PANEL_SETTING_KEYS` this repository's Phase D
 *  work (docs/superpowers/plans/2026-07-23-android-ledger-parity.md) depends on. */
class LedgerPanelSettingsTest {

    @Test
    fun `a panel with no configured default falls back to all-time, all-scope, top 6`() {
        val effective = effectiveLedgerSettings(LedgerWidgetId.VERDICTS, settings = null)

        assertEquals("all", effective.timeRange)
        assertEquals("all", effective.scope)
        assertEquals(6, effective.topN)
    }

    @Test
    fun `The Run defaults to a 12 month window even with no explicit setting`() {
        val effective = effectiveLedgerSettings(LedgerWidgetId.RUN, settings = null)

        assertEquals("12mo", effective.timeRange)
    }

    @Test
    fun `Shifting Standards defaults to a 5 year window`() {
        val effective = effectiveLedgerSettings(LedgerWidgetId.TRAJECTORY, settings = null)

        assertEquals("5y", effective.timeRange)
    }

    @Test
    fun `The Auteurs and Ensemble default topN to 5, not the global 6`() {
        assertEquals(5, effectiveLedgerSettings(LedgerWidgetId.AUTEURS, settings = null).topN)
        assertEquals(5, effectiveLedgerSettings(LedgerWidgetId.ENSEMBLE, settings = null).topN)
    }

    @Test
    fun `an explicit setting overrides the panel default`() {
        val effective = effectiveLedgerSettings(LedgerWidgetId.RUN, LedgerWidgetSettings(timeRange = "5y"))

        assertEquals("5y", effective.timeRange)
    }

    @Test
    fun `topN is clamped into the 3 to 12 range even for panel defaults and overrides`() {
        assertEquals(12, effectiveLedgerSettings(LedgerWidgetId.GENRES, LedgerWidgetSettings(topN = 99)).topN)
        assertEquals(3, effectiveLedgerSettings(LedgerWidgetId.GENRES, LedgerWidgetSettings(topN = 1)).topN)
    }

    @Test
    fun `Feature Lengths and Coming Attractions only honor title, matching ledgerPanels ts`() {
        assertFalse(LedgerWidgetId.RUNTIMES.honorsLedgerSetting(LedgerSettingKey.SCOPE))
        assertFalse(LedgerWidgetId.RUNTIMES.honorsLedgerSetting(LedgerSettingKey.TIME_RANGE))
        assertFalse(LedgerWidgetId.RUNTIMES.honorsLedgerSetting(LedgerSettingKey.TOP_N))
        assertTrue(LedgerWidgetId.RUNTIMES.honorsLedgerSetting(LedgerSettingKey.TITLE))

        assertFalse(LedgerWidgetId.ATTRACTIONS.honorsLedgerSetting(LedgerSettingKey.SCOPE))
        assertFalse(LedgerWidgetId.MOVIEGOING.honorsLedgerSetting(LedgerSettingKey.SCOPE))
    }

    @Test
    fun `Shifting Standards and Premieres and Revivals are the only panels honoring both time range and scope alongside The Run and Screening Nights`() {
        val expected = setOf(LedgerWidgetId.RUN, LedgerWidgetId.WEEKDAYS, LedgerWidgetId.TRAJECTORY, LedgerWidgetId.REVIVALS)
        val actual = LedgerWidgetId.entries.filter { it.honorsLedgerSetting(LedgerSettingKey.TIME_RANGE) }.toSet()

        assertEquals(expected, actual)
    }
}
