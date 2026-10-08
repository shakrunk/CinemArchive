import { useEffect, useId, useRef, useState } from 'react'
import { Camera, ImagePlus, Ticket } from 'lucide-react'
import { useAppStore } from '../store/useAppStore'
import type { CinemaOuting } from '../store/mockData'
import type { TicketCapture } from '../lib/tickets/types'
import { formatSeatLine } from '../lib/seating'
import { CinemaModal } from './ui/cinema-modal'
import { Button } from './ui/button'

const errorMessage = (error: unknown) => error instanceof Error ? error.message : 'The ticket photo could not be saved. Please retry.'

/** Entry points always resolve current owner data, never a stale outing prop. */
export function TicketButton({ outingId }: { outingId: string }) {
  const session = useAppStore((s) => s.librarySession)
  const privateView = useAppStore((s) => !s.isSharedView && s.viewerContext.kind === 'owner')
  const outing = useAppStore((s) => s.outings.find((row) => row.id === outingId))
  const [openedSession, setOpenedSession] = useState<number | null>(null)
  if (!privateView || !outing) return null
  return <>
    <button type="button" onClick={(event) => { event.stopPropagation(); setOpenedSession(session) }}
      className="inline-flex items-center gap-1.5 rounded-sm text-sm font-sans text-amber hover:text-amber-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber/60">
      <Ticket className="h-4 w-4" aria-hidden="true" />Ticket & seats
    </button>
    {openedSession === session && <TicketViewer key={`${session}:${outingId}`} outing={outing} onClose={() => setOpenedSession(null)} />}
  </>
}

function TicketCaptureControls({ outing, session }: { outing: CinemaOuting; session: number }) {
  const fileId = useId(), cameraId = useId()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [retry, setRetry] = useState<{ blob: Blob; capture: TicketCapture } | null>(null)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const mounted = useRef(true)
  const saving = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const current = () => mounted.current && useAppStore.getState().librarySession === session && !useAppStore.getState().isSharedView && useAppStore.getState().viewerContext.kind === 'owner'

  async function save(blob: Blob, prepared?: TicketCapture) {
    if (saving.current) return
    saving.current = true
    setBusy(true); setError(null)
    let capture = prepared
    try {
      capture ??= await (await import('../lib/tickets/barcode')).prepareTicketCapture(blob)
      if (!current()) return
      await useAppStore.getState().attachOutingTicket(outing.id, capture, blob)
      if (current()) { setRetry(null); setConfirmRemove(false) }
    } catch (cause) {
      if (current()) { setError(errorMessage(cause)); if (capture) setRetry({ blob, capture }) }
    } finally { saving.current = false; if (current()) setBusy(false) }
  }
  async function remove() {
    if (saving.current) return
    saving.current = true
    setBusy(true); setError(null)
    try {
      await useAppStore.getState().detachOutingTicket(outing.id)
      if (current()) { setConfirmRemove(false); setRetry(null) }
    } catch (cause) { if (current()) setError(errorMessage(cause)) }
    finally { saving.current = false; if (current()) setBusy(false) }
  }
  const selectFile = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (file) { setRetry(null); void save(file) }
  }
  const hasTicket = !!outing.ticketAttachment || !outing.ticketManaged && !!outing.ticketImagePath
  return <div className="space-y-3 border-t border-border pt-4 text-left">
    <p className="text-xs text-muted-foreground">Private ticket photo · JPEG, PNG or WebP · up to 20 MiB</p>
    <div className="flex flex-wrap gap-3">
      <label htmlFor={fileId} className={`inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-2 text-sm focus-within:ring-2 focus-within:ring-amber ${busy ? 'opacity-50' : 'cursor-pointer hover:bg-secondary'}`}>
        <ImagePlus className="h-4 w-4" />{hasTicket ? 'Replace photo' : 'Add ticket photo'}
        <input id={fileId} aria-label={hasTicket ? 'Replace ticket photo' : 'Add ticket photo'} type="file" accept="image/jpeg,image/png,image/webp" disabled={busy} onChange={selectFile} className="sr-only" />
      </label>
      <label htmlFor={cameraId} className={`inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-2 text-sm focus-within:ring-2 focus-within:ring-amber ${busy ? 'opacity-50' : 'cursor-pointer hover:bg-secondary'}`}>
        <Camera className="h-4 w-4" />Take photo
        <input id={cameraId} aria-label="Take ticket photo" type="file" accept="image/jpeg,image/png,image/webp" capture="environment" disabled={busy} onChange={selectFile} className="sr-only" />
      </label>
      {hasTicket && !confirmRemove && <Button type="button" variant="outline" disabled={busy} onClick={() => setConfirmRemove(true)}>Remove photo</Button>}
    </div>
    {confirmRemove && <div className="flex flex-wrap items-center gap-2 text-sm"><span>Remove this ticket photo?</span>
      <Button type="button" variant="outline" disabled={busy} onClick={() => void remove()}>Confirm removal</Button>
      <Button type="button" variant="ghost" disabled={busy} onClick={() => setConfirmRemove(false)}>Keep photo</Button>
    </div>}
    {busy && <p role="status" className="text-sm text-muted-foreground">Saving ticket photo…</p>}
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    {retry && !busy && <Button type="button" variant="outline" onClick={() => void save(retry.blob, retry.capture)}>Retry saving photo</Button>}
  </div>
}

