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
  /** Only on a fetched snapshot whose credits (title cast/crew, season cast,
   * episode crew) were read for just these titles. Every other title's credits
   * are absent from it, not empty, and mergeRefreshedSnapshot keeps the cached
   * ones. Never stored. Absent means every title's credits are authoritative. */
  creditsFor?: string[]
}

/** Credits carry no row revisions and no guarded command targets a credit row
 * (see collectRowRevisions/mutationRows), so a credit-free refresh still
 * refreshes every revision a later precondition can capture. */
function carryCredits(previous: OfflineSnapshot, incoming: OfflineSnapshot, creditsFor: string[]): Title[] {
  const fresh = new Set(creditsFor)
  const cached = new Map(previous.titles.map((title) => [title.id, title]))
  return incoming.titles.map((title) => {
    const old = cached.get(title.id)
    if (fresh.has(title.id) || !old) return title
    const oldSeasons = new Map((old.seasons ?? []).map((season) => [season.id, season]))
    const oldEpisodes = new Map((old.seasons ?? []).flatMap((season) => season.episodes ?? []).map((episode) => [episode.id, episode]))
    return { ...title, cast: old.cast, crew: old.crew, seasons: title.seasons?.map((season) => {
      const oldSeason = oldSeasons.get(season.id)
      return { ...season, cast: oldSeason?.cast ?? season.cast, episodes: season.episodes?.map((episode) => {
        const oldEpisode = oldEpisodes.get(episode.id)
        // director/writers are derived from episode crew when it is mapped.
        return oldEpisode ? { ...episode, director: oldEpisode.director, writers: oldEpisode.writers, crew: oldEpisode.crew } : episode
      }) }
    }) }
  })
}

/** What a title's credits were last read against: its own revision and those
 * of its seasons and episodes. A metadata refresh rewrites those rows along
 * with their credits, so a changed signature means the cached credits may be
 * stale; a title edit only costs that one title's credits. */
function creditSignature(title: Title, revisions: Record<string, string> | undefined): string {
  return JSON.stringify([revisions?.[`titles:${title.id}`] ?? null, ...(title.seasons ?? []).map((season) => [season.id,
    revisions?.[`seasons:${season.id}`] ?? null, ...(season.episodes ?? []).map((episode) => [episode.id, revisions?.[`episodes:${episode.id}`] ?? null])])])
}

/** Titles in a credit-free fetch whose cached credits cannot be reused: new to
 * this cache, read against different revisions, or named by the caller (a
 * command that just rewrote them). */
export function staleCreditTitles(previous: OfflineSnapshot, titles: Title[], revisions: Record<string, string>, include: readonly string[] = []): string[] {
  const cached = new Map(previous.titles.map((title) => [title.id, creditSignature(title, previous.rowRevisions)]))
  const named = new Set(include)
  return titles.filter((title) => named.has(title.id) || cached.get(title.id) !== creditSignature(title, revisions)).map((title) => title.id)
}

export function mergeRefreshedSnapshot(previous: OfflineSnapshot, incoming: OfflineSnapshot): OfflineSnapshot {
  if (incoming.creditsFor !== undefined) {
    const { creditsFor, ...rest } = incoming
    incoming = { ...rest, titles: carryCredits(previous, rest, creditsFor) }
  }
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
