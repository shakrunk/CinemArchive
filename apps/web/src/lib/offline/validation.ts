import type { Mutation, OfflineScope, PendingCommand } from './commands'
import type { OfflineSnapshot } from './snapshot'
import { mutationRows } from './preconditions'
import { isTicketAttachment, isTicketId, isValidTicketMutation, ticketObjectKey } from '../tickets/validation'
import { isTicketMutation } from '../tickets/types'

type Check = (value: unknown) => boolean
type Fields = Record<string, Check>
const text: Check = (v) => typeof v === 'string'
const id: Check = (v) => typeof v === 'string' && v.length > 0 && v.length <= 2048
const number: Check = (v) => typeof v === 'number' && Number.isFinite(v)
const integer: Check = (v) => number(v) && Number.isSafeInteger(v) && (v as number) >= 0
const boolean: Check = (v) => typeof v === 'boolean'
const timestamp: Check = (v) => typeof v === 'string' && Number.isFinite(Date.parse(v))
const oneOf = (...values: unknown[]): Check => (v) => values.includes(v)
const nullable = (check: Check): Check => (v) => v === null || check(v)
const array = (check: Check): Check => (v) => Array.isArray(v) && v.every(check)
const strings = array(text)

function record(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v) &&
    (Object.getPrototypeOf(v) === Object.prototype || Object.getPrototypeOf(v) === null)
}
const safeKey = (key: string) => !['__proto__', 'prototype', 'constructor'].includes(key)
const dictionary = (check: Check): Check => (v) => record(v) && Object.entries(v).every(([key, value]) => safeKey(key) && check(value))

