import { useState } from 'react'
import { Film } from 'lucide-react'
import { useAppStore } from '../store/useAppStore'

export function TheaterInterestControl({ titleId }: { titleId: string }) {
  const session = useAppStore((state) => state.librarySession)
  return <InterestButton key={`${session}:${titleId}`} titleId={titleId} />
}

function InterestButton({ titleId }: { titleId: string }) {
  const owner = useAppStore((state) => !!state.user && !state.isSharedView && state.viewerContext.kind === 'owner')
  const movie = useAppStore((state) => state.titles.some((title) => title.id === titleId && title.type === 'movie'))
  const scheduled = useAppStore((state) => state.outings.some((outing) => outing.titleId === titleId && outing.status === 'scheduled'))
  const selected = useAppStore((state) => state.theaterInterest.some((row) => row.titleId === titleId))
  const supported = useAppStore((state) => state.moviegoingPreferencesSupport === 'authoritative')
  const hydrated = useAppStore((state) => state.offlineStatus.hydrated)
  const pending = useAppStore((state) => [...state.offlineStatus.commands].reverse().find((command) =>
    (command.mutation.kind === 'batch' ? command.mutation.mutations : [command.mutation])
      .some((mutation) => mutation.kind === 'theaterInterest.set' && mutation.titleId === titleId)))
  const save = useAppStore((state) => state.setTheaterInterest)
  const retry = useAppStore((state) => state.retryPendingCommand)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  if (!owner || !movie || scheduled) return null
  const perform = async (action: () => Promise<void>) => {
    setBusy(true); setError(null)
    try { await action() } catch (failure) { setError(failure instanceof Error ? failure.message : 'Could not save this theater preference') }
    finally { setBusy(false) }
  }
  return <div className="space-y-1.5">
    <button type="button" aria-pressed={selected} disabled={busy || !supported || !hydrated}
      onClick={() => { void perform(() => save(titleId, !selected)) }}
      className="inline-flex items-center gap-2 rounded-full border border-amber/30 px-3 py-2 text-sm text-amber disabled:opacity-60 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-amber">
      <Film className="h-4 w-4" />{busy ? 'Saving…' : selected ? 'Want to see in theaters' : 'Want to see in theaters?'}
    </button>
    {!supported && <p className="text-xs text-paper-dim">Sync with the updated server to edit this preference. Saved preferences remain on this device.</p>}
    {pending && <p role="status" className="text-xs text-paper-dim">{pending.lastError ?? 'Saved on this device; waiting to sync.'}</p>}
    {pending && pending.state !== 'pending' && <button type="button" disabled={busy} className="text-xs text-amber underline"
      onClick={() => { void perform(() => retry(pending.id)) }}>Retry saved preference</button>}
    {error && <p role="alert" className="text-sm text-red-400">{error}</p>}
  </div>
}
