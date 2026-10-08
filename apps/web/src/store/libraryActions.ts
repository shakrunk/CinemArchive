import type { CinemaOuting, List, Title, Viewing } from './mockData'
import type { LedgerWidget, LedgerWidgetSettings, LedgerPanelId, LedgerPanelWidth } from '../lib/ledgerPanels'
import { createLedgerWidget, defaultLedgerWidgets, newLedgerWidgetId } from '../lib/ledgerPanels'
import { isSpecialsSeason, nextUnwatchedEpisode } from './episodeUtils'
import { compound, explicitPatch, titlePatchCommand } from './libraryCommands'
import type { Mutation, OutingPatch, TrackingMutation, ViewingPatch } from '../lib/offline/commands'
import type { OutingSharePayload } from './outings'
import { localDateStr } from './outings'
import type { SyncOutcome } from '../lib/sync/core'
import { isTicketMutation, TICKET_OUTING_FIELDS } from '../lib/tickets/types'

export interface LibraryActionState {
  titles: Title[]; outings: CinemaOuting[]; lists: List[]
  listMemberships: Record<string, Set<string>>
  pinnedModes: Record<string, 'bw' | 'color'>
  ledgerPrefs: { widgets: LedgerWidget[] }
}
export type LibraryWrite = <T>(prepare: (state: LibraryActionState) => { mutation: Mutation | null; result: T }) => Promise<T>
type EpisodeLog = { watchedAt?: string; prePlatform?: boolean; watchNotes?: string; rating?: number; reviewText?: string; colorMode?: 'bw' | 'color' }
const leaves = (mutation: Mutation | null): TrackingMutation[] => {
  if (!mutation) return []
  if (isTicketMutation(mutation)) throw new Error('Ticket changes cannot be combined with library commands')
  return mutation.kind === 'batch' ? mutation.mutations : [mutation]
}

/** Every action builds a typed, immutable journal payload against the latest
 * projection inside the store's serialized local-write boundary. */