function TicketPhoto({ outing, scan }: { outing: CinemaOuting; scan: boolean }) {
  const [photo, setPhoto] = useState<string | null>(null)
  const [barcode, setBarcode] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [retry, setRetry] = useState(0)
  const [showBarcode, setShowBarcode] = useState(false)
  const [zoom, setZoom] = useState(1)
  const attachment = outing.ticketAttachment!
  const payload = attachment.barcode?.payload, format = attachment.barcode?.format
  useEffect(() => {
    let cancelled = false
    const urls: string[] = []
    const url = (blob: Blob) => { const value = URL.createObjectURL(blob); urls.push(value); return value }
    void useAppStore.getState().readOutingTicket(outing.id, attachment.id).then(async (blob) => {
      if (cancelled) return
      setError(null); setPhoto(url(blob))
      try {
        const rendered = await (await import('../lib/tickets/barcode')).renderTicketBarcode(blob, payload && format ? { payload, format } : null)
        if (!cancelled && rendered) setBarcode(url(rendered))
      } catch { /* The saved original remains the scanning fallback. */ }
    }).catch((cause) => { if (!cancelled) setError(errorMessage(cause)) })
    return () => { cancelled = true; urls.forEach((value) => URL.revokeObjectURL(value)) }
  }, [outing.id, attachment.id, payload, format, retry])
  return <div className="space-y-3">
    {!photo && !error && <p role="status" className="text-sm">Opening private ticket photo…</p>}
    {error && <div><p role="alert" className="text-sm">{error}</p><Button type="button" variant="outline" onClick={() => setRetry((value) => value + 1)}>Retry opening photo</Button></div>}
    {photo && <>
      {barcode && <div className="flex flex-wrap justify-center gap-2">
        <button type="button" aria-pressed={!showBarcode} onClick={() => setShowBarcode(false)} className="rounded border px-3 py-1 text-sm">Original photo</button>
        <button type="button" aria-pressed={showBarcode} onClick={() => setShowBarcode(true)} className="rounded border px-3 py-1 text-sm">Ticket barcode</button>
      </div>}
      <div className="max-h-[55vh] overflow-auto rounded-md" style={{ background: scan ? '#fff' : 'var(--inset)' }}>
        <img src={showBarcode && barcode ? barcode : photo} alt={showBarcode && barcode ? `Ticket ${attachment.barcode?.format.replaceAll('_', ' ')} barcode` : 'Original ticket photo'}
          className="mx-auto block h-auto" style={{ width: `${zoom * 100}%`, maxWidth: 'none', minWidth: '100%' }} />
      </div>
      <label className="flex items-center justify-center gap-3 text-sm">Photo zoom
        <input aria-label="Ticket photo zoom" type="range" min="1" max="3" step="0.25" value={zoom} onChange={(event) => setZoom(Number(event.target.value))} />
      </label>
      <p className="text-xs">Original photo saved in this browser for offline use.</p>
    </>}
  </div>
}

