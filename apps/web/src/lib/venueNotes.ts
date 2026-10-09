import type { PendingCommand } from './offline/commands'
import type { OfflineSnapshot } from './offline/snapshot'
import { isVenueNote, type VenueNote } from './moviegoingPreferences'
import { isTicketId } from './tickets/validation'

/** Actionable admission/recovery failures, distinct from unavailable device storage. */
export class VenueNoteError extends Error {}

export interface VenueNoteMutation {
  kind: 'venueNote.change'
  venue: string
  userId: string
  notes: string | null
  localId: string
  recordedAt: string
  /** The editor's opening snapshot, never silently replaced at save time. */
  baseline: VenueNote | null
  previousCommandId?: string
}
export interface VenueNoteDraft {
  venue: string
  userId: string
  baseline: VenueNote | null
  previousCommandId?: string
}
export interface VenueNoteReview {
  commands: PendingCommand[]
  current: VenueNote | null
  notes: string | null
}
export const normalizeVenue = (venue: string): string => venue.replace(/^ +| +$/g, '')
export const sameVenueNote = (a: VenueNote | null, b: VenueNote | null): boolean => JSON.stringify(a) === JSON.stringify(b)
export function venueCommands(commands: readonly PendingCommand[], venue: string): PendingCommand[] {
  return commands.filter((command) => command.mutation.kind === 'venueNote.change' && command.mutation.venue === venue)
}
export function isVenueNoteMutation(value: unknown): value is VenueNoteMutation {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const v = value as Record<string, unknown>
  if (Object.keys(v).some((key) => !['kind', 'venue', 'userId', 'notes', 'localId', 'recordedAt', 'baseline', 'previousCommandId'].includes(key))) return false
  return v.kind === 'venueNote.change' && (v.notes === null || typeof v.notes === 'string') &&
    isVenueNote({ id: v.localId, userId: v.userId, venue: v.venue, notes: v.notes ?? '', createdAt: v.recordedAt, updatedAt: v.recordedAt }) &&
    (v.baseline === null || (isVenueNote(v.baseline) && v.baseline.userId === v.userId && v.baseline.venue === v.venue)) &&
    (v.notes !== null || v.baseline !== null) &&
    (v.previousCommandId === undefined || isTicketId(v.previousCommandId))
}
export function captureVenueDraft(snapshot: Pick<OfflineSnapshot, 'venueNotes'>, commands: readonly PendingCommand[], userId: string, rawVenue: string): VenueNoteDraft {
  const venue = normalizeVenue(rawVenue)
  const baseline = snapshot.venueNotes?.find((row) => row.venue === venue) ?? null
  const pending = venueCommands(commands, venue)
  return { venue, userId, baseline: baseline ? { ...baseline } : null,
    ...(pending.length ? { previousCommandId: pending[pending.length - 1].id } : {}) }
}
export function venueChange(draft: VenueNoteDraft, notes: string | null): VenueNoteMutation {
  return { kind: 'venueNote.change', ...draft, notes, localId: crypto.randomUUID(), recordedAt: new Date().toISOString() }
}
export function assertVenueAdmission(mutation: VenueNoteMutation, snapshot: OfflineSnapshot, commands: readonly PendingCommand[]): void {
  if (snapshot.moviegoingPreferencesSupport !== 'authoritative') throw new VenueNoteError('Sync moviegoing preferences with the updated server before editing venue notes')
  const current = captureVenueDraft(snapshot, commands, mutation.userId, mutation.venue)
  if (!sameVenueNote(current.baseline, mutation.baseline) || current.previousCommandId !== mutation.previousCommandId) {
    throw new VenueNoteError('This venue note changed while the editor was open. Keep your draft and compare the latest note before saving.')
  }
}
export function checkedVenueChain(commands: readonly PendingCommand[], commandId: string): PendingCommand[] {
  const head = commands.find((command) => command.id === commandId)
  if (!head || head.mutation.kind !== 'venueNote.change' || head.state !== 'conflict' || !head.venueRejection) {
    throw new VenueNoteError('Delivery is not known to be rejected. Retry the same saved operation to confirm it.')
  }
  const chain = venueCommands(commands, head.mutation.venue)
  if (chain[0].id !== head.id || chain.slice(1).some((command) => command.attempts !== 0 || command.state !== 'pending')) throw new VenueNoteError('Confirm earlier or uncertain venue changes before reviewing this note')
  const ids = new Set(chain.map((command) => command.id))
  if (commands.some((command) => !ids.has(command.id) && command.dependsOn.some((id) => ids.has(id)))) throw new VenueNoteError('Other saved changes depend on this venue note')
  return chain
}