export function createLibraryActions(write: LibraryWrite, afterOutingRevert?: (outingId: string) => void) {
  const save = (prepare: (state: LibraryActionState) => Mutation | null) => write((state) => ({ mutation: prepare(state), result: undefined }))
  const title = (state: LibraryActionState, id: string) => {
    const found = state.titles.find((row) => row.id === id)
    if (!found) throw new Error('This title is no longer in your library')
    return found
  }
  const layout = (change: (widgets: LedgerWidget[]) => LedgerWidget[]) => save((state) => ({ kind: 'ledger.set', widgets: change(state.ledgerPrefs.widgets) }))
  const actions = {
    applySyncOutcome: (outcome: SyncOutcome) => {
      const ids = [...new Set([...outcome.inserts.map((value) => value.id), ...outcome.updates.map((value) => value.titleId), ...outcome.links.map((value) => value.titleId)])]
      // Invoke all write boundaries now: every chunk captures the same owner
      // generation, while each builder reads the latest committed projection.
      const saves = ids.map((id) => write((state) => {
        const inserted = outcome.inserts.find((value) => value.id === id)
        const changes: TrackingMutation[] = inserted ? [{ kind: 'title.create', title: inserted }] : []
        const updates = outcome.updates.filter((value) => value.titleId === id)
        if (updates.length) {
          const existing = inserted ?? title(state, id)
          const patch: Partial<Title> = {}
          const viewings: Viewing[] = []
          for (const update of updates) { Object.assign(patch, update.patch); viewings.push(...(update.patch.viewings ?? [])) }
          changes.push(...leaves(titlePatchCommand(existing, { ...patch, viewings: [...existing.viewings, ...viewings] })))
        }
        const links = new Map(outcome.links.filter((link) => link.titleId === id).map((link) => [`${link.provider}:${link.externalId}`, link]))
        for (const link of links.values()) changes.push({ kind: 'external.link', ...link })
        return { mutation: compound(changes), result: undefined }
      }))
      return Promise.allSettled(saves).then((results) => {
        const failed = results.find((result) => result.status === 'rejected')
        if (failed?.status === 'rejected') throw new Error(`Import saved ${results.filter((result) => result.status === 'fulfilled').length} of ${ids.length} title changes on this device. ${failed.reason instanceof Error ? failed.reason.message : 'Retry the remaining items.'}`)
        return { added: outcome.inserts.length, updated: new Set(outcome.updates.map((value) => value.titleId)).size }
      })
    },
    addTitle: (value: Title) => save((state) => {
      if (state.titles.some((row) => row.tmdbId === value.tmdbId && row.type === value.type)) throw new Error('This title is already in your library')
      return { kind: 'title.create', title: value }
    }),
    updateTitle: (id: string, patch: Partial<Title>) => save((state) => titlePatchCommand(title(state, id), patch)),
    updateTitleMetadata: (id: string, patch: Partial<Title>) => save((state) => titlePatchCommand(title(state, id), patch, { metadataOnly: true })),
    removeTitle: (id: string) => save(() => ({ kind: 'title.delete', titleId: id })),
    setTitles: (values: Title[]) => save((state) => compound([
      ...values.flatMap((value) => { const old = state.titles.find((row) => row.id === value.id); return old ? leaves(titlePatchCommand(old, value)) : [{ kind: 'title.create' as const, title: value }] }),
      ...state.titles.filter((old) => !values.some((value) => value.id === old.id)).map((old): TrackingMutation => ({ kind: 'title.delete', titleId: old.id })),
    ])),
    importLibrary: (values: Title[], outings: CinemaOuting[] = []) => {
      const saves = values.map((value) => save((state) => {
        if (state.titles.some((row) => row.tmdbId === value.tmdbId && row.type === value.type)) return null
        const related = outings.filter((outing) => outing.titleId === value.id)
        return compound([
          { kind: 'title.create', title: { ...value, viewings: [] } },
          ...related.map((outing): TrackingMutation => ({ kind: 'outing.create', outing: { ...outing, completedViewingId: undefined } })),
          ...value.viewings.map((viewing): TrackingMutation => ({ kind: 'viewing.put', titleId: value.id, viewing })),
          ...related.filter((outing) => outing.completedViewingId).map((outing): TrackingMutation => ({ kind: 'outing.patch', outingId: outing.id, patch: { completedViewingId: outing.completedViewingId! } })),
        ])
      }))
      return Promise.allSettled(saves).then((results) => {
        const failed = results.find((result) => result.status === 'rejected')
        if (failed?.status === 'rejected') throw new Error(`Import saved ${results.filter((result) => result.status === 'fulfilled').length} of ${values.length} titles on this device. ${failed.reason instanceof Error ? failed.reason.message : 'Retry the remaining titles.'}`)
      })
    },
    addViewing: (titleId: string, viewing: Viewing, patch: Partial<Title> = {}) => save((state) => compound([
      ...leaves(titlePatchCommand(title(state, titleId), patch)), { kind: 'viewing.put', titleId, viewing },
    ])),
    editViewing: (titleId: string, viewingId: string, patch: Partial<Viewing>, titlePatch: Partial<Title> = {}, outing?: { id: string; patch: Partial<CinemaOuting> }) => save((state) => {
      if (!title(state, titleId).viewings.some((row) => row.id === viewingId)) throw new Error('This viewing no longer exists')
      const { id: _id, titleId: _titleId, ...fields } = patch
      if (_id !== undefined && _id !== viewingId || _titleId !== undefined && _titleId !== titleId) throw new Error('Viewing identity cannot change')
      return compound([...leaves(titlePatchCommand(title(state, titleId), titlePatch)),
        ...(Object.keys(fields).length ? [{ kind: 'viewing.patch' as const, titleId, viewingId, patch: explicitPatch(fields) as ViewingPatch }] : []),
        ...(outing ? [{ kind: 'outing.patch' as const, outingId: outing.id, patch: explicitPatch(outing.patch) as OutingPatch }] : []),
      ])
    }),
    removeViewing: (titleId: string, viewingId: string) => save((state) => compound([
      { kind: 'viewing.delete', titleId, viewingId },
      ...state.outings.filter((outing) => outing.completedViewingId === viewingId).map((outing): TrackingMutation => ({ kind: 'outing.patch', outingId: outing.id,
        patch: { completedViewingId: null, followUpDismissedAt: new Date().toISOString() } })),
    ])),
    logEpisode: (titleId: string, seasonNumber: number, episodeNumber: number, opts: EpisodeLog) => save((state) => {
      const episode = title(state, titleId).seasons?.find((season) => season.seasonNumber === seasonNumber)?.episodes?.find((ep) => ep.episodeNumber === episodeNumber)
      if (!episode) throw new Error('This episode is not available in your library')
      const now = new Date().toISOString()
      if (!opts.watchedAt && !opts.prePlatform && !opts.rating && !opts.reviewText?.trim()) return null
      return { kind: 'episode.log', titleId, episodeId: episode.id,
        watchEvent: opts.watchedAt || opts.prePlatform ? { id: crypto.randomUUID(), watchedAt: opts.prePlatform ? undefined : opts.watchedAt, notes: opts.watchNotes || undefined, colorMode: opts.colorMode } : undefined,
        rating: opts.rating && opts.rating > 0 ? { id: crypto.randomUUID(), rating: opts.rating, ratedAt: now } : undefined,
        review: opts.reviewText?.trim() ? { id: crypto.randomUUID(), reviewText: opts.reviewText.trim(), reviewedAt: now, colorMode: opts.colorMode } : undefined,
      }
    }),
    logNextEpisodeWatch: (titleId: string, colorMode?: 'bw' | 'color') => write((state) => {
      const next = nextUnwatchedEpisode(title(state, titleId).seasons ?? [])
      if (!next) return { mutation: null, result: null }
      const watchEventId = crypto.randomUUID()
      return { mutation: { kind: 'episode.log', titleId, episodeId: next.episode.id,
        watchEvent: { id: watchEventId, watchedAt: localDateStr(new Date()), colorMode } },
        result: { seasonNumber: next.season.seasonNumber, episodeNumber: next.episode.episodeNumber, watchEventId } }
    }),
    markPrePlatformWatched: (titleId: string, seasonNumber?: number) => save((state) => compound([
      ...(title(state, titleId).seasons ?? []).filter((season) => seasonNumber === undefined ? !isSpecialsSeason(season) : season.seasonNumber === seasonNumber)
        .flatMap((season): TrackingMutation[] => season.episodes?.length
          ? season.episodes.filter((ep) => !ep.watchEvents.length).map((ep) => ({ kind: 'episode.log', titleId, episodeId: ep.id, watchEvent: { id: crypto.randomUUID() } }))
          : [{ kind: 'season.progress', titleId, seasonId: season.id, episodesWatched: season.episodeCount }]),
      ...(seasonNumber === undefined ? [{ kind: 'title.patch' as const, titleId, patch: { status: 'watched' as const } }] : []),
    ])),
    deleteEpisodeWatchEvent: (titleId: string, seasonNumber: number, episodeNumber: number, watchEventId: string) => save((state) => {
      const episode = title(state, titleId).seasons?.find((season) => season.seasonNumber === seasonNumber)?.episodes?.find((ep) => ep.episodeNumber === episodeNumber)
      return episode ? { kind: 'episodeWatch.delete', titleId, episodeId: episode.id, watchEventId } : null
    }),
    setPinnedMode: (titleId: string, easterEggKey: string, variant: 'bw' | 'color' | null) => save(() => ({ kind: 'pin.set', titleId, easterEggKey, variant })),
    createList: (name: string, description: string | null = null, titleId?: string) => write(() => {
      const now = new Date().toISOString()
      const list: List = { id: crypto.randomUUID(), name, description, createdAt: now, updatedAt: now }
      return { mutation: compound([{ kind: 'list.create', list }, ...(titleId ? [{ kind: 'membership.set' as const, listId: list.id, titleId, present: true }] : [])]), result: list }
    }),
    renameList: (listId: string, patch: { name?: string; description?: string | null }) => save(() => ({ kind: 'list.patch', listId, patch, updatedAt: new Date().toISOString() })),
    deleteList: (listId: string) => save(() => ({ kind: 'list.delete', listId })),
    addTitleToList: (listId: string, titleId: string) => save(() => ({ kind: 'membership.set', listId, titleId, present: true })),
    removeTitleFromList: (listId: string, titleId: string) => save(() => ({ kind: 'membership.set', listId, titleId, present: false })),
    addOuting: (outing: CinemaOuting) => save(() => ({ kind: 'outing.create', outing })),
    setOutings: (values: CinemaOuting[]) => save((state) => compound([
      ...values.map((outing): TrackingMutation => state.outings.some((row) => row.id === outing.id)
        ? { kind: 'outing.patch', outingId: outing.id, patch: explicitPatch(Object.fromEntries(Object.entries(outing).filter(([key]) => !['id', 'titleId', 'createdAt', ...TICKET_OUTING_FIELDS].includes(key)))) as OutingPatch }
        : { kind: 'outing.create', outing }),
      ...state.outings.filter((old) => !values.some((value) => value.id === old.id)).map((old): TrackingMutation => ({ kind: 'outing.delete', outingId: old.id })),
    ])),
    updateOuting: (outingId: string, patch: Partial<CinemaOuting>) => save((state) => {
      if (TICKET_OUTING_FIELDS.some((key) => Object.hasOwn(patch, key))) throw new Error('Use the ticket attachment controls to change a ticket')
      const existing = state.outings.find((outing) => outing.id === outingId)
      if (!existing) throw new Error('This outing no longer exists')
      const merged = { ...existing, ...patch }
      const endsAt = new Date(new Date(merged.showtime).getTime() + (merged.previewsMinutes + merged.runtimeMinutes) * 60_000).toISOString()
      const { id: _id, titleId: _titleId, createdAt: _createdAt, ...fields } = patch
      if (_id !== undefined && _id !== outingId || _titleId !== undefined && _titleId !== existing.titleId || _createdAt !== undefined && _createdAt !== existing.createdAt) throw new Error('Outing identity cannot change')
      return { kind: 'outing.patch', outingId, patch: explicitPatch({ ...fields, endsAt }) as OutingPatch }
    }),
    cancelOuting: (outingId: string) => save(() => ({ kind: 'outing.patch', outingId, patch: { status: 'cancelled' } })),
    dismissOutingFollowUp: (outingId: string) => save(() => ({ kind: 'outing.patch', outingId, patch: { followUpDismissedAt: new Date().toISOString() } })),
    revertOutingCompletion: (outingId: string) => save((state) => {
      const outing = state.outings.find((row) => row.id === outingId)
      if (!outing || outing.status !== 'completed') return null
      const current = title(state, outing.titleId)
      return compound([
        ...(outing.completedViewingId ? [{ kind: 'viewing.delete' as const, titleId: current.id, viewingId: outing.completedViewingId }] : []),
        { kind: 'outing.patch', outingId, patch: { status: 'missed', completedViewingId: null } },
        ...(current.status === 'watched' && outing.previousStatus ? [{ kind: 'title.patch' as const, titleId: current.id, patch: { status: outing.previousStatus } }] : []),
      ])
    }).then(() => afterOutingRevert?.(outingId)),
    resolveSharedOutingTitle: (payload: OutingSharePayload) => write((state) => {
      const existing = state.titles.find((row) => row.tmdbId === payload.tmdbId && row.type === payload.type)
      if (existing) return { mutation: null, result: existing.id }
      const id = crypto.randomUUID()
      return { mutation: { kind: 'title.create', title: { id, tmdbId: payload.tmdbId, type: payload.type, title: payload.title,
        year: payload.year ?? 0, posterUrl: payload.posterUrl, genres: [], status: 'watchlist', tags: [], addedAt: new Date().toISOString(), viewings: [] } }, result: id }
    }),
    addLedgerWidget: (panel: LedgerPanelId) => write((state) => {
      const widget = createLedgerWidget(panel)
      return { mutation: { kind: 'ledger.set', widgets: [...state.ledgerPrefs.widgets, widget] }, result: widget.id }
    }),
    duplicateLedgerWidget: (id: string) => write((state) => {
      const source = state.ledgerPrefs.widgets.find((widget) => widget.id === id)
      if (!source) return { mutation: null, result: null }
      const copy = { ...source, id: newLedgerWidgetId() }
      const widgets = [...state.ledgerPrefs.widgets]
      widgets.splice(widgets.findIndex((widget) => widget.id === id) + 1, 0, copy)
      return { mutation: { kind: 'ledger.set', widgets }, result: copy.id }
    }),
    removeLedgerWidget: (id: string) => layout((widgets) => widgets.filter((widget) => widget.id !== id)),
    moveLedgerWidget: (id: string, direction: 'up' | 'down') => layout((widgets) => {
      const index = widgets.findIndex((widget) => widget.id === id), target = index + (direction === 'up' ? -1 : 1)
      if (index < 0 || target < 0 || target >= widgets.length) return widgets
      const next = [...widgets]; [next[index], next[target]] = [next[target], next[index]]; return next
    }),
    reorderLedgerWidgets: (ids: string[]) => layout((widgets) => [...ids.map((id) => widgets.find((widget) => widget.id === id)).filter((widget): widget is LedgerWidget => !!widget), ...widgets.filter((widget) => !ids.includes(widget.id))]),
    setLedgerWidgetWidth: (id: string, width: LedgerPanelWidth) => layout((widgets) => widgets.map((widget) => widget.id === id ? { ...widget, width } : widget)),
    setLedgerWidgetSettings: (id: string, patch: Partial<LedgerWidgetSettings>) => layout((widgets) => widgets.map((widget) => {
      if (widget.id !== id) return widget
      const settings = Object.fromEntries(Object.entries({ ...widget.settings, ...patch }).filter(([, value]) => value !== undefined))
      return { ...widget, settings: Object.keys(settings).length ? settings : undefined }
    })),
    resetLedgerPrefs: () => layout(() => defaultLedgerWidgets()),
  }
  return actions
}
