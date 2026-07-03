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

export const DEFAULT_LEDGER_PANEL_ORDER: LedgerPanelId[] = [
  'activity',
  'encores',
  'run',
  'ratings',
  'genres',
  'decades',
  'auteurs',
  'ensemble',
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
}

/** Panel width, expressed as a 12-column grid span (desktop only — panels are
 *  always full-width below the `lg` breakpoint). Cycled via the in-place
 *  layout editor on Ledger.tsx. */
export type LedgerPanelWidth = 'sm' | 'md' | 'lg' | 'full'

export const LEDGER_PANEL_WIDTH_ORDER: LedgerPanelWidth[] = ['sm', 'md', 'lg', 'full']

export const LEDGER_PANEL_WIDTH_LABELS: Record<LedgerPanelWidth, string> = {
  sm: 'S',
  md: 'M',
  lg: 'L',
  full: 'Full',
}

export const DEFAULT_LEDGER_PANEL_WIDTHS: Record<LedgerPanelId, LedgerPanelWidth> = {
  activity: 'lg',
  encores: 'sm',
  run: 'full',
  ratings: 'md',
  genres: 'md',
  decades: 'full',
  auteurs: 'md',
  ensemble: 'md',
}
