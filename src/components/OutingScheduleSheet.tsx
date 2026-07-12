import { useState, useEffect, useRef, useMemo } from 'react'
import { Ticket, Search, Calendar, Clock, MapPin, Users, Film, X, Share2, Download, RefreshCw } from 'lucide-react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { PosterThumb } from 'src/components/ui/poster-thumb'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { companionSuggestions, venueSuggestions, type OutingSchedulePrefill } from 'src/store/outings'
import { buildOutingIcs, outingIcsFilename, downloadIcsFile, formatOutingShareSnippet } from 'src/lib/ics'
import { listFriendships, type FriendshipView } from 'src/lib/auth'
import { ShareOutingPanel } from 'src/components/ShareOutingPanel'
import { CINEMA_FORMATS, type CinemaFormat, type CinemaOuting, type Companion, type Title } from 'src/store/mockData'

// ─── Companion chip input (free-text + past-companion/friend autocomplete) ───
// Exported for reuse by the viewing editor (TitleDetailDrawer, plan §4.6/§7.4)
// — venue/companions get the same chip-input affordance on any viewing.

export function CompanionInput({
  companions,
  onChange,
  suggestions,
}: {
  companions: Companion[]
  onChange: (companions: Companion[]) => void
  suggestions: Companion[]
}) {
  const [input, setInput] = useState('')
  const [showSuggestions, setShowSuggestions] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const filtered = suggestions
    .filter((s) => !companions.some((c) => c.name.toLowerCase() === s.name.toLowerCase()))
    .filter((s) => !input.trim() || s.name.toLowerCase().includes(input.trim().toLowerCase()))
    .slice(0, 6)

  function addCompanion(c: Companion) {
    if (companions.some((x) => x.name.toLowerCase() === c.name.toLowerCase())) return
    onChange([...companions, c])
    setInput('')
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      const trimmed = input.trim().replace(/,+$/, '')
      if (trimmed) addCompanion({ name: trimmed })
    } else if (e.key === 'Backspace' && input === '' && companions.length > 0) {
      onChange(companions.slice(0, -1))
    }
  }

  return (
    <div className="relative">
      <div
        className="flex flex-wrap gap-1.5 min-h-[38px] bg-secondary/50 border border-border rounded-md px-2.5 py-1.5 cursor-text focus-within:ring-2 focus-within:ring-amber/30"
        onClick={() => inputRef.current?.focus()}
      >
        {companions.map((c) => (
          <span
            key={c.name}
            className="flex items-center gap-1.5 pl-1 pr-2 py-0.5 rounded-full bg-amber/10 border border-amber/20 font-mono text-xs text-amber"
          >
            {c.friendUserId && (
              <span className="w-4 h-4 rounded-full bg-amber/20 flex items-center justify-center text-[9px] font-sans shrink-0">
                {c.name.charAt(0).toUpperCase()}
              </span>
            )}
            {c.name}
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); onChange(companions.filter((x) => x.name !== c.name)) }}
              className="hover:text-amber-bright transition-colors"
              aria-label={`Remove ${c.name}`}
            >
              <X className="w-2.5 h-2.5" />
            </button>
          </span>
        ))}
        <input
          ref={inputRef}
          aria-label="Add a companion"
          value={input}
          onChange={(e) => { setInput(e.target.value); setShowSuggestions(true) }}
          onFocus={() => setShowSuggestions(true)}
          onBlur={() => setTimeout(() => setShowSuggestions(false), 150)}
          onKeyDown={handleKeyDown}
          placeholder={companions.length === 0 ? "Who's coming? Enter or comma to add" : ''}
          className="flex-1 min-w-[120px] bg-transparent text-sm font-sans text-foreground placeholder:text-muted-foreground focus:outline-none"
        />
      </div>
      {showSuggestions && filtered.length > 0 && (
        <div className="absolute z-10 mt-1 w-full rounded-md border border-border bg-popover shadow-lg overflow-hidden">
          {filtered.map((s) => (
            <button
              key={s.friendUserId ?? s.name}
              type="button"
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => addCompanion(s)}
              className="w-full flex items-center gap-2 px-3 py-1.5 text-left text-sm font-sans hover:bg-secondary/60 transition-colors"
            >
              {s.friendUserId && (
                <span className="w-4 h-4 rounded-full bg-amber/20 flex items-center justify-center text-[9px] font-sans shrink-0 text-amber">
                  {s.name.charAt(0).toUpperCase()}
                </span>
              )}
              {s.name}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

// ─── Movie picker (command-palette / no-preselection entry point) ───────────

function MoviePicker({ titles, onPick }: { titles: Title[]; onPick: (titleId: string) => void }) {
  const [query, setQuery] = useState('')
  const movies = useMemo(() => titles.filter((t) => t.type === 'movie'), [titles])

  const results = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) {
      return movies
        .filter((t) => t.status === 'watchlist')
        .sort((a, b) => (a.addedAt < b.addedAt ? 1 : a.addedAt > b.addedAt ? -1 : 0))
    }
    return movies.filter((t) => t.title.toLowerCase().includes(q)).slice(0, 20)
  }, [movies, query])

  return (
    <div className="space-y-4">
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground pointer-events-none" />
        <Input
          autoFocus
          aria-label="Search for a movie"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search any movie in your library…"
          className="pl-9 bg-secondary/50 border-border"
        />
      </div>
      {results.length === 0 ? (
        <div className="text-center py-10 text-muted-foreground text-sm font-sans">
          {query ? `No movies match "${query}".` : 'Nothing on your watchlist yet — search for a movie above.'}
        </div>
      ) : (
        <div className="space-y-1 max-h-[50vh] overflow-y-auto scrollbar-thin">
          {!query && (
            <p className="font-mono text-[11px] uppercase tracking-widest text-muted-foreground pb-1">
              On your watchlist
            </p>
          )}
          {results.map((t) => (
            <button
              key={t.id}
              onClick={() => onPick(t.id)}
              className="w-full flex items-center gap-3 p-2.5 rounded-lg hover:bg-secondary/60 transition-colors text-left"
            >
              <PosterThumb src={t.posterUrl} alt={t.title} type={t.type} />
              <div className="flex-1 min-w-0">
                <p className="font-sans text-sm text-foreground truncate">{t.title}</p>
                <p className="font-mono text-xs text-muted-foreground">{t.year}</p>
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

// ─── Ticket form ─────────────────────────────────────────────────────────────

interface FormState {
  date: string
  time: string
  venue: string
  companions: Companion[]
  format: CinemaFormat | ''
  previewsMinutes: number
  runtimeMinutes: number
  ticketPrice: string
  seat: string
  bookingRef: string
  notes: string
}

function todayStr(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// prefill seeds showtime/venue/format from a shared plan's snapshot (plan
// §4.10's "I've got tickets too" CTA) — seat is deliberately left out of
// OutingSchedulePrefill, so it always falls through to the blank default.
function defaultForm(runtime?: number, prefill?: OutingSchedulePrefill | null): FormState {
  const showtime = prefill ? new Date(prefill.showtime) : null
  const pad = (n: number) => String(n).padStart(2, '0')
  return {
    date: showtime ? `${showtime.getFullYear()}-${pad(showtime.getMonth() + 1)}-${pad(showtime.getDate())}` : todayStr(),
    time: showtime ? `${pad(showtime.getHours())}:${pad(showtime.getMinutes())}` : '19:00',
    venue: prefill?.venue ?? '',
    companions: [],
    format: prefill?.format ?? '',
    previewsMinutes: 20,
    runtimeMinutes: runtime ?? 120,
    ticketPrice: '',
    seat: '',
    bookingRef: '',
    notes: '',
  }
}

function formFromOuting(outing: CinemaOuting): FormState {
  const showtime = new Date(outing.showtime)
  const pad = (n: number) => String(n).padStart(2, '0')
  return {
    date: `${showtime.getFullYear()}-${pad(showtime.getMonth() + 1)}-${pad(showtime.getDate())}`,
    time: `${pad(showtime.getHours())}:${pad(showtime.getMinutes())}`,
    venue: outing.venue ?? '',
    companions: outing.companions,
    format: outing.format ?? '',
    previewsMinutes: outing.previewsMinutes,
    runtimeMinutes: outing.runtimeMinutes,
    ticketPrice: outing.ticketPrice != null ? String(outing.ticketPrice) : '',
    seat: outing.seat ?? '',
    bookingRef: outing.bookingRef ?? '',
    notes: outing.notes ?? '',
  }
}

function computeEndsAt(form: FormState): Date {
  const showtime = new Date(`${form.date}T${form.time}:00`)
  return new Date(showtime.getTime() + (form.previewsMinutes + form.runtimeMinutes) * 60_000)
}

function OutingForm({
  title,
  editingOuting,
  duplicateOuting,
  prefill,
  onSaved,
  onClose,
}: {
  title: Title
  editingOuting: CinemaOuting | null
  duplicateOuting: CinemaOuting | null
  prefill?: OutingSchedulePrefill | null
  onSaved: (outing: CinemaOuting) => void
  onClose: () => void
}) {
  const addOuting = useAppStore((s) => s.addOuting)
  const updateOuting = useAppStore((s) => s.updateOuting)
  const reconcileOutings = useAppStore((s) => s.reconcileOutings)
  const selectTitle = useAppStore((s) => s.selectTitle)
  const openRefreshMetadata = useAppStore((s) => s.openRefreshMetadata)
  const outings = useAppStore((s) => s.outings)
  const allViewings = useAppStore((s) => s.titles.flatMap((t) => t.viewings))

  const [friends, setFriends] = useState<FriendshipView[]>([])

  useEffect(() => {
    // Deferred to satisfy react-hooks/set-state-in-effect (same pattern as
    // SendRecommendationPanel's friend fetch).
    const t = setTimeout(() => {
      listFriendships()
        .then((list) => setFriends(list.filter((f) => f.status === 'accepted')))
        .catch((err) => console.error('Failed to load friends for outing companions:', err))
    }, 0)
    return () => clearTimeout(t)
  }, [])

  const [form, setForm] = useState<FormState>(() =>
    editingOuting ? formFromOuting(editingOuting) : defaultForm(title.runtime, prefill)
  )

  const suggestions = useMemo(
    () => companionSuggestions(outings, allViewings, friends),
    [outings, allViewings, friends]
  )
  const venues = useMemo(() => venueSuggestions(outings, allViewings), [outings, allViewings])

  const endsAt = computeEndsAt(form)
  const endsAtLabel = endsAt.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true })
  const isPast = endsAt.getTime() <= new Date().getTime()

  function patch(p: Partial<FormState>) {
    setForm((f) => ({ ...f, ...p }))
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const showtimeIso = new Date(`${form.date}T${form.time}:00`).toISOString()
    const endsAtIso = computeEndsAt(form).toISOString()
    const common = {
      showtime: showtimeIso,
      previewsMinutes: form.previewsMinutes,
      runtimeMinutes: form.runtimeMinutes,
      endsAt: endsAtIso,
      venue: form.venue.trim() || undefined,
      companions: form.companions,
      format: form.format || undefined,
      ticketPrice: form.ticketPrice.trim() ? Number(form.ticketPrice) : undefined,
      seat: form.seat.trim() || undefined,
      bookingRef: form.bookingRef.trim() || undefined,
      notes: form.notes.trim() || undefined,
    }
    const nowPast = new Date(endsAtIso).getTime() <= Date.now()

    if (editingOuting) {
      // Reschedule (plan §4.2): editing a 'missed' outing (from "Didn't make
      // it") flips it back to 'scheduled' for the next attempt; editing an
      // already-scheduled outing leaves status untouched.
      const patch = editingOuting.status === 'scheduled' ? common : { ...common, status: 'scheduled' as const }
      updateOuting(editingOuting.id, patch)
      if (nowPast) void reconcileOutings()
      onClose()
      return
    }

    const outing: CinemaOuting = {
      id: crypto.randomUUID(),
      titleId: title.id,
      status: 'scheduled',
      createdAt: new Date().toISOString(),
      ...common,
    }
    addOuting(outing)
    if (nowPast) {
      void reconcileOutings()
      onClose()
    } else {
      onSaved(outing)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-5">
      <div className="flex gap-3">
        <PosterThumb src={title.posterUrl} alt={title.title} type={title.type} size="md" />
        <div className="min-w-0 pt-1">
          <p className="font-serif text-lg text-foreground truncate">{title.title}</p>
          <p className="font-mono text-xs text-muted-foreground mt-0.5">{title.year}</p>
        </div>
      </div>

      {duplicateOuting && (
        <p className="text-xs font-sans text-amber/80 bg-amber/10 border border-amber/20 rounded-md px-3 py-2">
          You already have tickets for{' '}
          {new Date(duplicateOuting.showtime).toLocaleDateString('en-US', { month: 'long', day: 'numeric' })}.
        </p>
      )}

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label htmlFor="outing-date" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
            <Calendar className="inline w-3 h-3 mr-1" />
            Date
          </label>
          <Input
            id="outing-date"
            type="date"
            required
            value={form.date}
            onChange={(e) => patch({ date: e.target.value })}
            className="bg-secondary/50 border-border font-mono"
          />
        </div>
        <div>
          <label htmlFor="outing-time" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
            <Clock className="inline w-3 h-3 mr-1" />
            Showtime
          </label>
          <Input
            id="outing-time"
            type="time"
            required
            value={form.time}
            onChange={(e) => patch({ time: e.target.value })}
            className="bg-secondary/50 border-border font-mono"
          />
        </div>
      </div>

      <div>
        <label htmlFor="outing-venue" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
          <MapPin className="inline w-3 h-3 mr-1" />
          Theater
        </label>
        <Input
          id="outing-venue"
          list="outing-venue-suggestions"
          value={form.venue}
          onChange={(e) => patch({ venue: e.target.value })}
          placeholder="e.g. AMC Georgetown"
          className="bg-secondary/50 border-border"
        />
        <datalist id="outing-venue-suggestions">
          {venues.map((v) => <option key={v} value={v} />)}
        </datalist>
      </div>

      <div>
        <p className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
          <Users className="inline w-3 h-3 mr-1" />
          Companions
        </p>
        <CompanionInput
          companions={form.companions}
          onChange={(companions) => patch({ companions })}
          suggestions={suggestions}
        />
      </div>

      <div>
        <p className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
          <Film className="inline w-3 h-3 mr-1" />
          Format
        </p>
        <div className="flex flex-wrap gap-2">
          {CINEMA_FORMATS.map((f) => (
            <button
              key={f}
              type="button"
              onClick={() => patch({ format: form.format === f ? '' : f })}
              className={cn(
                'px-3 py-1.5 rounded-md text-xs font-sans border transition-all',
                form.format === f
                  ? 'bg-amber/20 border-amber/50 text-amber'
                  : 'bg-secondary/50 border-border text-muted-foreground hover:text-foreground'
              )}
            >
              {f}
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label htmlFor="outing-previews" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
            Previews (min)
          </label>
          <Input
            id="outing-previews"
            type="number"
            required
            min={0}
            max={120}
            step={5}
            value={form.previewsMinutes}
            onChange={(e) => patch({ previewsMinutes: Math.max(0, Math.min(120, Number(e.target.value) || 0)) })}
            className="bg-secondary/50 border-border font-mono"
          />
        </div>
        <div>
          <label htmlFor="outing-runtime" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
            Runtime (min)
          </label>
          <Input
            id="outing-runtime"
            type="number"
            required
            min={1}
            value={form.runtimeMinutes}
            onChange={(e) => patch({ runtimeMinutes: Math.max(1, Number(e.target.value) || 1) })}
            className="bg-secondary/50 border-border font-mono"
          />
          {!title.runtime && (
            <button
              type="button"
              onClick={() => { selectTitle(title.id); openRefreshMetadata() }}
              className="flex items-center gap-1 text-xs font-mono text-amber/70 hover:text-amber transition-colors mt-1.5"
            >
              <RefreshCw className="w-3 h-3" />
              No runtime on file — refresh metadata
            </button>
          )}
        </div>
      </div>

      <p className="font-mono text-xs text-amber/80 bg-amber/5 border border-amber/15 rounded-md px-3 py-2">
        Lets out ≈ {endsAtLabel}
      </p>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label htmlFor="outing-price" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
            Ticket price
          </label>
          <Input
            id="outing-price"
            type="number"
            min={0}
            step={0.01}
            value={form.ticketPrice}
            onChange={(e) => patch({ ticketPrice: e.target.value })}
            placeholder="0.00"
            className="bg-secondary/50 border-border font-mono"
          />
        </div>
        <div>
          <label htmlFor="outing-seat" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
            Seat
          </label>
          <Input
            id="outing-seat"
            value={form.seat}
            onChange={(e) => patch({ seat: e.target.value })}
            placeholder="H12"
            className="bg-secondary/50 border-border"
          />
        </div>
      </div>

      <div>
        <label htmlFor="outing-booking-ref" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
          Booking ref
        </label>
        <Input
          id="outing-booking-ref"
          value={form.bookingRef}
          onChange={(e) => patch({ bookingRef: e.target.value })}
          placeholder="AMC-4X9KQ2"
          className="bg-secondary/50 border-border font-mono"
        />
      </div>

      <div>
        <label htmlFor="outing-notes" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
          Notes
        </label>
        <textarea
          id="outing-notes"
          value={form.notes}
          onChange={(e) => patch({ notes: e.target.value })}
          rows={2}
          placeholder="Anything else worth remembering…"
          className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-2 focus:ring-amber/30"
        />
      </div>

      <Button type="submit" className="w-full bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium">
        <Ticket className="w-4 h-4 mr-2" />
        {isPast ? 'Log this outing' : editingOuting ? 'Save changes' : 'Get tickets'}
      </Button>
    </form>
  )
}

// ─── Save confirmation — "Tickets saved — share your plans?" (§4.10) ─────────

function SavedStep({ title, outing, onClose }: { title: Title; outing: CinemaOuting; onClose: () => void }) {
  const [sharePanelOpen, setSharePanelOpen] = useState(false)

  function handleDownloadIcs() {
    const ics = buildOutingIcs(outing, title.title)
    downloadIcsFile(outingIcsFilename(title.title, outing.showtime), ics)
  }

  return (
    <div className="text-center py-8 space-y-4">
      <div className="w-12 h-12 rounded-full bg-amber/15 flex items-center justify-center mx-auto">
        <Ticket className="w-6 h-6 text-amber" />
      </div>
      <p className="font-serif text-lg text-foreground">Tickets saved — share your plans?</p>
      <p className="font-sans text-xs text-muted-foreground max-w-[280px] mx-auto">
        {formatOutingShareSnippet(title.title, outing.showtime, outing.venue, outing.format, outing.seat)}
      </p>
      <div className="flex flex-col gap-2 items-center pt-2">
        <Button
          onClick={() => setSharePanelOpen(true)}
          className="w-full max-w-[220px] bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
        >
          <Share2 className="w-4 h-4 mr-2" />
          Share plans
        </Button>
        <button
          onClick={handleDownloadIcs}
          className="flex items-center gap-1.5 text-xs font-mono text-amber/70 hover:text-amber transition-colors"
        >
          <Download className="w-3.5 h-3.5" />
          Download .ics
        </button>
        <button onClick={onClose} className="text-xs font-mono text-muted-foreground hover:text-foreground transition-colors mt-1">
          Not now
        </button>
      </div>
      {sharePanelOpen && (
        <ShareOutingPanel outing={outing} title={title} onClose={() => setSharePanelOpen(false)} />
      )}
    </div>
  )
}

// ─── Body (mounted only while the sheet is open — see RefreshMetadataModal
// for the same "fresh mount resets local state" idiom) ───────────────────────

function OutingScheduleBody({ onClose }: { onClose: () => void }) {
  const titles = useAppStore((s) => s.titles)
  const outings = useAppStore((s) => s.outings)
  const outingScheduleTitleId = useAppStore((s) => s.outingScheduleTitleId)
  const outingScheduleOutingId = useAppStore((s) => s.outingScheduleOutingId)
  const outingSchedulePrefill = useAppStore((s) => s.outingSchedulePrefill)

  const editingOuting = outingScheduleOutingId
    ? outings.find((o) => o.id === outingScheduleOutingId) ?? null
    : null
  const initialTitleId = editingOuting?.titleId ?? outingScheduleTitleId ?? null

  const [pickedTitleId, setPickedTitleId] = useState<string | null>(initialTitleId)
  const [savedOuting, setSavedOuting] = useState<CinemaOuting | null>(null)

  const title = titles.find((t) => t.id === pickedTitleId) ?? null

  const duplicateOuting = title
    ? outings.find((o) => o.titleId === title.id && o.status === 'scheduled' && o.id !== editingOuting?.id) ?? null
    : null

  if (savedOuting && title) {
    return <SavedStep title={title} outing={savedOuting} onClose={onClose} />
  }

  if (!title) {
    return <MoviePicker titles={titles} onPick={setPickedTitleId} />
  }

  return (
    <OutingForm
      title={title}
      editingOuting={editingOuting}
      duplicateOuting={duplicateOuting}
      prefill={editingOuting ? null : outingSchedulePrefill}
      onSaved={setSavedOuting}
      onClose={onClose}
    />
  )
}

export function OutingScheduleSheet() {
  const isOpen = useAppStore((s) => s.isOutingScheduleOpen)
  const close = useAppStore((s) => s.closeOutingSchedule)

  return (
    <CinemaModal
      open={isOpen}
      onClose={close}
      maxWidth="sm:max-w-lg"
      title="I've got tickets"
      description="Schedule a cinema outing, edit an existing one, or log one that's already happened."
    >
      <div className="overflow-y-auto flex-1 scrollbar-thin px-6 py-6">
        <h2 className="font-serif text-xl font-light text-foreground mb-5 flex items-center gap-2">
          <Ticket className="w-5 h-5 text-amber" />
          I've got tickets
        </h2>
        {isOpen ? <OutingScheduleBody onClose={close} /> : null}
      </div>
    </CinemaModal>
  )
}
