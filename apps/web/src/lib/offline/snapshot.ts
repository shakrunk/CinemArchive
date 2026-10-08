import type { CinemaOuting, List, Title } from '../../store/mockData'
import type { LedgerWidget } from '../ledgerPanels'

/** Server base only. Pending optimism lives in commands, never in this snapshot.
 * Membership arrays survive JSON/IndexedDB and become Sets at the store boundary. */
export interface OfflineSnapshot {
  titles: Title[]
  outings: CinemaOuting[]
  lists: List[]
  listMemberships: Record<string, string[]>
  pinnedModes: Record<string, 'bw' | 'color'>
  ledgerWidgets: LedgerWidget[] | null
}

export function emptySnapshot(): OfflineSnapshot {
  return { titles: [], outings: [], lists: [], listMemberships: {}, pinnedModes: {}, ledgerWidgets: null }
}
