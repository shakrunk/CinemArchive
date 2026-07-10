// Panel identifiers for the customizable Ledger dashboard layout (Settings →
// Ledger Layout). Mirrors the reorder/hide pattern used for nav prefs in
// src/lib/navigation.ts, but scoped to Ledger.tsx panels.

export type LedgerPanelId =
  | 'activity'
  | 'encores'
  | 'run'
  | 'ratings'
  | 'genres'
  | 'decades'
  | 'auteurs'
  | 'ensemble'
  | 'runtimes'
  | 'networks'
  | 'verdicts'
  | 'languages'
  | 'weekdays'
  | 'streaks'
  | 'trajectory'
  | 'revivals'
  | 'timewarp'
  | 'progress'
  | 'attractions'

export const DEFAULT_LEDGER_PANEL_ORDER: LedgerPanelId[] = [
  'activity',
  'encores',
  'run',
  'ratings',
  'genres',
  'decades',
  'auteurs',
  'ensemble',
  'runtimes',
  'networks',
  'verdicts',
  'languages',
  'weekdays',
  'streaks',
  'trajectory',
  'revivals',
  'timewarp',
  'progress',
  'attractions',
]

export const LEDGER_PANEL_LABELS: Record<LedgerPanelId, string> = {
  activity: 'Time in the Dark',
  encores: 'Encore Performances',
  run: 'The Run',
  ratings: 'Critical Record',
  genres: 'By the Genre',
  decades: 'By the Era',
  auteurs: 'The Auteurs',
  ensemble: 'The Ensemble',
  runtimes: 'Feature Lengths',
  networks: 'On the Air',
  verdicts: 'Second Opinions',
  languages: 'In Translation',
  weekdays: 'Screening Nights',
  streaks: 'The Marathon',
  trajectory: 'Shifting Standards',
  revivals: 'Premieres & Revivals',
  timewarp: 'The Revival House',
  progress: 'Still Rolling',
  attractions: 'Coming Attractions',
}

/** Short blurbs shown in the layout editor's widget palette. */
export const LEDGER_PANEL_DESCRIPTIONS: Record<LedgerPanelId, string> = {
  activity: '52-week screening heatmap',
  encores: 'Most-rewatched titles',
  run: 'Monthly screening trend',
  ratings: 'Rating distribution donut',
  genres: 'Top genres as marquee bubbles',
  decades: 'Titles by release decade',
  auteurs: 'Most-watched directors',
  ensemble: 'Most-billed leading cast',
  runtimes: 'Movie runtime histogram',
  networks: 'Top TV networks',
  verdicts: 'Your ratings vs IMDb',
  languages: 'Original language breakdown',
  weekdays: 'Screenings by day of week',
  streaks: 'Longest & current screening streaks',
  trajectory: 'Your average rating over time',
  revivals: 'First watches vs. encores by month',
  timewarp: 'How old the films you screen are',
  progress: 'Series in progress, by completion',
  attractions: 'The watchlist, weighed',
}

/** Panel width, expressed as a 12-column grid span (desktop only — panels are
 *  always full-width below the `lg` breakpoint). Set by dragging a panel's
 *  edge/corner handles in the Ledger's edit mode. */
export type LedgerPanelWidth = 'sm' | 'md' | 'lg' | 'full'

export const LEDGER_PANEL_WIDTH_ORDER: LedgerPanelWidth[] = ['sm', 'md', 'lg', 'full']

export const LEDGER_PANEL_WIDTH_LABELS: Record<LedgerPanelWidth, string> = {
  sm: 'S',
  md: 'M',
  lg: 'L',
  full: 'Full',
}

/** Column span (out of 12) each width preset occupies at the `lg` breakpoint. */
export const LEDGER_PANEL_WIDTH_SPANS: Record<LedgerPanelWidth, number> = {
  sm: 4,
  md: 6,
  lg: 8,
  full: 12,
}

/** Snap a fractional column span (from an edge drag) to the nearest preset. */
export function nearestPanelWidth(span: number): LedgerPanelWidth {
  let best: LedgerPanelWidth = 'sm'
  let bestDist = Infinity
  for (const w of LEDGER_PANEL_WIDTH_ORDER) {
    const dist = Math.abs(LEDGER_PANEL_WIDTH_SPANS[w] - span)
    if (dist < bestDist) {
      bestDist = dist
      best = w
    }
  }
  return best
}

/** Every widget card on the board renders at this fixed height (px), so grid
 *  rows stay uniform; overflowing content scrolls within the card. */
