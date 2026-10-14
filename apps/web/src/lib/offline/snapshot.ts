import type { CinemaOuting, List, Title } from '../../store/mockData'
import type { LedgerWidget } from '../ledgerPanels'
import type { TheaterInterest, VenueNote } from '../moviegoingPreferences'

/** Server base only. Pending optimism lives in commands, never in this snapshot.
 * Membership arrays survive JSON/IndexedDB and become Sets at the store boundary. */
export interface OfflineSnapshot {
  titles: Title[]
  outings: CinemaOuting[]
  lists: List[]
  listMemberships: Record<string, string[]>
  pinnedModes: Record<string, 'bw' | 'color'>
  ledgerWidgets: LedgerWidget[] | null
  rowRevisions?: Record<string, string>
  /** Missing on older caches; unsupported reads cannot clear managed tickets. */
  ticketAttachmentSupport?: 'authoritative' | 'unsupported'
  venueNotes?: VenueNote[]
  theaterInterest?: TheaterInterest[]
  moviegoingPreferencesSupport?: 'authoritative' | 'unsupported'
}

export function mergeRefreshedSnapshot(previous: OfflineSnapshot, incoming: OfflineSnapshot): OfflineSnapshot {
  // Absence also covers snapshots produced by older clients. Never erase a
  // private domain until its reader explicitly confirms authoritative results.
  if (incoming.moviegoingPreferencesSupport !== 'authoritative') {
    const revisions = Object.fromEntries(Object.entries(previous.rowRevisions ?? {})
      .filter(([key]) => key.startsWith('venue_notes:') || key.startsWith('theater_interest:')))
    incoming = { ...incoming,
      ...(previous.venueNotes !== undefined ? { venueNotes: previous.venueNotes } : {}),
      ...(previous.theaterInterest !== undefined ? { theaterInterest: previous.theaterInterest } : {}),
      ...(Object.keys(revisions).length ? { rowRevisions: { ...incoming.rowRevisions, ...revisions } } : {}),
    }
  }
  if (incoming.ticketAttachmentSupport !== 'unsupported') return incoming
  const old = new Map(previous.outings.map((outing) => [outing.id, outing]))
  return { ...incoming, outings: incoming.outings.map((outing) => {
    const cached = old.get(outing.id)
    if (!cached?.ticketManaged) return outing
    const preserved = { ...outing, ticketManaged: true }
    delete preserved.ticketAttachment
    if (cached.ticketAttachment) preserved.ticketAttachment = cached.ticketAttachment
    return preserved
  }) }
}

export function emptySnapshot(): OfflineSnapshot {
  return { titles: [], outings: [], lists: [], listMemberships: {}, pinnedModes: {}, ledgerWidgets: null }
}
