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
}

// ─── Widget instances ────────────────────────────────────────────────────────

/** A widget placed on the Ledger board. Instances are keyed by their own id
 *  (not the panel type), so the same visualization can appear multiple times.
 *  Height is standardized (LEDGER_PANEL_STANDARD_HEIGHT) — only width varies. */
export interface LedgerWidget {
  id: string
  panel: LedgerPanelId
  width: LedgerPanelWidth
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