export const LEDGER_PANEL_STANDARD_HEIGHT = 400

export const DEFAULT_LEDGER_PANEL_WIDTHS: Record<LedgerPanelId, LedgerPanelWidth> = {
  activity: 'lg',
  encores: 'sm',
  run: 'full',
  ratings: 'md',
  genres: 'md',
  decades: 'full',
  auteurs: 'md',
  ensemble: 'md',
  runtimes: 'md',
  networks: 'md',
  verdicts: 'lg',
  languages: 'sm',
  weekdays: 'full',
  streaks: 'sm',
  trajectory: 'md',
  revivals: 'md',
  timewarp: 'md',
  progress: 'md',
  attractions: 'sm',
}

// ─── Per-widget settings ─────────────────────────────────────────────────────

/** Time window a panel's data is restricted to. Applies to dated viewings /
 *  watch events; collection-level panels (genres, decades, …) ignore it. */
export type LedgerTimeRange = 'all' | '12mo' | 'ytd' | '5y'

export type LedgerScope = 'all' | 'movies' | 'tv'

/** Optional per-instance knobs. Every field absent = the panel's default, so a
 *  widget without `settings` behaves exactly like a pre-settings widget. */
export interface LedgerWidgetSettings {
  timeRange?: LedgerTimeRange
  scope?: LedgerScope
  topN?: number
  title?: string
}

export const LEDGER_TOP_N_MIN = 3
export const LEDGER_TOP_N_MAX = 12

export const LEDGER_TIME_RANGE_ORDER: LedgerTimeRange[] = ['all', '5y', 'ytd', '12mo']

export const LEDGER_TIME_RANGE_LABELS: Record<LedgerTimeRange, string> = {
  all: 'All time',
  '5y': '5 yr',
  ytd: 'This year',
  '12mo': '12 mo',
}

export const LEDGER_SCOPE_ORDER: LedgerScope[] = ['all', 'movies', 'tv']

export const LEDGER_SCOPE_LABELS: Record<LedgerScope, string> = {
  all: 'All',
  movies: 'Films',
  tv: 'Series',
}

/** Which knobs each panel exposes in the layout editor's details panel.
 *  Panels silently ignore settings keys they do not list. */
export const PANEL_SETTING_KEYS: Record<LedgerPanelId, Array<keyof LedgerWidgetSettings>> = {
  activity: ['scope', 'title'],
  encores: ['scope', 'topN', 'title'],
  run: ['timeRange', 'scope', 'title'],
  ratings: ['scope', 'title'],
  genres: ['scope', 'topN', 'title'],
  decades: ['scope', 'title'],
  auteurs: ['scope', 'topN', 'title'],
  ensemble: ['scope', 'topN', 'title'],
  runtimes: ['title'],
  networks: ['topN', 'title'],
  verdicts: ['scope', 'topN', 'title'],
  languages: ['scope', 'topN', 'title'],
  weekdays: ['timeRange', 'scope', 'title'],
  streaks: ['scope', 'title'],
  trajectory: ['timeRange', 'scope', 'title'],
  revivals: ['timeRange', 'scope', 'title'],
  timewarp: ['scope', 'title'],
  progress: ['topN', 'title'],
  attractions: ['title'],
}

/** Per-panel defaults for knobs whose neutral value isn't the global one
 *  (topN varies by panel; The Run's natural window is 12 months). */
const PANEL_SETTING_DEFAULTS: Partial<Record<LedgerPanelId, { timeRange?: LedgerTimeRange; topN?: number }>> = {
  encores: { topN: 6 },
  run: { timeRange: '12mo' },
  genres: { topN: 6 },
  auteurs: { topN: 5 },
  ensemble: { topN: 5 },
  networks: { topN: 6 },
  verdicts: { topN: 6 },
  languages: { topN: 6 },
  trajectory: { timeRange: '5y' },
  revivals: { timeRange: '12mo' },
  progress: { topN: 5 },
}

export interface EffectiveLedgerSettings {
  timeRange: LedgerTimeRange
  scope: LedgerScope
  topN: number
}

export function clampTopN(n: number): number {
  return Math.max(LEDGER_TOP_N_MIN, Math.min(LEDGER_TOP_N_MAX, Math.round(n)))
}

/** Resolve a widget's settings against its panel's defaults. */
export function effectiveLedgerSettings(
  panel: LedgerPanelId,
  settings?: LedgerWidgetSettings,
): EffectiveLedgerSettings {
  const d = PANEL_SETTING_DEFAULTS[panel] ?? {}
  return {
    timeRange: settings?.timeRange ?? d.timeRange ?? 'all',
    scope: settings?.scope ?? 'all',
    topN: clampTopN(settings?.topN ?? d.topN ?? 6),
  }
}