function shape(required: Fields, optional: Fields = {}): Check {
  return (v) => record(v) && Object.entries(required).every(([key, check]) => Object.hasOwn(v, key) && check(v[key])) &&
    Object.entries(v).every(([key, value]) => {
      if (!safeKey(key)) return false
      const check = Object.hasOwn(required, key) ? required[key] : Object.hasOwn(optional, key) ? optional[key] : undefined
      return check !== undefined && check(value)
    })
}
function patch(required: Fields, optional: Fields = {}): Check {
  const fields = { ...required, ...Object.fromEntries(Object.entries(optional).map(([key, check]) => [key, nullable(check)])) }
  return (v) => record(v) && Object.keys(v).length > 0 && shape({}, fields)(v)
}
const color = oneOf('bw', 'color')
const status = oneOf('watched', 'watchlist', 'watching', 'dropped')
const companion = shape({ name: text }, { friendUserId: id })
const cast = shape({ tmdbPersonId: number, name: text, order: number }, { character: text, episodeCount: number, profileUrl: text })
const crew = shape({ tmdbPersonId: number, name: text, job: text }, { department: text, profileUrl: text })
const episodeCrew = shape({ tmdbPersonId: number, name: text, job: text })
const physicalMedia = shape({ id, format: oneOf('DVD', 'Blu-ray', '4K UHD', 'VHS', 'LaserDisc', 'Other') }, { edition: text, notes: text })
const watch = shape({ id }, { watchedAt: text, notes: text, colorMode: color })
const rating = shape({ id, rating: number, ratedAt: timestamp })
const review = shape({ id, reviewText: text, reviewedAt: timestamp }, { colorMode: color })
const episodeOptional: Fields = { episodeName: text, airDate: text, runtime: number, synopsis: text, stillUrl: text, director: text, writers: strings, crew: array(episodeCrew) }
const episode = shape({ id, episodeNumber: integer, watchEvents: array(watch), ratings: array(rating), reviews: array(review) }, episodeOptional)
const season = shape({ id, seasonNumber: integer, episodeCount: integer, episodesWatched: integer }, { airYear: number, cast: array(cast), episodes: array(episode) })
const viewingOptional: Fields = { date: text, rating: number, notes: text, venue: text, companions: array(companion), outingId: id }
const viewing = shape({ id, titleId: id }, viewingOptional)
const titleRequired: Fields = { tmdbId: number, type: oneOf('movie', 'tv'), title: text, year: number, genres: strings, status, tags: strings }
const titleOptional: Fields = {
  director: text, posterUrl: text, backdropUrl: text, synopsis: text, runtime: number, network: text,
  rating: number, notes: text, releaseDate: text, originalLanguage: text, contentRating: text,
  imdbId: text, rtUrl: text, customWatchUrl: text, inHomeCollection: boolean, physicalMedia: array(physicalMedia),
  collectionId: number, collectionName: text, imdbRating: number, rtScore: number, metacriticScore: number,
  awardsCount: number, bechdelOutcome: oneOf('pass', 'fail'), bechdelScore: text, cast: array(cast), crew: array(crew), studios: strings,
}
const title = shape({ id, ...titleRequired, addedAt: timestamp, viewings: array(viewing) }, { ...titleOptional, seasons: array(season) })
const outingRequired: Fields = {
  showtime: timestamp, previewsMinutes: number, runtimeMinutes: number, endsAt: timestamp,
  companions: array(companion), seats: strings, status: oneOf('scheduled', 'completed', 'missed', 'cancelled'),
}
const outingOptional: Fields = {
  venue: text, format: oneOf('Standard', 'IMAX', '3D', 'Dolby', '70mm', 'Drive-in', 'Other'),
  ticketPrice: number, seat: text, auditorium: text, seatRow: text, bookingRef: text, notes: text,
  previousStatus: status, completedViewingId: id, followUpDismissedAt: timestamp,
}
const outing = shape({ id, titleId: id, createdAt: timestamp, ...outingRequired }, outingOptional)
const outingSnapshot = shape({ id, titleId: id, createdAt: timestamp, ...outingRequired }, {
  ...outingOptional, ticketAttachment: isTicketAttachment, ticketManaged: boolean, ticketImagePath: text, ticketBarcodePayload: text, ticketBarcodeFormat: text,
})
const list = shape({ id, name: text, description: nullable(text), createdAt: timestamp, updatedAt: timestamp })
// Keep panel IDs as strings so stored boards survive new panel releases. The UI's
// normalizer owns which panels can currently render, not the durable journal.
const widget = shape({ id, panel: id, width: oneOf('sm', 'md', 'lg', 'full') }, {
  settings: shape({}, { timeRange: oneOf('all', '12mo', 'ytd', '5y'), scope: oneOf('all', 'movies', 'tv'), topN: number, title: text }),
})
const checks: Record<string, Check> = {
  'title.create': shape({ kind: text, title }),
  'title.patch': shape({ kind: text, titleId: id, patch: patch(titleRequired, titleOptional) }),
  'title.delete': shape({ kind: text, titleId: id }),
  'season.put': shape({ kind: text, titleId: id, season }),
  'season.progress': shape({ kind: text, titleId: id, seasonId: id, episodesWatched: integer }),
  'episode.metadata': shape({ kind: text, titleId: id, episodeId: id, patch: patch({}, episodeOptional) }),
  'episode.log': (v) => shape({ kind: text, titleId: id, episodeId: id }, { watchEvent: watch, rating, review })(v) &&
    record(v) && ('watchEvent' in v || 'rating' in v || 'review' in v),
  'episodeWatch.delete': shape({ kind: text, titleId: id, episodeId: id, watchEventId: id }),
  'viewing.put': (v) => shape({ kind: text, titleId: id, viewing })(v) && record(v) && record(v.viewing) && v.titleId === v.viewing.titleId,
  'viewing.patch': shape({ kind: text, titleId: id, viewingId: id, patch: patch({}, viewingOptional) }),
  'viewing.delete': shape({ kind: text, titleId: id, viewingId: id }),
  'outing.create': shape({ kind: text, outing }),
  'outing.patch': shape({ kind: text, outingId: id, patch: patch(outingRequired, outingOptional) }),
  'outing.delete': shape({ kind: text, outingId: id }),
  'list.create': shape({ kind: text, list }),
  'list.patch': shape({ kind: text, listId: id, patch: patch({ name: text, description: nullable(text) }), updatedAt: timestamp }),
  'list.delete': shape({ kind: text, listId: id }),
  'membership.set': shape({ kind: text, listId: id, titleId: id, present: boolean }),
  'pin.set': shape({ kind: text, titleId: id, easterEggKey: id, variant: nullable(color) }),
  'ledger.set': shape({ kind: text, widgets: array(widget) }),
  'external.link': shape({ kind: text, titleId: id, provider: oneOf('letterboxd', 'simkl', 'plex', 'emby'), externalId: id }),
}
const revisionTable = oneOf('titles', 'lists', 'cinema_outings', 'seasons', 'episodes', 'viewings', 'episode_watch_events', 'episode_ratings', 'episode_reviews')
const precondition: Check = (value) => shape({ table: revisionTable, id, updatedAt: timestamp })(value) ||
  shape({ table: revisionTable, id, afterCommandId: id })(value)
