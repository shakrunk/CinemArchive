import { useState } from 'react'
import { useAppStore } from '../store/useAppStore'
import { normalizeVenue, venueCommands, type VenueNoteDraft, type VenueNoteReview } from '../lib/venueNotes'

export function VenueNoteEditor({ venue }: { venue: string }) {
  const session = useAppStore((state) => state.librarySession)
  const name = normalizeVenue(venue)
  return <Editor key={`${session}:${name}`} venue={name} />
}
function Editor({ venue }: { venue: string }) {
  const owner = useAppStore((state) => !!state.user && !state.isSharedView && state.viewerContext.kind === 'owner')
  const supported = useAppStore((state) => state.moviegoingPreferencesSupport === 'authoritative')
  const current = useAppStore((state) => state.venueNotes.find((note) => note.venue === venue))
  const pending = useAppStore((state) => state.offlineStatus.commands)
  const open = useAppStore((state) => state.openVenueNote)
  const save = useAppStore((state) => state.saveVenueNote)
  const retry = useAppStore((state) => state.retryPendingCommand)
  const compare = useAppStore((state) => state.reviewVenueNote)
  const resolve = useAppStore((state) => state.resolveVenueNote)
  const [draft, setDraft] = useState<(VenueNoteDraft & { session: number }) | null>(null)
  const [text, setText] = useState('')
  const [review, setReview] = useState<VenueNoteReview | null>(null)
  const [localComparison, setLocalComparison] = useState<(VenueNoteDraft & { session: number }) | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  if (!owner || !venue) return null
  const changes = venueCommands(pending, venue), head = changes[0]
  const run = async (work: () => Promise<void>) => {
    setBusy(true); setError(null)
    try { await work() } catch (failure) { setError(failure instanceof Error ? failure.message : 'Could not save private venue notes') }
    finally { setBusy(false) }
  }
  const start = () => {
    try { const opening = open(venue); setDraft(opening); setLocalComparison(null); setText(opening.baseline?.notes ?? ''); setError(null) }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'Could not open private venue notes') }
  }
  const saveDraft = (notes: string | null) => { if (draft) void run(async () => { await save(draft, notes); setDraft(null) }) }
  const button = 'text-xs text-amber underline disabled:opacity-50'
  return <section aria-label={`Private notes for ${venue}`} className="mt-3 space-y-2 rounded-md border border-border p-3">
    <p className="text-sm font-medium">Private venue notes</p>
    <p className="text-xs text-paper-dim">Parking, accessibility, or other details for {venue}. These notes are not shared with friends.</p>
    <p className="whitespace-pre-wrap break-words text-sm">{current ? current.notes || 'Empty note' : 'No saved venue note'}</p>
    {!supported && <p className="text-xs text-paper-dim">Sync with the updated server to edit notes. Existing notes remain saved on this device.</p>}
    {!draft && <button type="button" className={button} disabled={!supported || busy} onClick={start}>{current ? 'Edit venue note' : 'Add venue note'}</button>}
    {draft && <div className="space-y-2">
      <label className="block text-sm">Venue note<textarea aria-label="Venue note" value={text} disabled={busy} onChange={(event) => setText(event.target.value)}
        rows={4} className="mt-1 block w-full rounded-md border border-border bg-secondary/50 p-2" /></label>
      <p className="text-xs text-paper-dim">{[...text].length}/20,000 characters. An empty note is saved; removal is separate.</p>
      <div className="flex flex-wrap gap-3">
        <button type="button" className={button} disabled={busy} onClick={() => saveDraft(text)}>{busy ? 'Saving…' : 'Save venue note'}</button>
        {draft.baseline && <button type="button" className={button} disabled={busy} onClick={() => saveDraft(null)}>Remove venue note</button>}
        <button type="button" className={button} disabled={busy} onClick={() => { setDraft(null); setLocalComparison(null) }}>Cancel editing</button>
      </div>
      {error?.includes('changed while the editor') && <button type="button" className={button} disabled={busy} onClick={() => {
        try { setLocalComparison(open(venue)) } catch { /* The original failure and draft remain visible. */ }
      }}>Keep my draft and compare the latest note</button>}
      {localComparison && <div className="space-y-2 border-t border-border pt-2">
        <p className="whitespace-pre-wrap break-words text-sm">Latest saved note: {localComparison.baseline ? localComparison.baseline.notes || 'Empty note' : 'No saved note'}</p>
        <p className="whitespace-pre-wrap break-words text-sm">Your unsaved draft: {text || 'Empty note'}</p>
        <button type="button" className={button} disabled={busy} onClick={() => { void run(async () => {
          await save(localComparison, text); setDraft(null); setLocalComparison(null)
        }) }}>Save my draft instead</button>
        <button type="button" className={`${button} ml-3`} disabled={busy} onClick={() => { setDraft(null); setLocalComparison(null); setError(null) }}>Keep latest saved note</button>
      </div>}
    </div>}
    {head && <div className="space-y-1">
      <p role="status" className="text-xs text-paper-dim">{head.lastError ?? 'Venue note saved on this device; waiting to sync.'}</p>
      <button type="button" className={button} disabled={busy} onClick={() => { void run(() => retry(head.id)) }}>Retry saved venue note</button>
      {head.venueRejection && <button type="button" className={`${button} ml-3`} disabled={busy} onClick={() => { void run(async () => setReview(await compare(head.id))) }}>Compare server note</button>}
    </div>}
    {review && <div className="space-y-2 border-t border-border pt-2">
      <p className="text-sm font-medium">Compare venue notes</p>
      <p className="whitespace-pre-wrap break-words text-sm">Server: {review.current ? review.current.notes || 'Empty note' : 'No saved note'}</p>
      <p className="whitespace-pre-wrap break-words text-sm">Your latest saved change: {review.notes === null ? 'Remove note' : review.notes || 'Empty note'}</p>
      <p className="text-xs text-paper-dim">This resolves {review.commands.length} saved change(s). The server note is checked again before saving.</p>
      <div className="flex flex-wrap gap-3">{[false, true].map((keepLocal) => <button key={String(keepLocal)} type="button" className={button} disabled={busy}
        onClick={() => { void run(async () => { await resolve(review, keepLocal); setReview(null); setDraft(null) }) }}>{keepLocal ? 'Use my saved change' : 'Keep server note'}</button>)}</div>
    </div>}
    {error && <p role="alert" className="text-sm text-red-400">{error}</p>}
  </section>
}