/** Stable dependency-array key for a widget's settings — plain JSON identity
 *  rather than object reference, so a `settings` prop with unchanged content
 *  but a new identity doesn't force a panel's memoized derivation to recompute. */
export function settingsDepKey(settings?: LedgerWidgetSettings): string {
  return JSON.stringify(settings ?? {})
}

/** Short " · films · this year" suffix appended to a configured panel's hint
 *  so a customized card is self-describing on the board. */
export function describeLedgerSettings(settings?: LedgerWidgetSettings): string {
  if (!settings) return ''
  const parts: string[] = []
  if (settings.scope === 'movies') parts.push('films only')
  if (settings.scope === 'tv') parts.push('series only')
  if (settings.timeRange === 'ytd') parts.push('this year')
  if (settings.timeRange === '12mo') parts.push('past 12 mo')
  if (settings.timeRange === '5y') parts.push('past 5 yr')
  return parts.length ? ` · ${parts.join(' · ')}` : ''
}

// ─── Widget instances ────────────────────────────────────────────────────────

/** A widget placed on the Ledger board. Instances are keyed by their own id
 *  (not the panel type), so the same visualization can appear multiple times.
 *  Height is standardized (LEDGER_PANEL_STANDARD_HEIGHT) — only width varies. */
export interface LedgerWidget {
  id: string
  panel: LedgerPanelId
  width: LedgerPanelWidth
  settings?: LedgerWidgetSettings
}

export function newLedgerWidgetId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `w_${crypto.randomUUID().slice(0, 8)}`
  }
  return `w_${Math.random().toString(36).slice(2, 10)}`
}

export function createLedgerWidget(panel: LedgerPanelId, width?: LedgerPanelWidth): LedgerWidget {
  return { id: newLedgerWidgetId(), panel, width: width ?? DEFAULT_LEDGER_PANEL_WIDTHS[panel] }
}

/** The out-of-the-box board: one instance of every panel, in default order. */
export function defaultLedgerWidgets(): LedgerWidget[] {
  return DEFAULT_LEDGER_PANEL_ORDER.map((panel) => createLedgerWidget(panel))
}

/** Sanitize a raw widget list (from localStorage rehydrate or the synced DB
 *  layout): drop instances whose panel type no longer exists, backfill widths,
 *  and keep only known, well-typed settings keys. Returns null when the input
 *  isn't a widget array at all. */
export function normalizeLedgerWidgets(raw: unknown): LedgerWidget[] | null {
  if (!Array.isArray(raw)) return null
  const widgets: LedgerWidget[] = []
  for (const item of raw) {
    if (typeof item !== 'object' || item === null) continue
    const w = item as Partial<LedgerWidget> & { settings?: Record<string, unknown> }
    if (typeof w.panel !== 'string' || !(w.panel in LEDGER_PANEL_LABELS)) continue
    const widget: LedgerWidget = {
      id: typeof w.id === 'string' && w.id ? w.id : newLedgerWidgetId(),
      panel: w.panel as LedgerPanelId,
      width:
        typeof w.width === 'string' && LEDGER_PANEL_WIDTH_ORDER.includes(w.width as LedgerPanelWidth)
          ? (w.width as LedgerPanelWidth)
          : DEFAULT_LEDGER_PANEL_WIDTHS[w.panel as LedgerPanelId],
    }
    if (typeof w.settings === 'object' && w.settings !== null) {
      const s = w.settings as Record<string, unknown>
      const settings: LedgerWidgetSettings = {}
      if (typeof s.timeRange === 'string' && LEDGER_TIME_RANGE_ORDER.includes(s.timeRange as LedgerTimeRange)) {
        settings.timeRange = s.timeRange as LedgerTimeRange
      }
      if (typeof s.scope === 'string' && LEDGER_SCOPE_ORDER.includes(s.scope as LedgerScope)) {
        settings.scope = s.scope as LedgerScope
      }
      if (typeof s.topN === 'number' && Number.isFinite(s.topN)) settings.topN = clampTopN(s.topN)
      if (typeof s.title === 'string' && s.title.trim()) settings.title = s.title.slice(0, 60)
      if (Object.keys(settings).length > 0) widget.settings = settings
    }
    widgets.push(widget)
  }
  return widgets
}