function isLeaf(value: unknown): boolean {
  return record(value) && typeof value.kind === 'string' && Object.hasOwn(checks, value.kind) && checks[value.kind](value)
}
export function assertMutation(value: unknown): asserts value is Mutation {
  const valid = isLeaf(value) || isValidTicketMutation(value) || (record(value) && value.kind === 'batch' &&
    shape({ kind: text, mutations: (v) => Array.isArray(v) && v.length > 0 && v.length <= 10_000 && v.every(isLeaf) })(value))
  if (!valid) throw new Error('Invalid or unsupported offline mutation')
}
export function assertScope(value: unknown): asserts value is OfflineScope {
  if (!shape({ projectId: id, userId: id })(value)) throw new Error('Offline storage requires a project and authenticated owner')
}
export function assertCommand(value: unknown): asserts value is PendingCommand {
  if (!shape({
    version: oneOf(1), id, scope: (v) => shape({ projectId: id, userId: id })(v), createdAt: timestamp,
    sequence: integer, mutation: (v) => { try { assertMutation(v); return true } catch { return false } },
    dependsOn: array(id), state: oneOf('pending', 'failed', 'conflict'), attempts: integer, nextAttemptAt: integer,
  }, { baseRevision: text, lastError: text,
    preconditions: array(precondition),
  })(value)) throw new Error('Invalid or unsupported offline command')
  const command = value as PendingCommand
  if (isTicketMutation(command.mutation)) {
    if (!isTicketId(command.id) || command.baseRevision || command.preconditions?.length) throw new Error('Ticket commands use attachment CAS, not row revision guards')
    if (command.mutation.kind === 'ticket.attach' && command.mutation.attachment.objectKey !== ticketObjectKey(command.scope, command.mutation.attachment.id)) throw new Error('Ticket attachment belongs to another owner')
  }
  const rows = mutationRows(command.mutation)
  const guarded = new Set<string>()
  for (const condition of command.preconditions ?? []) {
    const key = `${condition.table}:${condition.id}`
    if (guarded.has(key) || condition.afterCommandId === command.id || !rows.some((row) => row.guard && row.table === condition.table && row.id === condition.id)) {
      throw new Error('Invalid offline row precondition')
    }
    guarded.add(key)
  }
}
export function assertSnapshot(value: unknown): asserts value is OfflineSnapshot {
  if (!shape({ titles: array(title), outings: array(outingSnapshot), lists: array(list),
    listMemberships: dictionary(strings), pinnedModes: dictionary(color), ledgerWidgets: nullable(array(widget)),
  }, { rowRevisions: dictionary(timestamp), ticketAttachmentSupport: (v) => v === 'authoritative' || v === 'unsupported' })(value)) throw new Error('Invalid or unsupported offline snapshot')
}
