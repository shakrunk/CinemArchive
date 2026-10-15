import { useState } from 'react'
import { useAppStore } from '../store/useAppStore'
import { LEGACY_LIBRARY_KEY } from '../store/offlineLibrary'
import { CinemaModal } from './ui/cinema-modal'

function download(name: string, content: string) {
  const url = URL.createObjectURL(new Blob([content], { type: 'application/json' }))
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = name; anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

/**
 * Full review of local durability and delivery: pending changes, damaged-cache
 * recovery, and the unowned legacy cache. Opened from the library sync toast
 * (LibrarySyncToast) or Settings → Data & Portability.
 */
export function LibrarySyncDialog() {
  const open = useAppStore((s) => s.isLibrarySyncOpen)
  const shared = useAppStore((s) => s.isSharedView)
  const close = useAppStore((s) => s.closeLibrarySync)
  return (
    <CinemaModal open={open && !shared} onClose={close} title="Library sync" maxWidth="sm:max-w-xl">
      <LibrarySyncDetails />
    </CinemaModal>
  )
}

export function LibrarySyncDetails() {
  const status = useAppStore((s) => s.offlineStatus)
  const shared = useAppStore((s) => s.isSharedView)
  const storageError = useAppStore((s) => s.offlineStorageError)
  const syncError = useAppStore((s) => s.offlineSyncError)
  const legacy = useAppStore((s) => s.legacyCacheAvailable)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirmDiscard, setConfirmDiscard] = useState<string | null>(null)
  if (shared) return null
  async function run(work: () => Promise<void>) {
    setBusy(true); setError(null)
    try { await work(); setConfirmDiscard(null) }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Unable to finish this action. Your saved work is retained.') }
    finally { setBusy(false) }
  }
  const button = 'rounded border border-border px-3 py-1 text-sm disabled:opacity-50'
  const nothingToReview = !status.commands.length && !status.quarantined.length && !storageError && !syncError && !legacy
  return <section aria-label="Library sync" className="min-h-0 overflow-y-auto p-5 pr-14 text-paper">
    <h2 className="font-serif text-lg">
      {storageError ? 'A change could not be saved on this device' : status.quarantined.length ? 'Local library recovery required' : status.commands.length ? `${status.commands.length} saved change${status.commands.length === 1 ? '' : 's'} waiting to sync` : 'Library recovery and sync'}
    </h2>
    <div className="mt-3 space-y-3 text-sm">
      {(storageError || syncError || error) && <p role="alert">{error ?? storageError ?? syncError}</p>}
      {nothingToReview && !error && <p role="status">Nothing on this device is waiting to sync.</p>}
      {status.commands.length > 0 && <>
        <p>These changes are saved on this device. Keep this browser’s site data until they have synced.</p>
        <div className="flex flex-wrap gap-2">
          <button className={button} disabled={busy} onClick={() => void run(() => useAppStore.getState().retryLibrarySync())}>Sync now</button>
          <button className={button} onClick={() => download('cinemarchive-pending-changes.json', JSON.stringify(status.commands, null, 2))}>Export pending changes</button>
        </div>
        <ul className="space-y-2">{status.commands.map((command) => <li key={command.id} className="rounded border border-border p-2">
          <p>{command.mutation.kind === 'batch' ? 'Related library changes' : command.mutation.kind.replace('.', ': ')} · {command.state}</p>
          {command.lastError && <p>{command.lastError}</p>}
          {command.state === 'conflict' && <p>Another change conflicts with this saved edit. Export your pending changes before discarding it, then reapply the edit against the refreshed library.</p>}
          {command.dependsOn.length > 0 && <p>Waiting for {command.dependsOn.length} earlier related change{command.dependsOn.length === 1 ? '' : 's'}.</p>}
          <div className="mt-2 flex flex-wrap gap-2">
            {command.state !== 'pending' && <button className={button} disabled={busy} onClick={() => void run(() => useAppStore.getState().retryPendingCommand(command.id))}>Retry</button>}
            <button className={button} disabled={busy} onClick={() => setConfirmDiscard(command.id)}>Discard…</button>
            {confirmDiscard === command.id && <><span>Remove this saved change? Related changes must be removed first.</span><button className={button} disabled={busy} onClick={() => void run(() => useAppStore.getState().discardPendingCommand(command.id))}>Confirm discard</button><button className={button} onClick={() => setConfirmDiscard(null)}>Keep</button></>}
          </div>
        </li>)}</ul>
      </>}
      {!status.commands.length && syncError && <button className={button} disabled={busy} onClick={() => void run(() => useAppStore.getState().retryLibrarySync())}>Retry sync</button>}
      {status.quarantined.length > 0 && <>
        <p>Damaged local data has been preserved for recovery. Export it before clearing it; unsynced changes in that data may be lost when cleared.</p>
        <div className="flex flex-wrap gap-2">
          <button className={button} onClick={() => download('cinemarchive-recovery.json', JSON.stringify(status.quarantined, null, 2))}>Export damaged data</button>
          <button className={button} disabled={busy} onClick={() => setConfirmDiscard('quarantine')}>Clear damaged cache…</button>
          {confirmDiscard === 'quarantine' && <button className={button} disabled={busy} onClick={() => void run(() => useAppStore.getState().discardDamagedCache())}>Confirm clear and reload</button>}
        </div>
      </>}
      {legacy && <>
        <p>An older library cache has no verified account owner. It is preserved separately. Export it, check its contents, then import it into the intended account from Settings.</p>
        <button className={button} onClick={() => { try { download('cinemarchive-legacy-cache.json', localStorage.getItem(LEGACY_LIBRARY_KEY) ?? '{}') } catch { setError('The older cache cannot be read in this browser.') } }}>Export older cache</button>
      </>}
    </div>
  </section>
}
