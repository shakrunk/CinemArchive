import { useEffect, useId, useRef, useState } from 'react'
import { Camera, Images, ImagePlus } from 'lucide-react'
import { isSupabaseConfigured } from '../lib/auth'
import { isDevMockUser } from '../lib/devAuth'
import {
  addKeepsake, canAddKeepsake, discardPendingKeepsake, finishKeepsake, keepsakeUrl, KeepsakeSaveError, listKeepsakes, MAX_KEEPSAKES,
  pendingKeepsakes, prepareKeepsake, removeKeepsake, replaceKeepsake, type OutingKeepsake, type PendingKeepsake,
} from '../lib/keepsakes'
import { useAppStore } from '../store/useAppStore'
import { CinemaModal } from './ui/cinema-modal'
import { Button } from './ui/button'

const errorMessage = (error: unknown, fallback: string) => error instanceof Error && error.message ? error.message : fallback

/** Entry point beside the ticket button (#208). Private owner view only, and only with a real account. */
export function KeepsakeButton({ outingId }: { outingId: string }) {
  const user = useAppStore((s) => s.user)
  const privateView = useAppStore((s) => !s.isSharedView && s.viewerContext.kind === 'owner')
  const outing = useAppStore((s) => s.outings.find((row) => row.id === outingId))
  const title = useAppStore((s) => s.titles.find((row) => row.id === s.outings.find((o) => o.id === outingId)?.titleId)?.title ?? 'Cinema outing')
  const [open, setOpen] = useState(false)
  if (!isSupabaseConfigured || !user || isDevMockUser(user) || !privateView || !outing) return null
  return <>
    <button type="button" onClick={(event) => { event.stopPropagation(); setOpen(true) }}
      className="inline-flex items-center gap-1.5 rounded-sm text-sm font-sans text-amber hover:text-amber-muted focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-amber">
      <Images className="h-4 w-4" aria-hidden="true" />Photos
    </button>
    {open && <KeepsakeAlbum key={outingId} ownerId={user.id} outingId={outingId} title={title} onClose={() => setOpen(false)} />}
  </>
}

function KeepsakeThumbnail({ ownerId, keepsake, onRemove, onReplace, disabled }: {
  ownerId: string; keepsake: OutingKeepsake; onRemove: (keepsake: OutingKeepsake) => void
  onReplace: (keepsake: OutingKeepsake, file: File) => void; disabled: boolean
}) {
  const replaceId = useId()
  const [url, setUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let current = true
    keepsakeUrl(ownerId, keepsake).then((value) => { if (current) setUrl(value) }, () => { if (current) setFailed(true) })
    return () => { current = false }
  }, [ownerId, keepsake])
  return <li className="space-y-1">
    <div className="aspect-square overflow-hidden rounded-md bg-secondary/40 flex items-center justify-center">
      {url ? <a href={url} target="_blank" rel="noreferrer noopener" aria-label="Open photo full size" className="block h-full w-full">
        <img src={url} alt="Keepsake photo" className="h-full w-full object-cover" onError={() => setFailed(true)} />
      </a> : <span className="text-xs text-muted-foreground">{failed ? "Couldn't load" : 'Loading…'}</span>}
    </div>
    <div className="flex flex-wrap items-center gap-1">
      <label htmlFor={replaceId} className={`inline-flex items-center rounded-md px-3 py-1.5 text-sm focus-within:ring-2 focus-within:ring-amber ${disabled ? 'opacity-50' : 'cursor-pointer hover:bg-secondary'}`}>
        Replace photo
        <input id={replaceId} aria-label="Replace photo" type="file" accept="image/jpeg,image/png,image/webp" disabled={disabled} className="sr-only"
          onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ''; if (file) onReplace(keepsake, file) }} />
      </label>
      <Button type="button" size="sm" variant="ghost" disabled={disabled} onClick={() => onRemove(keepsake)}>Remove photo</Button>
    </div>
  </li>
}

