// Small formatting helpers shared across Ledger panels. Kept separate from
// PanelShell.tsx so that file only exports components (react-refresh rule).

export function renderStarLabel(rating: number): string {
  const full = Math.floor(rating)
  const half = rating % 1 >= 0.5
  return '★'.repeat(full) + (half ? '½' : '')
}

export function localDateStr(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function monthLabel(month: string): string {
  const [y, m] = month.split('-').map(Number)
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short' })
}

/** Month label with a 2-digit year suffix ("Mar '25") — for series that span
 *  more than one calendar year, where a bare month name is ambiguous. */
export function monthYearLabel(month: string): string {
  return `${monthLabel(month)} ’${month.slice(2, 4)}`
}
