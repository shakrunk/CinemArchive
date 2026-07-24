package work.kumarfamilynet.cinemarchive.core.model

/** Direct port of `ledgerPanels.ts`'s per-widget settings contract — which knobs each of the
 *  20 panels actually honors, and the panel-specific defaults ("The Run" panel's own
 *  natural window is 12 months, not the global "all time" default; several panels default
 *  `topN` to 5 or 6). Kept in `core:model` alongside [LedgerWidgetId] since both
 *  [work.kumarfamilynet.cinemarchive.data.LedgerRepository] (widget aggregation) and
 *  `feature:ledger` (the edit-mode settings UI) need it. */
enum class LedgerSettingKey { TIME_RANGE, SCOPE, TOP_N, TITLE }

/** Which of [LedgerWidgetSettings]'s fields each panel exposes/consumes. A panel not listing
 *  a key must silently ignore that key if present on a stored/synced widget (ledger.md §1 —
 *  same "drop what you don't recognize" posture as [LedgerLayoutRules.normalize]), not error
 *  or crash. */
val PANEL_SETTING_KEYS: Map<LedgerWidgetId, Set<LedgerSettingKey>> = mapOf(
    LedgerWidgetId.ACTIVITY to setOf(LedgerSettingKey.SCOPE, LedgerSettingKey.TITLE),
    LedgerWidgetId.ENCORES to setOf(LedgerSettingKey.SCOPE, LedgerSettingKey.TOP_N, LedgerSettingKey.TITLE),
    LedgerWidgetId.RUN to setOf(LedgerSettingKey.TIME_RANGE, LedgerSettingKey.SCOPE, LedgerSettingKey.TITLE),
    LedgerWidgetId.RATINGS to setOf(LedgerSettingKey.SCOPE, LedgerSettingKey.TITLE),
    LedgerWidgetId.GENRES to setOf(LedgerSettingKey.SCOPE, LedgerSettingKey.TOP_N, LedgerSettingKey.TITLE),
    LedgerWidgetId.DECADES to setOf(LedgerSettingKey.SCOPE, LedgerSettingKey.TITLE),
    LedgerWidgetId.AUTEURS to setOf(LedgerSettingKey.SCOPE, LedgerSettingKey.TOP_N, LedgerSettingKey.TITLE),
    LedgerWidgetId.ENSEMBLE to setOf(LedgerSettingKey.SCOPE, LedgerSettingKey.TOP_N, LedgerSettingKey.TITLE),
    LedgerWidgetId.RUNTIMES to setOf(LedgerSettingKey.TITLE),
    LedgerWidgetId.NETWORKS to setOf(LedgerSettingKey.TOP_N, LedgerSettingKey.TITLE),
    LedgerWidgetId.VERDICTS to setOf(LedgerSettingKey.SCOPE, LedgerSettingKey.TOP_N, LedgerSettingKey.TITLE),
    LedgerWidgetId.LANGUAGES to setOf(LedgerSettingKey.SCOPE, LedgerSettingKey.TOP_N, LedgerSettingKey.TITLE),
    LedgerWidgetId.WEEKDAYS to setOf(LedgerSettingKey.TIME_RANGE, LedgerSettingKey.SCOPE, LedgerSettingKey.TITLE),
    LedgerWidgetId.STREAKS to setOf(LedgerSettingKey.SCOPE, LedgerSettingKey.TITLE),
    LedgerWidgetId.TRAJECTORY to setOf(LedgerSettingKey.TIME_RANGE, LedgerSettingKey.SCOPE, LedgerSettingKey.TITLE),
    LedgerWidgetId.REVIVALS to setOf(LedgerSettingKey.TIME_RANGE, LedgerSettingKey.SCOPE, LedgerSettingKey.TITLE),
    LedgerWidgetId.TIMEWARP to setOf(LedgerSettingKey.SCOPE, LedgerSettingKey.TITLE),
    LedgerWidgetId.PROGRESS to setOf(LedgerSettingKey.TOP_N, LedgerSettingKey.TITLE),
    LedgerWidgetId.ATTRACTIONS to setOf(LedgerSettingKey.TITLE),
    LedgerWidgetId.MOVIEGOING to setOf(LedgerSettingKey.TITLE),
)

fun LedgerWidgetId.honorsLedgerSetting(key: LedgerSettingKey): Boolean = key in (PANEL_SETTING_KEYS[this] ?: emptySet())

private data class PanelSettingDefaults(val timeRange: String? = null, val topN: Int? = null)

/** Panel-specific defaults for knobs whose neutral value isn't the global one — mirrors
 *  `PANEL_SETTING_DEFAULTS` in ledgerPanels.ts exactly. */
private val PANEL_SETTING_DEFAULTS: Map<LedgerWidgetId, PanelSettingDefaults> = mapOf(
    LedgerWidgetId.ENCORES to PanelSettingDefaults(topN = 6),
    LedgerWidgetId.RUN to PanelSettingDefaults(timeRange = "12mo"),
    LedgerWidgetId.GENRES to PanelSettingDefaults(topN = 6),
    LedgerWidgetId.AUTEURS to PanelSettingDefaults(topN = 5),
    LedgerWidgetId.ENSEMBLE to PanelSettingDefaults(topN = 5),
    LedgerWidgetId.NETWORKS to PanelSettingDefaults(topN = 6),
    LedgerWidgetId.VERDICTS to PanelSettingDefaults(topN = 6),
    LedgerWidgetId.LANGUAGES to PanelSettingDefaults(topN = 6),
    LedgerWidgetId.TRAJECTORY to PanelSettingDefaults(timeRange = "5y"),
    LedgerWidgetId.REVIVALS to PanelSettingDefaults(timeRange = "12mo"),
    LedgerWidgetId.PROGRESS to PanelSettingDefaults(topN = 5),
)

private const val TOP_N_MIN = 3
private const val TOP_N_MAX = 12

private fun clampTopN(n: Int): Int = n.coerceIn(TOP_N_MIN, TOP_N_MAX)

/** A widget's fully-resolved settings — every field always present, panel defaults applied.
 *  Does **not** itself gate by [PANEL_SETTING_KEYS]; callers that need "did this panel even
 *  ask for this knob" must check [honorsLedgerSetting] separately, same split as the web
 *  app's `effectiveLedgerSettings()` (which also returns a value unconditionally) vs. each
 *  panel's own derivation choosing whether to apply it. */
data class LedgerEffectiveSettings(val timeRange: String, val scope: String, val topN: Int)

fun effectiveLedgerSettings(panel: LedgerWidgetId, settings: LedgerWidgetSettings?): LedgerEffectiveSettings {
    val defaults = PANEL_SETTING_DEFAULTS[panel]
    return LedgerEffectiveSettings(
        timeRange = settings?.timeRange ?: defaults?.timeRange ?: "all",
        scope = settings?.scope ?: "all",
        topN = clampTopN(settings?.topN ?: defaults?.topN ?: 6),
    )
}