function KeepsakeAlbum({ ownerId, outingId, title, onClose }: { ownerId: string; outingId: string; title: string; onClose: () => void }) {
  const fileId = useId(), cameraId = useId()
  const [keepsakes, setKeepsakes] = useState<OutingKeepsake[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState<OutingKeepsake | null>(null)
  const [attempt, setAttempt] = useState(0)
  // Photos whose bytes uploaded but whose row did not save, kept in this browser (see lib/keepsakes).
  const [pending, setPending] = useState<PendingKeepsake[]>(() => pendingKeepsakes(ownerId, outingId))
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const refreshPending = () => setPending(pendingKeepsakes(ownerId, outingId))

  useEffect(() => {
    let current = true
    listKeepsakes(outingId).then(
      (list) => {
        if (!current) return
        // A pending photo that already has a row was saved after all: its reply was lost.
        for (const saved of pendingKeepsakes(ownerId, outingId)) if (list.some((row) => row.id === saved.id)) discardPendingKeepsake(ownerId, saved.id)
        setKeepsakes(list); setPending(pendingKeepsakes(ownerId, outingId)); setError(null)
      },
      (cause) => { if (current) { setKeepsakes([]); setError(errorMessage(cause, "Couldn't load your photos. Check your connection.")) } },
    )
    return () => { current = false }
  }, [ownerId, outingId, attempt])

  async function add(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file || busy) return
    setBusy(true); setError(null)
    try {
      const saved = await addKeepsake(ownerId, outingId, await prepareKeepsake(file))
      if (mounted.current) setKeepsakes((list) => [...(list ?? []), saved])
    } catch (cause) {
      if (mounted.current) setError(errorMessage(cause, "Couldn't add that photo. Check your connection and try again."))
    } finally { if (mounted.current) { setBusy(false); refreshPending() } }
  }
  async function replace(old: OutingKeepsake, file: File) {
    if (busy) return
    setBusy(true); setError(null)
    try {
      const { saved, oldRemoved } = await replaceKeepsake(ownerId, old, await prepareKeepsake(file), keepsakes?.length ?? 0)
      if (mounted.current) {
        setKeepsakes((list) => (list ?? []).filter((row) => !oldRemoved || row.id !== old.id).concat(saved))
        if (!oldRemoved) setError("The new photo is saved, but the old one couldn't be removed. Remove it to finish the swap.")
      }
    } catch (cause) {
      if (mounted.current) {
        const reason = errorMessage(cause, "Couldn't replace that photo. Check your connection and try again.")
        if (cause instanceof KeepsakeSaveError && cause.originalRemoved) setKeepsakes((list) => (list ?? []).filter((row) => row.id !== old.id))
        setError(cause instanceof KeepsakeSaveError && cause.originalRemoved
          ? `${reason} Your original photo was removed to make room: finish saving the new one below, or add the original again.`
          : `${reason} Your original photo is unchanged.`)
      }
    } finally { if (mounted.current) { setBusy(false); refreshPending() } }
  }
  async function finish(photo: PendingKeepsake) {
    if (busy) return
    setBusy(true); setError(null)
    try {
      const saved = await finishKeepsake(ownerId, photo)
      if (mounted.current) setKeepsakes((list) => (list ?? []).some((row) => row.id === saved.id) ? list : [...(list ?? []), saved])
    } catch (cause) {
      if (mounted.current) setError(errorMessage(cause, "Couldn't save that photo. Check your connection and try again."))
    } finally { if (mounted.current) { setBusy(false); refreshPending() } }
  }
  function discard(photo: PendingKeepsake) {
    discardPendingKeepsake(ownerId, photo.id)
    refreshPending()
  }
  async function remove(keepsake: OutingKeepsake) {
    if (busy) return
    setBusy(true); setError(null)
    try {
      await removeKeepsake(ownerId, keepsake.id)
      if (mounted.current) { setKeepsakes((list) => (list ?? []).filter((row) => row.id !== keepsake.id)); setConfirming(null) }
    } catch (cause) {
      if (mounted.current) setError(errorMessage(cause, "Couldn't remove that photo. Check your connection and try again."))
    } finally { if (mounted.current) setBusy(false) }
  }

  const room = canAddKeepsake(keepsakes?.length ?? 0)
  const disabled = busy || keepsakes === null || !room
  const label = `inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-2 text-sm focus-within:ring-2 focus-within:ring-amber ${disabled ? 'opacity-50' : 'cursor-pointer hover:bg-secondary'}`
  return <CinemaModal open onClose={onClose} title={`Photos · ${title}`} description="Keepsake photos kept with this cinema trip.">
    <div className="space-y-4 px-6 py-6 text-left">
      <h2 className="font-serif text-xl font-light text-foreground">Photos · {title}</h2>
      <p className="text-xs text-muted-foreground">
        Keep a ticket stub, the poster board or a selfie with this trip. Private to you · JPEG, PNG or WebP · needs a connection to add or view.
      </p>
      {keepsakes === null ? <p role="status" className="text-sm text-muted-foreground">Loading photos…</p>
        : keepsakes.length === 0 ? <p className="text-sm text-muted-foreground">No photos yet.</p>
        : <ul aria-label="Keepsake photos" className="grid grid-cols-2 gap-3">
          {keepsakes.map((keepsake) => <KeepsakeThumbnail key={keepsake.id} ownerId={ownerId} keepsake={keepsake} disabled={busy} onRemove={setConfirming} onReplace={(row, file) => void replace(row, file)} />)}
        </ul>}
      <div className="flex flex-wrap gap-3">
        <label htmlFor={fileId} className={label}>
          <ImagePlus className="h-4 w-4" />Add photo
          <input id={fileId} aria-label="Add photo" type="file" accept="image/jpeg,image/png,image/webp" disabled={disabled} onChange={(event) => void add(event)} className="sr-only" />
        </label>
        <label htmlFor={cameraId} className={label}>
          <Camera className="h-4 w-4" />Take photo
          <input id={cameraId} aria-label="Take photo" type="file" accept="image/jpeg,image/png,image/webp" capture="environment" disabled={disabled} onChange={(event) => void add(event)} className="sr-only" />
        </label>
      </div>
      {!room && <p className="text-xs text-muted-foreground">This trip keeps up to {MAX_KEEPSAKES} photos. Remove one to add another, or replace one.</p>}
      {pending.length > 0 && <ul aria-label="Photos not saved yet" className="space-y-2">
        {pending.map((photo) => <li key={photo.id} className="flex flex-wrap items-center gap-2 text-sm">
          <span>A photo uploaded but wasn't saved to this trip.</span>
          <Button type="button" size="sm" variant="outline" disabled={busy || !room} onClick={() => void finish(photo)}>Finish saving</Button>
          <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={() => discard(photo)}>Discard</Button>
        </li>)}
      </ul>}
      {confirming && <div className="flex flex-wrap items-center gap-2 text-sm">
        <span>Remove this photo? It leaves this trip right away; its stored copy is deleted within a couple of days.</span>
        <Button type="button" variant="outline" disabled={busy} onClick={() => void remove(confirming)}>Confirm removal</Button>
        <Button type="button" variant="ghost" disabled={busy} onClick={() => setConfirming(null)}>Keep photo</Button>
      </div>}
      {busy && <p role="status" className="text-sm text-muted-foreground">Working…</p>}
      {error && <div className="space-y-1">
        <p role="alert" className="text-sm text-destructive">{error}</p>
        <Button type="button" size="sm" variant="ghost" onClick={() => setAttempt((n) => n + 1)}>Try again</Button>
      </div>}
    </div>
  </CinemaModal>
}