function TicketViewer({ outing, onClose }: { outing: CinemaOuting; onClose: () => void }) {
  const session = useAppStore((s) => s.librarySession)
  const title = useAppStore((s) => s.titles.find((row) => row.id === outing.titleId)?.title ?? 'Cinema outing')
  const [mode, setMode] = useState<'photo' | 'scan' | 'seats'>('photo')
  const [expanded, setExpanded] = useState(false)
  useEffect(() => {
    if (mode === 'photo' || !navigator.wakeLock) return
    let cancelled = false
    let pending = false
    let lock: WakeLockSentinel | undefined
    const keepAwake = () => {
      if (pending || document.visibilityState !== 'visible' || lock && !lock.released) return
      pending = true
      void navigator.wakeLock.request('screen').then((value) => { if (cancelled) void value.release().catch(() => {}); else lock = value }).catch(() => {}).finally(() => { pending = false })
    }
    keepAwake()
    document.addEventListener('visibilitychange', keepAwake)
    return () => { cancelled = true; document.removeEventListener('visibilitychange', keepAwake); if (lock) void lock.release().catch(() => {}) }
  }, [mode])
  const seatLine = formatSeatLine(outing)
  const hasLegacy = !!outing.ticketImagePath || !!outing.ticketBarcodePayload
  return <CinemaModal open onClose={onClose} title={`Ticket & seats · ${title}`} description="Your private ticket photo and seat details." expanded={expanded} onToggleExpand={() => setExpanded((value) => !value)}>
    <div className="overflow-y-auto p-5 pt-12 space-y-5">
      <header><h2 className="font-serif text-xl">{title}</h2><p className="text-sm text-muted-foreground">{outing.venue}</p><p className="text-sm text-muted-foreground">{new Date(outing.showtime).toLocaleString()}</p></header>
      <div className="flex gap-2" aria-label="Ticket display">
        {(['photo', 'scan', 'seats'] as const).map((value) => <Button key={value} type="button" variant={mode === value ? 'secondary' : 'outline'} aria-pressed={mode === value} onClick={() => setMode(value)}>{value === 'photo' ? 'Photo' : value === 'scan' ? 'Scan' : 'Seats'}</Button>)}
      </div>
      <section className="rounded-lg p-4 space-y-3 text-center" style={mode === 'scan' ? { background: '#fff', color: '#111' } : mode === 'seats' ? { background: '#050403', color: '#be8844' } : undefined}>
        {mode === 'seats' ? <><p className="text-xs uppercase tracking-widest">Your seats</p><p className="text-3xl font-mono break-words">{seatLine || 'No seats saved'}</p><p className="text-sm">{outing.venue}</p></>
          : outing.ticketAttachment ? <TicketPhoto key={`${session}:${outing.ticketAttachment.id}`} outing={outing} scan={mode === 'scan'} />
            : <p className="text-sm">{hasLegacy && !outing.ticketManaged ? 'This ticket photo is stored on your original device. Add the original photo here to make it available across your devices.' : 'Add the original ticket photo to keep it handy at the cinema.'}</p>}
        {outing.bookingRef && <p className="font-mono text-sm break-all">Booking reference: {outing.bookingRef}</p>}
        {mode === 'scan' && <p className="text-xs">Adjust your screen brightness if needed for scanning.</p>}
      </section>
      {hasLegacy && outing.ticketManaged && <p className="text-xs text-muted-foreground">Older device-local ticket data is retained for recovery. It is not the current ticket photo.</p>}
      <TicketCaptureControls outing={outing} session={session} />
    </div>
  </CinemaModal>
}
