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
