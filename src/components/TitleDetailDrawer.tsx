import { useState, useEffect, useRef, useMemo, useId } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { StarRating } from 'src/components/ui/star-rating'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { PosterLightbox } from 'src/components/ui/poster-lightbox'
import { SeriesGraph } from 'src/components/ui/series-graph'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { CardTitle, BodyText, MetaBadge, StatLabel } from 'src/components/ui/typography'
import { useAppStore, useSelectedTitle } from 'src/store/useAppStore'
import { PersonDetailPanel, type PersonDetailTarget } from 'src/components/PersonDetailPanel'
import {
  avgSeasonRating,
  avgSeriesRating,
  episodesWatchedInSeason,
  totalEpisodesWatched,
  totalEpisodeCount,
  getUnlockedModes,
  getEarnedModes,
} from 'src/store/episodeUtils'
import { EpisodeCard, EpisodePanel } from 'src/components/ui/episode-card'
import {
  Calendar, Check, Clock, Eye, Film, Tv, Plus, FileText, Trash2, Star,
  ChevronLeft, ChevronRight, ChevronDown, ChevronUp, RefreshCw, Tag, X, Send, Ticket, Clapperboard,
  Pencil, MapPin, Users,
} from 'lucide-react'
import { cn, fmtDate, fmtReleaseDate, fmtRuntime, languageName } from 'src/lib/utils'
import { formatCompanions, findPendingFollowUpOuting, companionSuggestions, venueSuggestions } from 'src/store/outings'
import { CINEMA_FORMATS, type Title, type Viewing, type WatchStatus, type Season, type Episode, type CastMember, type CrewMember, type EpisodeCrew, type CinemaOuting, type CinemaFormat, type Companion } from 'src/store/mockData'
import { fetchSeasonDetails, fetchTitleVideos, fetchTitleImages, fetchWatchProviders, fetchCollectionParts, TMDB_STILL_BASE, type TitleVideo, type WatchProviders, type SearchResult } from 'src/lib/media'
import { upsertEpisodeMetadataInDb, bulkUpsertSeasonCastInDb, bulkUpsertEpisodeCrewInDb } from 'src/lib/db'
import { listFriendships, type FriendshipView } from 'src/lib/auth'
import { SendRecommendationPanel } from 'src/components/SendRecommendationPanel'
import { ShareOutingPanel } from 'src/components/ShareOutingPanel'
import { CompanionInput } from 'src/components/OutingScheduleSheet'
import { TitleCommentsPanel } from 'src/components/TitleCommentsPanel'
import SpiderWebOverlay from 'src/components/SpiderWebOverlay'
import { SpiderNoirModeSelector } from 'src/components/SpiderNoirModeSelector'
import { transitionSpiderNoir } from 'src/lib/theme'
import { MatrixPillModal } from 'src/components/MatrixPillModal'
import { MatrixDigitalRain } from 'src/components/MatrixDigitalRain'
import { MatrixPillSelector } from 'src/components/MatrixPillSelector'
import { HeroBackdrop } from 'src/components/ui/hero-backdrop'
import { TrailerRow } from 'src/components/ui/trailer-row'
import { WatchProvidersSection } from 'src/components/ui/watch-providers'
import { ReviewBadges, ExternalLinks, HeroScores } from 'src/components/ui/media-badges'
import { SPIDER_NOIR_TMDB_ID, THE_MATRIX_TMDB_ID } from 'src/lib/easterEggThemes'

type SelectorMode = 'normal' | 'bw' | 'color'
const EASTER_EGG_KEY = 'spider_noir_color'
const SPIDER_NOIR_LOGO_NORMAL = 'https://image.tmdb.org/t/p/original/o2D8loRUDlEuOf7BMRgulerNJ6p.png'
const SPIDER_NOIR_LOGO_COLOR  = 'https://image.tmdb.org/t/p/original/ubGbGqmbfWW1B7kNXDI1KrooK7S.png'
const SPIDER_NOIR_LOGO_BW     = 'https://image.tmdb.org/t/p/original/tcS3i7X9XvYcFGEEnjHG147sQrq.png'

function getSpiderNoirActiveMode(title: Title): 'bw' | 'color' | null {
  let lastMode: 'bw' | 'color' | null = null
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) {
      for (const we of ep.watchEvents) {
        if (we.colorMode) lastMode = we.colorMode
      }
      for (const rv of ep.reviews) {
        if (rv.colorMode) lastMode = rv.colorMode
      }
    }
  }
  return lastMode
}

// ─── Shared status options ────────────────────────────────────────────────────

const STATUS_OPTIONS: { value: WatchStatus; label: string }[] = [
  { value: 'watched', label: 'Watched' },
  { value: 'watchlist', label: 'Watchlist' },
  { value: 'watching', label: 'Watching' },
  { value: 'dropped', label: 'Dropped' },
]

// ─── Movie-only: viewing timeline ────────────────────────────────────────────

// Undated viewings are pre-platform (watched before joining) and order as oldest.
function viewingTime(v: Viewing): number {
  return v.date ? new Date(v.date).getTime() : -Infinity
}

// Venue + companions fields — shared by the log-viewing form and the
// per-viewing editor (plan §4.6/§7.4): the same chip-input/autocomplete
// affordances as OutingScheduleSheet's ticket form, driven by the same
// src/store/outings.ts suggestion helpers, so "your usual theater" and
// "who you usually go with" complete the same way everywhere.
function VenueCompanionsFields({
  venue,
  onVenueChange,
  companions,
  onCompanionsChange,
}: {
  venue: string
  onVenueChange: (venue: string) => void
  companions: Companion[]
  onCompanionsChange: (companions: Companion[]) => void
}) {
  const outings = useAppStore((s) => s.outings)
  const titles = useAppStore((s) => s.titles)
  const allViewings = useMemo(() => titles.flatMap((t) => t.viewings), [titles])
  const [friends, setFriends] = useState<FriendshipView[]>([])
  const datalistId = useId()

  useEffect(() => {
    // Deferred to satisfy react-hooks/set-state-in-effect (same pattern as
    // OutingScheduleSheet's friend fetch).
    const t = setTimeout(() => {
      listFriendships()
        .then((list) => setFriends(list.filter((f) => f.status === 'accepted')))
        .catch((err) => console.error('Failed to load friends for viewing companions:', err))
    }, 0)
    return () => clearTimeout(t)
  }, [])

  const suggestions = useMemo(
    () => companionSuggestions(outings, allViewings, friends),
    [outings, allViewings, friends]
  )
  const venues = useMemo(() => venueSuggestions(outings, allViewings), [outings, allViewings])

  return (
    <>
      <div>
        <label className="flex items-center gap-1 font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
          <MapPin className="w-3 h-3" />
          Theater
        </label>
        <Input
          list={datalistId}
          value={venue}
          onChange={(e) => onVenueChange(e.target.value)}
          placeholder="e.g. AMC Georgetown"
          className="bg-secondary/50 border-border"
        />
        <datalist id={datalistId}>
          {venues.map((v) => <option key={v} value={v} />)}
        </datalist>
      </div>
      <div>
        <p className="flex items-center gap-1 font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
          <Users className="w-3 h-3" />
          Companions
        </p>
        <CompanionInput companions={companions} onChange={onCompanionsChange} suggestions={suggestions} />
      </div>
    </>
  )
}

// Ticket stub line (plan §4.6/§7.4/§13): "at AMC Georgetown · with Alex &
// Sam · IMAX" — format comes from the linked outing (viewings don't carry
// their own format column), so a manually-logged viewing degrades to just
// venue/companions. Degrades further to nothing when neither is present.
function TicketStubLine({ viewing, outing }: { viewing: Viewing; outing: CinemaOuting | undefined }) {
  const companionLabel = formatCompanions(viewing.companions ?? [])
  const segments = [
    viewing.venue && `at ${viewing.venue}`,
    companionLabel && `with ${companionLabel}`,
    outing?.format,
  ].filter((s): s is string => Boolean(s))
  if (segments.length === 0) return null

  return (
    <div className="flex items-center gap-2 mt-1.5">
      <span className="ticket-perforation" aria-hidden="true" />
      <span className="font-mono text-xs text-amber/80">{segments.join(' · ')}</span>
    </div>
  )
}

// Per-viewing editor (plan §4.6/§7.4): venue/companions/rating/notes are
// editable on ANY viewing, not just outing-completed ones. When the viewing
// is linked to an outing, a "Ticket details" section also surfaces that
// outing's receipt fields (format/price/seat/booking ref) — those stay
// editable after completion even though the outing's timing fields are
// frozen (rule §5.5); this editor is where that post-completion editing
// happens, not the schedule sheet.
function ViewingEditForm({
  title,
  viewing,
  outing,
  onClose,
}: {
  title: Title
  viewing: Viewing
  outing: CinemaOuting | undefined
  onClose: () => void
}) {
  const { updateTitle, updateOuting } = useAppStore(
    useShallow((s) => ({
      updateTitle: s.updateTitle,
      updateOuting: s.updateOuting,
    }))
  )

  const [prePlatform, setPrePlatform] = useState(!viewing.date)
  const [date, setDate] = useState(viewing.date ?? new Date().toISOString().slice(0, 10))
  const [rating, setRating] = useState(viewing.rating ?? 0)
  const [notes, setNotes] = useState(viewing.notes ?? '')
  const [venue, setVenue] = useState(viewing.venue ?? '')
  const [companions, setCompanions] = useState<Companion[]>(viewing.companions ?? [])

  const [format, setFormat] = useState<CinemaFormat | ''>(outing?.format ?? '')
  const [ticketPrice, setTicketPrice] = useState(outing?.ticketPrice != null ? String(outing.ticketPrice) : '')
  const [seat, setSeat] = useState(outing?.seat ?? '')
  const [bookingRef, setBookingRef] = useState(outing?.bookingRef ?? '')

  function handleSave(e: React.FormEvent) {
    e.preventDefault()
    const nextViewings = title.viewings.map((v) =>
      v.id === viewing.id
        ? {
            ...v,
            date: prePlatform ? undefined : date,
            rating: rating > 0 ? rating : undefined,
            notes: notes.trim() || undefined,
            venue: venue.trim() || undefined,
            companions: companions.length > 0 ? companions : undefined,
          }
        : v
    )
    updateTitle(title.id, {
      viewings: nextViewings,
      ...(rating > 0 ? { rating } : {}),
    })
    if (outing) {
      updateOuting(outing.id, {
        format: format || undefined,
        ticketPrice: ticketPrice.trim() ? Number(ticketPrice) : undefined,
        seat: seat.trim() || undefined,
        bookingRef: bookingRef.trim() || undefined,
      })
    }
    onClose()
  }

  return (
    <form onSubmit={handleSave} className="mt-3 space-y-3 border-t pt-3" style={{ borderColor: 'var(--line)' }}>
      <div>
        {!prePlatform && (
          <Input
            type="date"
            aria-label="Date watched"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            className="bg-secondary/50 border-border font-mono"
          />
        )}
        <label className="flex items-center gap-2 cursor-pointer mt-2">
          <input
            type="checkbox"
            checked={prePlatform}
            onChange={(e) => setPrePlatform(e.target.checked)}
            className="accent-amber w-3.5 h-3.5"
          />
          <span className="font-sans text-xs text-muted-foreground">
            Watched before joining CinemArchive (no date)
          </span>
        </label>
      </div>

      <div>
        <p className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">Rating</p>
        <StarRating value={rating} onChange={setRating} size="md" />
      </div>

      <VenueCompanionsFields
        venue={venue}
        onVenueChange={setVenue}
        companions={companions}
        onCompanionsChange={setCompanions}
      />

      <div>
        <label htmlFor="viewing-edit-notes" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
          <FileText className="inline w-3 h-3 mr-1" />
          Notes
        </label>
        <textarea
          id="viewing-edit-notes"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={2}
          className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-2 focus:ring-amber/30"
        />
      </div>

      {outing && (
        <div className="space-y-3 rounded-lg border p-3" style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}>
          <p className="font-mono uppercase tracking-widest" style={{ fontSize: '10px', color: 'var(--paper-faint)' }}>
            Ticket details
          </p>
          <div className="flex flex-wrap gap-2">
            {CINEMA_FORMATS.map((f) => (
              <button
                key={f}
                type="button"
                onClick={() => setFormat(format === f ? '' : f)}
                className={cn(
                  'px-2.5 py-1 rounded-md text-xs font-sans border transition-all',
                  format === f
                    ? 'bg-amber/20 border-amber/50 text-amber'
                    : 'bg-secondary/50 border-border text-muted-foreground hover:text-foreground'
                )}
              >
                {f}
              </button>
            ))}
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label htmlFor="viewing-edit-price" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
                Ticket price
              </label>
              <Input
                id="viewing-edit-price"
                type="number"
                min={0}
                step={0.01}
                value={ticketPrice}
                onChange={(e) => setTicketPrice(e.target.value)}
                placeholder="0.00"
                className="bg-secondary/50 border-border font-mono"
              />
            </div>
            <div>
              <label htmlFor="viewing-edit-seat" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
                Seat
              </label>
              <Input
                id="viewing-edit-seat"
                value={seat}
                onChange={(e) => setSeat(e.target.value)}
                placeholder="H12"
                className="bg-secondary/50 border-border"
              />
            </div>
          </div>
          <div>
            <label htmlFor="viewing-edit-booking-ref" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
              Booking ref
            </label>
            <Input
              id="viewing-edit-booking-ref"
              value={bookingRef}
              onChange={(e) => setBookingRef(e.target.value)}
              placeholder="AMC-4X9KQ2"
              className="bg-secondary/50 border-border font-mono"
            />
          </div>
        </div>
      )}

      <div className="flex gap-2">
        <Button type="submit" className="flex-1 bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium">
          Save changes
        </Button>
        <Button type="button" variant="outline" onClick={onClose}>
          Cancel
        </Button>
      </div>
    </form>
  )
}

function ViewingTimeline({
  title,
  onDeleteViewing,
  onLogViewing,
  isSharedView,
}: {
  title: Title
  onDeleteViewing?: (viewingId: string) => void
  onLogViewing?: () => void
  isSharedView?: boolean
}) {
  const viewings = title.viewings
  const outings = useAppStore((s) => s.outings)
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null)
  const [editingId, setEditingId] = useState<string | null>(null)
  if (viewings.length === 0) {
    return (
      <div className="text-center py-6 text-muted-foreground text-sm font-sans flex flex-col items-center gap-3">
        <div>No viewings logged yet</div>
        {!isSharedView && onLogViewing && (
          <button
            onClick={onLogViewing}
            className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber"
          >
            <Plus className="w-3.5 h-3.5" />
            Log first viewing
          </button>
        )}
      </div>
    )
  }

  return (
    <div className="relative pl-6">
      <div className="absolute left-2 top-0 bottom-0 w-px bg-border" />
      <div className="space-y-4">
        {viewings
          .slice()
          // undated (pre-platform) viewings sort as oldest → bottom of the timeline
          .sort((a, b) => viewingTime(b) - viewingTime(a))
          .map((v) => {
            const formattedDate = v.date ? fmtDate(v.date) : 'Before CinemArchive'
            const outing = v.outingId ? outings.find((o) => o.id === v.outingId) : undefined
            return (
            <div key={v.id} className="relative">
              <div className="absolute -left-[18px] top-1 w-3 h-3 rounded-full bg-amber/70 border-2 border-void" />
              <div className="bg-secondary/50 rounded-lg p-3">
                {pendingDeleteId === v.id ? (
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
                      Remove this viewing?
                    </span>
                    <div className="flex items-center gap-3">
                      <button
                        onClick={() => {
                          onDeleteViewing?.(v.id)
                          setPendingDeleteId(null)
                        }}
                        className="font-mono text-xs transition-opacity hover:opacity-80"
                        style={{ color: 'var(--ember)' }}
                        aria-label={`Delete forever: viewing from ${formattedDate}`}
                      >
                        Delete forever
                      </button>
                      <button
                        onClick={() => setPendingDeleteId(null)}
                        className="font-mono text-xs transition-opacity hover:opacity-80"
                        style={{ color: 'var(--paper-faint)' }}
                        aria-label={`Cancel deleting viewing from ${formattedDate}`}
                      >
                        Cancel
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="flex items-center justify-between mb-1">
                      <span className="font-mono text-xs text-amber">
                        {v.date
                          ? fmtDate(v.date)
                          : <span className="italic">Before CinemArchive</span>}
                      </span>
                      <div className="flex items-center gap-2">
                        {v.rating && (
                          <span className="font-mono text-xs text-amber">★ {v.rating}</span>
                        )}
                        {!isSharedView && (
                          <button
                            onClick={() => setEditingId(editingId === v.id ? null : v.id)}
                            style={{ color: 'var(--paper-faint)', opacity: 0.45 }}
                            onMouseEnter={(e) => (e.currentTarget.style.opacity = '1')}
                            onMouseLeave={(e) => (e.currentTarget.style.opacity = '0.45')}
                            aria-label={`Edit viewing from ${formattedDate}`}
                            aria-expanded={editingId === v.id}
                          >
                            <Pencil className="w-3 h-3" />
                          </button>
                        )}
                        {!isSharedView && onDeleteViewing && (
                          <button
                            onClick={() => setPendingDeleteId(v.id)}
                            style={{ color: 'var(--paper-faint)', opacity: 0.45 }}
                            onMouseEnter={(e) => (e.currentTarget.style.opacity = '1')}
                            onMouseLeave={(e) => (e.currentTarget.style.opacity = '0.45')}
                            aria-label={`Delete viewing from ${formattedDate}`}
                          >
                            <Trash2 className="w-3 h-3" />
                          </button>
                        )}
                      </div>
                    </div>
                    <TicketStubLine viewing={v} outing={outing} />
                    {v.notes && (
                      <p className="text-xs text-muted-foreground font-sans italic leading-relaxed mt-1.5">
                        "{v.notes}"
                      </p>
                    )}
                    {editingId === v.id && (
                      <ViewingEditForm
                        title={title}
                        viewing={v}
                        outing={outing}
                        onClose={() => setEditingId(null)}
                      />
                    )}
                  </>
                )}
              </div>
            </div>
          )})}
      </div>
    </div>
  )
}

// ─── Section container ────────────────────────────────────────────────────────
// Every drawer section sits in one of these bordered cards so related content
// reads as a common region (gestalt) instead of floating in open space.

function SectionCard({
  title,
  action,
  className,
  children,
}: {
  title?: React.ReactNode
  /** Rendered on the right of the heading row (e.g. a "Log a viewing" button). */
  action?: React.ReactNode
  className?: string
  children: React.ReactNode
}) {
  return (
    <section
      className={cn('rounded-xl border p-4 sm:p-5 min-w-0', className)}
      style={{ borderColor: 'var(--line)', background: 'var(--wash)' }}
    >
      {(title || action) && (
        <div className="flex items-center justify-between gap-3 mb-4">
          <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim">{title}</h4>
          {action}
        </div>
      )}
      {children}
    </section>
  )
}

// ─── Details sidebar ──────────────────────────────────────────────────────────

function StatusCard({
  status,
  isSharedView,
  onChange,
}: {
  status: WatchStatus
  isSharedView: boolean
  onChange: (status: WatchStatus) => void
}) {
  return (
    <SectionCard title="Status">
      <div className="relative inline-block">
        <select
          value={status}
          onChange={isSharedView ? undefined : (e) => onChange(e.target.value as WatchStatus)}
          disabled={isSharedView}
          aria-label="Title status"
          className={cn(
            'appearance-none font-sans text-sm rounded-lg pl-3 pr-9 py-2 bg-secondary border border-amber/30 focus:outline-none focus:border-amber/60',
            isSharedView && 'opacity-60 cursor-default'
          )}
          style={{ color: 'var(--amber)' }}
        >
          {STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
        <ChevronDown
          className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4"
          style={{ color: 'var(--amber)' }}
        />
      </div>
    </SectionCard>
  )
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="font-mono text-xs uppercase tracking-wider text-muted-foreground shrink-0">{label}</dt>
      <dd className="font-sans text-sm text-foreground text-right min-w-0 break-words">{value}</dd>
    </div>
  )
}

// ─── Cast & Crew section ──────────────────────────────────────────────────────

const CREW_DISPLAY: Array<{ jobs: string[]; label: string }> = [
  { jobs: ['Creator'],                                     label: 'Created by' },
  { jobs: ['Director'],                                    label: 'Dir.' },
  { jobs: ['Screenplay', 'Writer', 'Teleplay', 'Story'],   label: 'Written by' },
  { jobs: ['Producer'],                                    label: 'Prod.' },
  { jobs: ['Director of Photography'],                     label: 'D.O.P.' },
  { jobs: ['Original Music Composer'],                     label: 'Composer' },
]

function CastCard({
  member,
  onPersonClick,
}: {
  member: CastMember
  onPersonClick: (person: PersonDetailTarget) => void
}) {
  return (
    <button
      type="button"
      onClick={() => onPersonClick({ tmdbPersonId: member.tmdbPersonId, name: member.name, profileUrl: member.profileUrl, character: member.character })}
      aria-label={`View details for ${member.name}`}
      className="group shrink-0 w-[110px] overflow-hidden rounded-lg text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 transition-all"
      style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}
    >
      <div className="aspect-[2/3] overflow-hidden">
        {member.profileUrl ? (
          <img
            src={member.profileUrl}
            alt={member.name}
            className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center" style={{ background: 'var(--card)' }}>
            <span className="font-mono text-3xl" style={{ color: 'var(--paper-faint)' }}>
              {member.name.charAt(0).toUpperCase()}
            </span>
          </div>
        )}
      </div>
      <div className="p-2">
        <div
          className="font-sans font-semibold line-clamp-1 transition-colors group-hover:text-amber"
          style={{ fontSize: '12px', color: 'var(--paper)', lineHeight: 1.3 }}
          title={member.name}
        >
          {member.name}
        </div>
        <div
          className="font-mono line-clamp-1 mt-0.5"
          style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: member.character ? 0.6 : 0 }}
          title={member.character}
        >
          {member.character || ' '}
        </div>
        {member.episodeCount != null && (
          <div
            className="font-mono mt-0.5"
            style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: 0.7 }}
          >
            {member.episodeCount} ep{member.episodeCount !== 1 ? 's' : ''}
          </div>
        )}
      </div>
    </button>
  )
}

// Wrapping cast layout: the first 5 members show, with a "View All" tile in the
// 6th slot expanding the rest (no single-axis horizontal scroll).
const CAST_COLLAPSED_COUNT = 5

function CastGrid({
  cast,
  onPersonClick,
}: {
  cast: CastMember[]
  onPersonClick: (person: PersonDetailTarget) => void
}) {
  const [showAll, setShowAll] = useState(false)
  // No point hiding a single member behind a button — collapse only above 6.
  const collapsible = cast.length > CAST_COLLAPSED_COUNT
  const visible = collapsible && !showAll ? cast.slice(0, CAST_COLLAPSED_COUNT) : cast

  return (
    <div className="flex flex-wrap gap-2.5">
      {visible.map((member) => (
        <CastCard key={member.tmdbPersonId} member={member} onPersonClick={onPersonClick} />
      ))}
      {collapsible && (
        <button
          type="button"
          onClick={() => setShowAll((v) => !v)}
          aria-expanded={showAll}
          className="shrink-0 w-[110px] rounded-lg flex flex-col items-center justify-center gap-1.5 border border-dashed transition-colors hover:border-amber/40 hover:bg-amber/5 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
          style={{ borderColor: 'var(--line)', minHeight: '120px' }}
        >
          {showAll ? (
            <ChevronUp className="w-4 h-4" style={{ color: 'var(--paper-faint)' }} />
          ) : (
            <Plus className="w-4 h-4" style={{ color: 'var(--paper-faint)' }} />
          )}
          <span className="font-mono text-xs" style={{ color: 'var(--paper-dim)' }}>
            {showAll ? 'Show less' : 'View All'}
          </span>
          {!showAll && (
            <span className="font-mono" style={{ fontSize: '10px', color: 'var(--paper-faint)' }}>
              +{cast.length - CAST_COLLAPSED_COUNT} more
            </span>
          )}
        </button>
      )}
    </div>
  )
}

interface CastCrewSectionProps {
  cast?: CastMember[]
  crew?: CrewMember[]
  studios?: string[]
  onPersonClick: (person: PersonDetailTarget) => void
  onStudioClick: (studio: string) => void
}

function CastCrewSection({ cast, crew, studios, onPersonClick, onStudioClick }: CastCrewSectionProps) {
  const [expanded, setExpanded] = useState(true)
  const hasCast = cast && cast.length > 0
  const hasCrew = crew && crew.length > 0
  const hasStudios = studios && studios.length > 0
  if (!hasCast && !hasCrew && !hasStudios) return null

  return (
    <div>
      <button
        type="button"
        onClick={() => setExpanded((e) => !e)}
        aria-expanded={expanded}
        className="flex items-center gap-2 mb-4 group focus:outline-none"
      >
        <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim group-hover:text-amber transition-colors">
          Cast &amp; Crew
        </h4>
        <ChevronDown
          className={cn('w-3.5 h-3.5 text-paper-faint transition-transform group-hover:text-amber', expanded ? 'rotate-180' : '')}
        />
      </button>
      {expanded && (
      <div className="space-y-4">
      {hasCast && (
        <div>
          <div
            className="font-mono mb-2"
            style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
          >
            Main Cast
          </div>
          <CastGrid cast={cast} onPersonClick={onPersonClick} />
        </div>
      )}

      {(hasCrew || hasStudios) && (
        <div className="space-y-1.5">
          {hasCrew && CREW_DISPLAY.map(({ jobs, label }) => {
            const members = crew!.filter((c) => jobs.includes(c.job))
            if (members.length === 0) return null
            return (
              <div key={label} className="flex gap-3" style={{ fontSize: '12px' }}>
                <span
                  className="font-mono shrink-0 text-right"
                  style={{ width: '80px', color: 'var(--paper-faint)', fontSize: '10px', paddingTop: '1px' }}
                >
                  {label}
                </span>
                <span className="font-sans" style={{ color: 'var(--paper)' }}>
                  {members.map((m, i) => (
                    <span key={m.tmdbPersonId}>
                      {i > 0 && ' · '}
                      <button
                        type="button"
                        onClick={() => onPersonClick({ tmdbPersonId: m.tmdbPersonId, name: m.name, profileUrl: m.profileUrl, job: m.job })}
                        aria-label={`View details for ${m.name}`}
                        className="text-paper transition-colors hover:text-amber focus-visible:text-amber focus:outline-none"
                      >
                        {m.name}
                      </button>
                    </span>
                  ))}
                </span>
              </div>
            )
          })}
          {hasStudios && (
            <div className="flex gap-3" style={{ fontSize: '12px' }}>
              <span
                className="font-mono shrink-0 text-right"
                style={{ width: '80px', color: 'var(--paper-faint)', fontSize: '10px', paddingTop: '1px' }}
              >
                Studio
              </span>
              <span className="font-sans" style={{ color: 'var(--paper)' }}>
                {studios!.map((s, i) => (
                  <span key={s}>
                    {i > 0 && ', '}
                    <button
                      type="button"
                      onClick={() => onStudioClick(s)}
                      aria-label={`Browse titles from ${s}`}
                      className="text-paper transition-colors hover:text-amber focus-visible:text-amber focus:outline-none"
                    >
                      {s}
                    </button>
                  </span>
                ))}
              </span>
            </div>
          )}
        </div>
      )}
      </div>
      )}
    </div>
  )
}

// ─── TV: Season selector + episode carousel ───────────────────────────────────

interface TVSeriesSectionProps {
  titleId: string
  seasons: Season[]
  isSharedView: boolean
  isSpiderNoir: boolean
  onPersonClick: (person: PersonDetailTarget) => void
  onColorModeSelected?: (mode: 'bw' | 'color') => void
}

// Episodes in the given seasons with no watch event yet — the set a
// pre-platform bulk mark would touch.
function unwatchedEpisodeCount(seasons: Season[]): number {
  return seasons.reduce(
    (sum, s) => sum + (s.episodes?.filter((e) => e.watchEvents.length === 0).length ?? 0),
    0
  )
}

function TVSeriesSection({ titleId, seasons, isSharedView, isSpiderNoir, onPersonClick, onColorModeSelected }: TVSeriesSectionProps) {
  const markPrePlatformWatched = useAppStore((s) => s.markPrePlatformWatched)
  const [confirmPrePlatform, setConfirmPrePlatform] = useState<'series' | 'season' | null>(null)
  const [selectedSeason, setSelectedSeason] = useState(seasons[0]?.seasonNumber ?? 1)
  const [selectedEpId, setSelectedEpId] = useState<string | null>(null)
  const [castExpanded, setCastExpanded] = useState(true)
  const [canScrollLeft, setCanScrollLeft] = useState(false)
  const [canScrollRight, setCanScrollRight] = useState(false)
  const carouselRef = useRef<HTMLDivElement>(null)
  const graphClickEpIdRef = useRef<string | null>(null)

  const season = seasons.find((s) => s.seasonNumber === selectedSeason)
  const hasEpisodes = (s: Season) => (s.episodes?.length ?? 0) > 0
  const selectedEp = season?.episodes?.find((e) => e.id === selectedEpId) ?? null

  const totalWatched = totalEpisodesWatched(seasons)
  const totalCount = totalEpisodeCount(seasons)
  const seriesAvg = avgSeriesRating(seasons)

  const CARD_WIDTH = 252 // 240px card + 12px gap

  function handleCarouselScroll() {
    const el = carouselRef.current
    if (!el) return
    setCanScrollLeft(el.scrollLeft > 4)
    setCanScrollRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 4)
  }

  function scrollCarousel(dir: 'left' | 'right') {
    carouselRef.current?.scrollBy({ left: dir === 'right' ? CARD_WIDTH : -CARD_WIDTH, behavior: 'smooth' })
  }

  // Reset carousel + selection on season change (honours graph-click ref)
  useEffect(() => {
    const pendingEpId = graphClickEpIdRef.current
    graphClickEpIdRef.current = null
    const el = carouselRef.current
    if (el) el.scrollLeft = 0
    setTimeout(() => {
      setSelectedEpId(pendingEpId)
      setCanScrollLeft(false)
    }, 0)
    const t = setTimeout(() => {
      const el2 = carouselRef.current
      if (el2) setCanScrollRight(el2.scrollWidth > el2.clientWidth + 4)
    }, 50)
    return () => clearTimeout(t)
  }, [selectedSeason])

  function handleSeasonChange(seasonNumber: number) {
    setSelectedSeason(seasonNumber)
    setConfirmPrePlatform(null)
  }

  const seriesUnwatched = unwatchedEpisodeCount(seasons)
  const seasonUnwatched = season ? unwatchedEpisodeCount([season]) : 0

  function renderPrePlatformMark(scope: 'series' | 'season') {
    const count = scope === 'series' ? seriesUnwatched : seasonUnwatched
    if (isSharedView || count === 0) return null
    const label = scope === 'series'
      ? 'Watched entire series before joining'
      : `Watched season ${season?.seasonNumber} before joining`
    return confirmPrePlatform === scope ? (
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
          Mark {count} episode{count === 1 ? '' : 's'} as watched (no date)?
        </span>
        <div className="flex items-center gap-3">
          <button
            onClick={() => {
              markPrePlatformWatched(titleId, scope === 'season' ? season?.seasonNumber : undefined)
              setConfirmPrePlatform(null)
            }}
            className="font-mono text-xs transition-colors text-amber hover:text-amber/80"
            aria-label={`Confirm marking ${count} episodes as watched before joining`}
          >
            Confirm
          </button>
          <button
            onClick={() => setConfirmPrePlatform(null)}
            className="font-mono text-xs transition-opacity hover:opacity-80"
            style={{ color: 'var(--paper-faint)' }}
            aria-label="Cancel marking as watched before joining"
          >
            Cancel
          </button>
        </div>
      </div>
    ) : (
      <button
        onClick={() => setConfirmPrePlatform(scope)}
        className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber"
      >
        <Clock className="w-3 h-3" />
        {label}
      </button>
    )
  }

  return (
    <div className="space-y-5">
      {/* Series-level stats */}
      <div className="grid grid-cols-3 gap-4">
        <div className="rounded-lg px-3 py-1.5 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--paper)', fontVariationSettings: '"opsz" 30' }}>
            {totalWatched}<span className="text-sm font-mono ml-0.5" style={{ color: 'var(--paper-faint)' }}>/{totalCount}</span>
          </div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Episodes</div>
        </div>
        <div className="rounded-lg px-3 py-1.5 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--amber)', fontVariationSettings: '"opsz" 30' }}>
            {seriesAvg !== null ? `★ ${seriesAvg.toFixed(1)}` : '—'}
          </div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Avg Rating</div>
        </div>
        <div className="rounded-lg px-3 py-1.5 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--paper)', fontVariationSettings: '"opsz" 30' }}>{seasons.length}</div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Seasons</div>
        </div>
      </div>

      {/* Bulk pre-platform mark: series scope */}
      {renderPrePlatformMark('series')}

      {/* Series Graph heatmap */}
      {seasons.some(hasEpisodes) && (
        <div>
          <h4 className="font-mono mb-3" style={{ fontSize: '10px', letterSpacing: '0.18em', textTransform: 'uppercase', color: 'var(--paper-faint)' }}>
            Series Graph
          </h4>
          <SeriesGraph
            seasons={seasons}
            onCellClick={(seasonNumber, episodeNumber) => {
              const targetSeason = seasons.find((s) => s.seasonNumber === seasonNumber)
              const ep = targetSeason?.episodes?.find((e) => e.episodeNumber === episodeNumber)
              if (ep) graphClickEpIdRef.current = ep.id
              handleSeasonChange(seasonNumber)
            }}
          />
        </div>
      )}

      {/* Smart season selector */}
      {seasons.length <= 3 ? (
        <div className="flex gap-2 overflow-x-auto scrollbar-none">
          {seasons.map((s) => {
            const watched = episodesWatchedInSeason(s)
            const pct = s.episodeCount > 0 ? Math.round((watched / s.episodeCount) * 100) : 0
            const seasonAvg = avgSeasonRating(s)
            return (
              <button
                key={s.seasonNumber}
                onClick={() => handleSeasonChange(s.seasonNumber)}
                aria-label={`Season ${s.seasonNumber}`}
                aria-current={selectedSeason === s.seasonNumber ? 'true' : undefined}
                className={cn(
                  'shrink-0 px-3.5 py-2.5 rounded-lg text-left transition-all border',
                  selectedSeason === s.seasonNumber
                    ? 'border-amber/40 bg-amber/10'
                    : 'border-transparent hover:border-[var(--line)] hover:bg-[var(--wash)]'
                )}
              >
                <div className="font-mono" style={{ fontSize: '13px', color: selectedSeason === s.seasonNumber ? 'var(--amber)' : 'var(--paper-dim)' }}>
                  S{s.seasonNumber}
                </div>
                <div className="font-mono" style={{ fontSize: '11px', color: 'var(--paper-faint)' }}>
                  {pct}%{seasonAvg !== null ? ` · ★${seasonAvg.toFixed(1)}` : ''}
                </div>
              </button>
            )
          })}
        </div>
      ) : (
        <select
          value={selectedSeason}
          onChange={(e) => handleSeasonChange(parseInt(e.target.value, 10))}
          aria-label="Select season"
          className="font-mono text-sm rounded-lg px-3 py-2 bg-secondary border border-amber/30 focus:outline-none focus:border-amber/60"
          style={{ color: 'var(--amber)' }}
        >
          {seasons.map((s) => {
            const watched = episodesWatchedInSeason(s)
            const pct = s.episodeCount > 0 ? Math.round((watched / s.episodeCount) * 100) : 0
            const seasonAvg = avgSeasonRating(s)
            return (
              <option key={s.seasonNumber} value={s.seasonNumber}>
                {`Season ${s.seasonNumber} · ${pct}%${seasonAvg !== null ? ` · ★${seasonAvg.toFixed(1)}` : ''}`}
              </option>
            )
          })}
        </select>
      )}

      {/* Bulk pre-platform mark: selected-season scope */}
      {renderPrePlatformMark('season')}

      {/* Season cast */}
      {season?.cast && season.cast.length > 0 && (
        <div className="pl-3 border-l-2" style={{ borderColor: 'var(--line)' }}>
          <button
            type="button"
            onClick={() => setCastExpanded((e) => !e)}
            aria-expanded={castExpanded}
            className="flex items-center gap-1.5 mb-2 group focus:outline-none"
          >
            <span className="font-mono group-hover:text-amber transition-colors" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>
              Season {season.seasonNumber} Cast
            </span>
            <ChevronDown
              className={cn('w-3 h-3 text-paper-faint transition-transform group-hover:text-amber', castExpanded ? 'rotate-180' : '')}
            />
          </button>
          {castExpanded && (
          <div className="flex gap-2.5 overflow-x-auto scrollbar-none pb-1 -mx-6 px-6">
            {season.cast.map((member) => (
              <button
                key={member.tmdbPersonId}
                type="button"
                onClick={() => onPersonClick({ tmdbPersonId: member.tmdbPersonId, name: member.name, profileUrl: member.profileUrl, character: member.character })}
                aria-label={`View details for ${member.name}`}
                className="group shrink-0 w-[110px] overflow-hidden rounded-lg text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 transition-all"
                style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}
              >
                <div className="aspect-[2/3] overflow-hidden">
                  {member.profileUrl ? (
                    <img src={member.profileUrl} alt={member.name} className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105" />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center" style={{ background: 'var(--card)' }}>
                      <span className="font-mono text-3xl" style={{ color: 'var(--paper-faint)' }}>{member.name.charAt(0).toUpperCase()}</span>
                    </div>
                  )}
                </div>
                <div className="p-2">
                  <div className="font-sans font-semibold line-clamp-1 transition-colors group-hover:text-amber" style={{ fontSize: '12px', color: 'var(--paper)', lineHeight: 1.3 }} title={member.name}>{member.name}</div>
                  <div className="font-mono line-clamp-1 mt-0.5" style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: member.character ? 0.6 : 0 }} title={member.character}>{member.character || ' '}</div>
                  {member.episodeCount != null && (
                    <div className="font-mono mt-0.5" style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: 0.7 }}>
                      {member.episodeCount} ep{member.episodeCount !== 1 ? 's' : ''}
                    </div>
                  )}
                </div>
              </button>
            ))}
          </div>
          )}
        </div>
      )}

      {/* Episode carousel */}
      {season && hasEpisodes(season) && (
        <div>
          <div className="relative">
            {/* Left arrow */}
            {canScrollLeft && (
              <button
                type="button"
                onClick={() => scrollCarousel('left')}
                aria-label="Scroll episodes left"
                className="absolute left-0 top-1/2 -translate-y-1/2 z-10 -translate-x-2 rounded-full p-1.5 transition-opacity hover:opacity-100 opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                style={{ background: 'var(--card)', border: '1px solid var(--line)' }}
              >
                <ChevronLeft className="w-4 h-4" style={{ color: 'var(--paper)' }} />
              </button>
            )}

            {/* Carousel */}
            <div
              ref={carouselRef}
              onScroll={handleCarouselScroll}
              className="flex gap-3 overflow-x-auto scrollbar-none pb-1"
            >
              {season.episodes!.map((ep) => (
                <EpisodeCard
                  key={ep.id}
                  episode={ep}
                  season={season}
                  titleId={titleId}
                  isSelected={selectedEpId === ep.id}
                  onSelect={() => setSelectedEpId(selectedEpId === ep.id ? null : ep.id)}
                  isSharedView={isSharedView}
                  isSpiderNoir={isSpiderNoir}
                />
              ))}
            </div>

            {/* Right arrow */}
            {canScrollRight && (
              <button
                type="button"
                onClick={() => scrollCarousel('right')}
                aria-label="Scroll episodes right"
                className="absolute right-0 top-1/2 -translate-y-1/2 z-10 translate-x-2 rounded-full p-1.5 transition-opacity hover:opacity-100 opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                style={{ background: 'var(--card)', border: '1px solid var(--line)' }}
              >
                <ChevronRight className="w-4 h-4" style={{ color: 'var(--paper)' }} />
              </button>
            )}
          </div>

          {/* Inline logging panel — renders below the carousel when a card is selected */}
          {selectedEp && (
            <div className="mt-3">
              <EpisodePanel
                episode={selectedEp}
                season={season}
                titleId={titleId}
                isSharedView={isSharedView}
                isSpiderNoir={isSpiderNoir}
                onColorModeSelected={onColorModeSelected}
              />
            </div>
          )}
        </div>
      )}

      {/* Fallback: coarse progress bar when season has no episode-level data */}
      {season && !hasEpisodes(season) && (() => {
        const pct = season.episodeCount > 0 ? (season.episodesWatched / season.episodeCount) * 100 : 0
        return (
          <div className="flex items-center gap-3 px-2 py-3">
            <span className="font-mono text-xs text-muted-foreground w-8 shrink-0">S{season.seasonNumber}</span>
            <div className="flex-1 h-1.5 bg-secondary rounded-full overflow-hidden">
              <div className="h-full bg-amber rounded-full transition-all" style={{ width: `${pct}%` }} />
            </div>
            <span className="font-mono text-xs text-muted-foreground w-12 text-right shrink-0">
              {season.episodesWatched}/{season.episodeCount}
            </span>
          </div>
        )
      })()}
    </div>
  )
}

// ─── Inline tag editor ───────────────────────────────────────────────────────

function DrawerTagEditor({
  tags,
  isSharedView,
  onChange,
}: {
  tags: string[]
  isSharedView: boolean
  onChange: (tags: string[]) => void
}) {
  const [input, setInput] = useState('')
  const [editing, setEditing] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  function commit() {
    const trimmed = input.trim().replace(/,+$/, '')
    if (trimmed && !tags.includes(trimmed)) {
      onChange([...tags, trimmed])
    }
    setInput('')
  }

  function handleKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      commit()
    } else if (e.key === 'Backspace' && input === '' && tags.length > 0) {
      onChange(tags.slice(0, -1))
    } else if (e.key === 'Escape') {
      commit()
      setEditing(false)
    }
  }

  function removeTag(tag: string) {
    onChange(tags.filter((t) => t !== tag))
  }

  if (isSharedView) {
    if (tags.length === 0) return null
    return (
      <div className="flex flex-wrap gap-1.5">
        {tags.map((t) => (
          <MetaBadge key={t} className="border-amber/30 text-amber/80">
            <Tag className="w-2.5 h-2.5 mr-1 inline" />{t}
          </MetaBadge>
        ))}
      </div>
    )
  }

  return (
    <div className="flex flex-wrap gap-1.5">
        {tags.map((t) => (
          <span
            key={t}
            className="flex items-center gap-1 px-2 py-0.5 rounded-full bg-amber/10 border border-amber/20 font-mono text-xs text-amber"
          >
            {t}
            <button
              type="button"
              onClick={() => removeTag(t)}
              className="hover:text-amber-bright transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded-full"
              aria-label={`Remove tag ${t}`}
              title={`Remove tag ${t}`}
            >
              <X className="w-2.5 h-2.5" />
            </button>
          </span>
        ))}
        {editing && (
          <input
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKey}
            onBlur={() => { commit(); setEditing(false) }}
            placeholder="tag name…"
            aria-label="New tag name"
            className="px-2 py-0.5 rounded-full bg-secondary border border-amber/30 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-amber/60 w-28"
          />
        )}
        {!editing && (
          <button
            onClick={() => { setEditing(true); setTimeout(() => inputRef.current?.focus(), 0) }}
            className="flex items-center gap-1 px-2 py-0.5 rounded-full border border-dashed border-amber/20 font-mono text-xs text-muted-foreground hover:border-amber/40 hover:text-amber/70 transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
          >
            <Tag className="w-2.5 h-2.5" /> add tag
          </button>
        )}
    </div>
  )
}

// ─── Franchise section — other movies in the collection + watch progress ─────
// KP-027 (franchise strip) / KP-028 (watched X/Y progress). Parts come from
// TMDB's /collection/{id} via the media-proxy; library membership and watched
// state are resolved client-side against the store's titles.

function FranchiseSection({
  collectionId,
  collectionName,
  currentTmdbId,
  isSharedView,
}: {
  collectionId: number
  collectionName?: string
  currentTmdbId: number
  isSharedView: boolean
}) {
  const { titles, openDetailDrawer, openAddTitlePreselected } = useAppStore(
    useShallow((s) => ({
      titles: s.titles,
      openDetailDrawer: s.openDetailDrawer,
      openAddTitlePreselected: s.openAddTitlePreselected,
    }))
  )
  const [parts, setParts] = useState<SearchResult[]>([])
  const [loadedCollectionId, setLoadedCollectionId] = useState<number | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchCollectionParts(collectionId)
      .then((p) => {
        if (cancelled) return
        setParts(p)
        setLoadedCollectionId(collectionId)
      })
      .catch((err) => {
        console.error('collection parts error:', err)
        if (cancelled) return
        setParts([])
        setLoadedCollectionId(collectionId)
      })
    return () => { cancelled = true }
  }, [collectionId])

  const loading = loadedCollectionId !== collectionId

  const libraryByTmdbId = useMemo(() => {
    const map = new Map<number, Title>()
    for (const t of titles) if (t.tmdbId) map.set(t.tmdbId, t)
    return map
  }, [titles])

  // A one-part "collection" (or a fetch failure) has nothing to show.
  if (!loading && parts.length < 2) return null

  const watchedCount = parts.filter((p) => p.tmdbId != null && libraryByTmdbId.get(p.tmdbId)?.status === 'watched').length
  const franchiseLabel = (collectionName ?? 'Franchise').replace(/\s+Collection$/i, '')

  return (
    <div>
      <div className="flex items-baseline justify-between gap-3 mb-2 flex-wrap">
        <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim">
          {franchiseLabel} <span className="normal-case tracking-normal text-muted-foreground font-normal">· franchise</span>
        </h4>
        {!loading && (
          <span className="font-mono text-[11px] text-paper-faint">
            Watched <span className="text-amber">{watchedCount}</span>/{parts.length}
          </span>
        )}
      </div>

      {!loading && (
        <div
          className="h-1 rounded-full overflow-hidden mb-3"
          style={{ background: 'var(--inset)' }}
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={parts.length}
          aria-valuenow={watchedCount}
          aria-label={`${franchiseLabel}: ${watchedCount} of ${parts.length} movies watched`}
        >
          <div
            className="h-full rounded-full transition-[width] duration-500"
            style={{ width: `${(watchedCount / parts.length) * 100}%`, background: 'var(--amber)' }}
          />
        </div>
      )}

      {loading ? (
        <div className="flex gap-2.5 overflow-hidden">
          {Array.from({ length: 4 }, (_, i) => (
            <div key={i} className="shrink-0 w-[84px] aspect-[2/3] rounded-lg animate-pulse" style={{ background: 'var(--inset)' }} />
          ))}
        </div>
      ) : (
        <div className="flex gap-2.5 overflow-x-auto pb-1 scrollbar-none">
          {parts.map((p) => {
            const libTitle = p.tmdbId != null ? libraryByTmdbId.get(p.tmdbId) : undefined
            const isCurrent = p.tmdbId === currentTmdbId
            const isWatched = libTitle?.status === 'watched'
            return (
              <button
                key={p.tmdbId}
                onClick={() => {
                  if (isCurrent) return
                  if (libTitle) openDetailDrawer(libTitle.id)
                  else if (!isSharedView) openAddTitlePreselected(p)
                }}
                disabled={isCurrent || (!libTitle && isSharedView)}
                title={
                  isCurrent
                    ? `${p.title} — currently viewing`
                    : libTitle
                      ? `${p.title} — open details`
                      : isSharedView ? p.title : `${p.title} — add to library`
                }
                className={cn(
                  'shrink-0 w-[84px] text-left group focus-visible:outline-none',
                  isCurrent ? 'cursor-default' : 'cursor-pointer'
                )}
              >
                <div
                  className={cn(
                    'relative aspect-[2/3] rounded-lg overflow-hidden border transition-transform',
                    !isCurrent && 'group-hover:scale-[1.03] group-focus-visible:ring-1 group-focus-visible:ring-amber/60',
                    isCurrent ? 'border-amber/60' : 'border-[var(--line)]'
                  )}
                  style={{ background: 'var(--inset)' }}
                >
                  {p.posterUrl ? (
                    <img src={p.posterUrl} alt={p.title} loading="lazy" className="w-full h-full object-cover" />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center">
                      <Film className="w-5 h-5 text-paper-faint opacity-30" />
                    </div>
                  )}
                  {isWatched && (
                    <span
                      className="absolute top-1 right-1 w-4 h-4 rounded-full flex items-center justify-center"
                      style={{ background: 'var(--amber)' }}
                      title="Watched"
                    >
                      <Check className="w-2.5 h-2.5 text-[color:var(--on-amber)]" strokeWidth={3} />
                    </span>
                  )}
                  {!libTitle && !isSharedView && (
                    <span className="absolute inset-0 flex items-center justify-center bg-black/50 opacity-0 group-hover:opacity-100 transition-opacity">
                      <Plus className="w-5 h-5 text-white" />
                    </span>
                  )}
                </div>
                <p className={cn('font-sans text-[10px] leading-tight line-clamp-2 mt-1', isCurrent ? 'text-amber' : 'text-paper-dim')}>
                  {p.title}
                </p>
                <p className="font-mono text-[9px] text-paper-faint mt-0.5">{p.year > 0 ? p.year : 'TBA'}</p>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}

// ─── Cinema Outings — scheduled banner (plan §4.6) ───────────────────────────

function OutingBanner({ title }: { title: Title }) {
  const outing = useAppStore((s) => s.outings.find((o) => o.titleId === title.id && o.status === 'scheduled'))
  // Follow-up ("how was it?") banner (plan §4.6) — only surfaced when there's
  // no scheduled outing to show instead; a rewatch mid-follow-up is a rare
  // enough overlap that the scheduled banner simply takes priority.
  const pendingFollowUp = useAppStore((s) =>
    outing ? null : findPendingFollowUpOuting(s.outings, title.id, new Date())
  )
  const { openOutingSchedule, openPostShowSheet, cancelOuting } = useAppStore(
    useShallow((s) => ({
      openOutingSchedule: s.openOutingSchedule,
      openPostShowSheet: s.openPostShowSheet,
      cancelOuting: s.cancelOuting,
    }))
  )
  const [confirmingCancel, setConfirmingCancel] = useState(false)
  const [sharePanelOpen, setSharePanelOpen] = useState(false)

  if (!outing && pendingFollowUp) {
    return (
      <div className="px-4 sm:px-6 pt-4">
        <button
          onClick={() => openPostShowSheet(pendingFollowUp.id)}
          className="w-full flex items-center gap-2 rounded-lg px-3 py-2.5 border border-amber/25 bg-amber/[0.06] hover:bg-amber/[0.1] transition-colors text-left"
        >
          <Clapperboard className="w-3.5 h-3.5 text-amber shrink-0" aria-hidden="true" />
          <span className="font-mono text-xs text-amber">{title.title} just let out — how was it?</span>
        </button>
      </div>
    )
  }

  if (!outing) return null

  const showtime = new Date(outing.showtime)
  const now = new Date().getTime()
  const nowShowing = now >= showtime.getTime() && now < new Date(outing.endsAt).getTime()
  const dateLabel = showtime.toLocaleDateString('en-US', { weekday: 'long', month: 'short', day: 'numeric' })
  const timeLabel = showtime.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true })
  const companionLabel = formatCompanions(outing.companions)
  const summary = [nowShowing ? 'NOW SHOWING' : `${dateLabel} · ${timeLabel}`, outing.venue, companionLabel && `with ${companionLabel}`]
    .filter(Boolean)
    .join(' · ')

  return (
    <div className="px-4 sm:px-6 pt-4">
      <div
        className="flex flex-wrap items-center gap-x-2 gap-y-1.5 rounded-lg px-3 py-2.5 border border-amber/25 bg-amber/[0.06]"
        aria-label={`Scheduled cinema outing: ${summary}`}
      >
        <Ticket className="w-3.5 h-3.5 text-amber shrink-0" aria-hidden="true" />
        <span className="font-mono text-xs text-amber">{summary}</span>
        {confirmingCancel ? (
          <span className="flex items-center gap-2 ml-auto">
            <span className="font-mono text-xs text-muted-foreground">Cancel these tickets?</span>
            <button onClick={() => cancelOuting(outing.id)} className="font-mono text-xs" style={{ color: 'var(--ember)' }} aria-label="Yes, cancel these tickets">
              Yes
            </button>
            <button onClick={() => setConfirmingCancel(false)} className="font-mono text-xs text-muted-foreground hover:text-foreground transition-colors" aria-label="No, keep these tickets">
              No
            </button>
          </span>
        ) : (
          <span className="flex items-center gap-3 ml-auto">
            <button
              onClick={() => setSharePanelOpen(true)}
              className="font-mono text-xs text-amber/80 hover:text-amber transition-colors"
            >
              Share
            </button>
            <button
              onClick={() => openOutingSchedule(title.id, outing.id)}
              className="font-mono text-xs text-amber/80 hover:text-amber transition-colors"
            >
              Edit
            </button>
            <button
              onClick={() => setConfirmingCancel(true)}
              className="font-mono text-xs text-muted-foreground hover:text-ember transition-colors"
            >
              Cancel
            </button>
          </span>
        )}
      </div>
      {sharePanelOpen && (
        <ShareOutingPanel outing={outing} title={title} onClose={() => setSharePanelOpen(false)} />
      )}
    </div>
  )
}

// ─── Main drawer ─────────────────────────────────────────────────────────────

export function TitleDetailDrawer() {
  // ⚡ Bolt: Prevent unnecessary re-renders by using useShallow
  const { isDetailDrawerOpen, closeDetailDrawer, updateTitle, removeTitle, removeViewing, openRefreshMetadata, isSharedView, viewerContext, openOutingSchedule } = useAppStore(
    useShallow((s) => ({
      isDetailDrawerOpen: s.isDetailDrawerOpen,
      closeDetailDrawer: s.closeDetailDrawer,
      updateTitle: s.updateTitle,
      removeTitle: s.removeTitle,
      removeViewing: s.removeViewing,
      openRefreshMetadata: s.openRefreshMetadata,
      isSharedView: s.isSharedView,
      viewerContext: s.viewerContext,
      openOutingSchedule: s.openOutingSchedule,
    }))
  )
  const browseByStudio = useAppStore((s) => s.browseByStudio)
  const title = useSelectedTitle()
  const user = useAppStore((s) => s.user)

  const isSpiderNoir = title?.tmdbId === SPIDER_NOIR_TMDB_ID
  const isMatrix = title?.tmdbId === THE_MATRIX_TMDB_ID

  const [showMatrixModal, setShowMatrixModal] = useState(false)
  const [showMatrixRain, setShowMatrixRain] = useState(false)

  const [sendPanelOpen, setSendPanelOpen] = useState(false)

  const { pinnedModes, setPinnedMode, unlockTheme } = useAppStore(
    useShallow((s) => ({
      pinnedModes: s.pinnedModes,
      setPinnedMode: s.setPinnedMode,
      unlockTheme: s.unlockTheme,
    }))
  )

  const unlockedModes = useMemo(
    () => (isSpiderNoir && title ? getUnlockedModes(title) : new Set<'bw' | 'color'>()),
    [isSpiderNoir, title]
  )
  const earnedModes = useMemo(
    () => (isSpiderNoir && title ? getEarnedModes(title) : new Set<'bw' | 'color'>()),
    [isSpiderNoir, title]
  )

  // Global theme easter eggs: earning the Spider-Noir black & white mode, or
  // taking the Matrix red pill, unlocks the matching app-wide theme (Settings
  // → Appearance). Keyed on the "earned"/"rain shown" signals so it fires
  // regardless of whether the title was already watched before this session.
  useEffect(() => {
    if (earnedModes.has('bw')) unlockTheme('noir')
  }, [earnedModes, unlockTheme])

  useEffect(() => {
    if (showMatrixRain) unlockTheme('matrix')
  }, [showMatrixRain, unlockTheme])

  const prevNoirModeRef = useRef<'bw' | 'color' | null | undefined>(undefined)
  const [noirAnim, setNoirAnim] = useState<'bw' | 'color' | null>(null)
  const noirAnimTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [manualMode, setManualMode] = useState<SelectorMode>('normal')

  // closeDetailDrawer only sets isDetailDrawerOpen=false, not selectedTitleId=null,
  // so title and isSpiderNoir remain valid here even when the drawer is closed.
  const pinnedModeRaw = (title ? (pinnedModes[`${title.id}:${EASTER_EGG_KEY}`] ?? null) : null) as 'bw' | 'color' | null

  // Seed manualMode from pinned → last watch event → normal when the drawer opens.
  // setTimeout(0) defers the setState out of the effect body to satisfy react-hooks/set-state-in-effect.
  useEffect(() => {
    if (!isSpiderNoir || !title || !isDetailDrawerOpen) return
    const derived = getSpiderNoirActiveMode(title)
    const seeded = pinnedModeRaw ?? derived ?? 'normal'
    const t = setTimeout(() => setManualMode(seeded as SelectorMode), 0)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title?.id, isDetailDrawerOpen])

  // While drawer is open: use manual selection. When closed: use pinned (if any).
  const effectiveNoirMode: 'bw' | 'color' | null = isSpiderNoir
    ? (isDetailDrawerOpen ? (manualMode !== 'normal' ? (manualMode as 'bw' | 'color') : null) : pinnedModeRaw)
    : null

  useEffect(() => {
    const ALL = ['spider-noir-bw', 'spider-noir-color', 'spider-noir-bw-enter', 'spider-noir-color-enter'] as const
    const prevMode = prevNoirModeRef.current

    // Skip the transition on the very first render (prev === undefined) —
    // the drawer isn't open yet so there's nothing to animate between.
    // Also skip if the mode hasn't actually changed.
    const isVisualChange = prevMode !== undefined && prevMode !== effectiveNoirMode

    function applyClasses() {
      document.body.classList.remove(...ALL)
      if (effectiveNoirMode) {
        document.body.classList.add(effectiveNoirMode === 'bw' ? 'spider-noir-bw' : 'spider-noir-color')
        document.body.classList.add(effectiveNoirMode === 'bw' ? 'spider-noir-bw-enter' : 'spider-noir-color-enter')
      }
    }

    if (isVisualChange) {
      // The View Transition must capture the current (pre-change) DOM state
      // before applyClasses runs. We pass applyClasses as the commit so the
      // API snapshots before/after correctly — this works for both the enter
      // (normal → noir) and exit (noir → normal) directions.
      transitionSpiderNoir(applyClasses)
      if (noirAnimTimerRef.current) clearTimeout(noirAnimTimerRef.current)
      if (effectiveNoirMode) {
        // Entering or switching: cast the web overlay on top of the VT reveal.
        // Deferred so it runs as an async callback (not synchronously in the
        // effect body) — avoids the react-hooks/set-state-in-effect lint rule.
        noirAnimTimerRef.current = setTimeout(() => {
          setNoirAnim(effectiveNoirMode)
          noirAnimTimerRef.current = setTimeout(() => {
            setNoirAnim(null)
            document.body.classList.remove('spider-noir-bw-enter', 'spider-noir-color-enter')
          }, 2100)
        }, 0)
      } else {
        // Exiting: dismiss any lingering web overlay so it doesn't hang over
        // the returned normal view.
        noirAnimTimerRef.current = setTimeout(() => setNoirAnim(null), 0)
      }
    } else {
      applyClasses()
    }

    prevNoirModeRef.current = effectiveNoirMode

    // Do NOT remove body classes in the cleanup — the cleanup fires before the
    // next effect run, which would cause the exit View Transition to capture
    // no-noir → no-noir (invisible). Class removal is handled by applyClasses
    // in the next run. The timer must still be cleared on unmount.
    return () => {
      if (noirAnimTimerRef.current) clearTimeout(noirAnimTimerRef.current)
    }
  }, [effectiveNoirMode])

  const [showLogForm, setShowLogForm] = useState(false)
  const [logDate, setLogDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [logPrePlatform, setLogPrePlatform] = useState(false)
  const [logRating, setLogRating] = useState(0)
  const [logNotes, setLogNotes] = useState('')
  // Home viewings can record company too (plan §4.6) — the generalized
  // payoff of putting venue/companions on `viewings` rather than only outings.
  const [logVenue, setLogVenue] = useState('')
  const [logCompanions, setLogCompanions] = useState<Companion[]>([])
  const [showMovieSaved, setShowMovieSaved] = useState(false)
  const [pendingDeleteTitle, setPendingDeleteTitle] = useState(false)
  const [posterLightboxOpen, setPosterLightboxOpen] = useState(false)
  const [activePerson, setActivePerson] = useState<PersonDetailTarget | null>(null)
  const [videos, setVideos] = useState<TitleVideo[]>([])
  const [watchProviders, setWatchProviders] = useState<WatchProviders | null>(null)
  const [logoUrl, setLogoUrl] = useState<string | null>(null)
  const [heroBackdropUrl, setHeroBackdropUrl] = useState<string | null>(null)

  const displayLogoUrl = isSpiderNoir
    ? (effectiveNoirMode === 'color' ? SPIDER_NOIR_LOGO_COLOR : effectiveNoirMode === 'bw' ? SPIDER_NOIR_LOGO_BW : SPIDER_NOIR_LOGO_NORMAL)
    : logoUrl

  useEffect(() => {
    if (!isDetailDrawerOpen || !title?.tmdbId) {
      // Deferred to satisfy react-hooks/set-state-in-effect (same pattern as manualMode seeding above).
      const t = setTimeout(() => setVideos([]), 0)
      return () => clearTimeout(t)
    }
    let cancelled = false
    fetchTitleVideos(title.tmdbId, title.type).then((v) => {
      if (!cancelled) setVideos(v)
    })
    return () => { cancelled = true }
  }, [isDetailDrawerOpen, title?.tmdbId, title?.type])

  useEffect(() => {
    if (!isDetailDrawerOpen || !title?.tmdbId) {
      const t = setTimeout(() => setWatchProviders(null), 0)
      return () => clearTimeout(t)
    }
    let cancelled = false
    fetchWatchProviders(title.tmdbId, title.type).then((wp) => {
      if (!cancelled) setWatchProviders(wp)
    })
    return () => { cancelled = true }
  }, [isDetailDrawerOpen, title?.tmdbId, title?.type])

  // Images: best logo + best backdrop at original resolution, fetched together.
  // No backdropUrl guard — we fetch for any title with a tmdbId so titles
  // without a stored backdrop can still get a cinematic hero + logo.
  useEffect(() => {
    if (!isDetailDrawerOpen || !title?.tmdbId) {
      const t = setTimeout(() => { setLogoUrl(null); setHeroBackdropUrl(null) }, 0)
      return () => clearTimeout(t)
    }
    let cancelled = false
    fetchTitleImages(title.tmdbId, title.type).then(({ logoUrl: logo, backdropUrl: backdrop }) => {
      if (!cancelled) {
        setLogoUrl(logo)
        setHeroBackdropUrl(backdrop)
      }
    })
    return () => { cancelled = true }
  }, [isDetailDrawerOpen, title?.tmdbId, title?.type])

  function onClose() {
    setPendingDeleteTitle(false)
    setPosterLightboxOpen(false)
    setActivePerson(null)
    closeDetailDrawer()
  }

  // Track which title IDs have already been backfilled this session to avoid repeat calls.
  const backfilledRef = useRef<Set<string>>(new Set())

  // When a TV show is opened and episode metadata is missing, fetch season details
  // from TMDB and hydrate them in-place, then persist to DB. Handles two cases:
  // (a) season rows exist but no episode rows were ever inserted, and
  // (b) episode rows exist but none have names yet.
  useEffect(() => {
    if (!title || title.type !== 'tv' || !title.seasons || title.tmdbId <= 0 || isSharedView) return

    const seasonsNeedingBackfill = title.seasons.filter((s) =>
      // Case (a): season exists but no episodes were persisted to DB yet
      (s.episodeCount > 0 && (!s.episodes || s.episodes.length === 0)) ||
      // Case (b): episode rows exist but none have a name (pre-feature data)
      (s.episodes && s.episodes.length > 0 && s.episodes.every((ep) => !ep.episodeName)) ||
      // Case (c): episodes exist but season cast was never fetched
      (s.episodes && s.episodes.length > 0 && (!s.cast || s.cast.length === 0))
    )
    if (seasonsNeedingBackfill.length === 0) return

    const cacheKey = `${title.id}:${title.tmdbId}`
    if (backfilledRef.current.has(cacheKey)) return
    backfilledRef.current.add(cacheKey)

    const snapshotTitle = title
    const snapshotUser = user

    const EP_CREW_JOBS_BF = new Set(['Director', 'Writer', 'Teleplay', 'Story'])

    async function backfill() {
      const settled = await Promise.allSettled(
        seasonsNeedingBackfill.map(async (season) => {
          const { episodes: tmdbEps, cast: seasonCast } = await fetchSeasonDetails(snapshotTitle.tmdbId, season.seasonNumber)
          return { season, tmdbEps, seasonCast }
        })
      )

      let updatedSeasons = [...snapshotTitle.seasons!]
      const allUpdatedEpisodes: Parameters<typeof upsertEpisodeMetadataInDb>[2] = []
      const allEpisodeCrew: Array<{ episodeId: string; crew: EpisodeCrew[] }> = []
      const allSeasonCast: Array<{ seasonId: string; cast: CastMember[] }> = []

      for (const result of settled) {
        if (result.status !== 'fulfilled' || result.value.tmdbEps.length === 0) continue
        const { season, tmdbEps, seasonCast } = result.value

        const existingEpisodes = season.episodes || []
        let updatedEpisodes: Episode[]

        if (existingEpisodes.length === 0) {
          updatedEpisodes = tmdbEps.map((tmdbEp) => {
            const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
              .filter((c) => EP_CREW_JOBS_BF.has(c.job))
              .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
            return {
              id: crypto.randomUUID(),
              episodeNumber: tmdbEp.episode_number,
              episodeName: tmdbEp.name || undefined,
              airDate: tmdbEp.air_date || undefined,
              runtime: tmdbEp.runtime || undefined,
              synopsis: tmdbEp.overview || undefined,
              stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : undefined,
              director: epCrew.find((c) => c.job === 'Director')?.name,
              writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
              crew: epCrew.length > 0 ? epCrew : undefined,
              watchEvents: [],
              ratings: [],
              reviews: [],
            }
          })
          updatedSeasons = updatedSeasons.map((s) =>
            s.seasonNumber === season.seasonNumber
              ? { ...s, episodes: updatedEpisodes, episodeCount: updatedEpisodes.length, cast: seasonCast.length > 0 ? seasonCast : s.cast }
              : s
          )
        } else {
          updatedEpisodes = existingEpisodes.map((ep) => {
            const tmdbEp = tmdbEps.find((e) => e.episode_number === ep.episodeNumber)
            if (!tmdbEp) return ep
            const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
              .filter((c) => EP_CREW_JOBS_BF.has(c.job))
              .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
            return {
              ...ep,
              episodeName: tmdbEp.name || ep.episodeName,
              airDate: tmdbEp.air_date || ep.airDate,
              runtime: tmdbEp.runtime || ep.runtime,
              synopsis: tmdbEp.overview || ep.synopsis,
              stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : ep.stillUrl,
              director: epCrew.find((c) => c.job === 'Director')?.name ?? ep.director,
              writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
              crew: epCrew.length > 0 ? epCrew : ep.crew,
            }
          })
          updatedSeasons = updatedSeasons.map((s) =>
            s.seasonNumber === season.seasonNumber
              ? { ...s, episodes: updatedEpisodes, cast: seasonCast.length > 0 ? seasonCast : s.cast }
              : s
          )
        }

        for (const ep of updatedEpisodes) {
          allUpdatedEpisodes.push({
            id: ep.id,
            seasonNumber: season.seasonNumber,
            episodeNumber: ep.episodeNumber,
            episodeName: ep.episodeName,
            airDate: ep.airDate,
            runtime: ep.runtime,
            synopsis: ep.synopsis,
            stillUrl: ep.stillUrl,
          })
          if (ep.crew && ep.crew.length > 0) {
            allEpisodeCrew.push({ episodeId: ep.id, crew: ep.crew })
          }
        }

        if (seasonCast.length > 0) {
          allSeasonCast.push({ seasonId: season.id, cast: seasonCast })
        }
      }

      if (allUpdatedEpisodes.length > 0 || allSeasonCast.length > 0) {
        updateTitle(snapshotTitle.id, { seasons: updatedSeasons })
        if (snapshotUser) {
          if (allUpdatedEpisodes.length > 0) {
            upsertEpisodeMetadataInDb(snapshotUser.id, snapshotTitle.id, allUpdatedEpisodes).catch((e) =>
              console.error('Episode metadata backfill DB write failed:', e)
            )
          }
          if (allSeasonCast.length > 0) {
            bulkUpsertSeasonCastInDb(snapshotUser.id, snapshotTitle.id, allSeasonCast).catch((e) =>
              console.error('Season cast backfill DB write failed:', e)
            )
          }
          if (allEpisodeCrew.length > 0) {
            bulkUpsertEpisodeCrewInDb(snapshotUser.id, snapshotTitle.id, allEpisodeCrew).catch((e) =>
              console.error('Episode crew backfill DB write failed:', e)
            )
          }
        }
      }
    }

    backfill().catch((e) => console.error('Episode metadata backfill failed:', e))
  }, [title?.id, title?.tmdbId]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!title) return null

  function logViewing() {
    if (!title || (!logPrePlatform && !logDate)) return
    const viewing: Viewing = {
      id: crypto.randomUUID(),
      titleId: title.id,
      date: logPrePlatform ? undefined : logDate,
      rating: logRating > 0 ? logRating : undefined,
      notes: logNotes || undefined,
      venue: logVenue.trim() || undefined,
      companions: logCompanions.length > 0 ? logCompanions : undefined,
    }
    updateTitle(title.id, {
      viewings: [...title.viewings, viewing],
      status: 'watched',
      rating: logRating > 0 ? logRating : title.rating,
    })
    setShowMovieSaved(true)
    setTimeout(() => {
      setShowMovieSaved(false)
      setShowLogForm(false)
      setLogDate(new Date().toISOString().slice(0, 10))
      setLogPrePlatform(false)
      setLogRating(0)
      setLogNotes('')
      setLogVenue('')
      setLogCompanions([])
    }, 1500)
  }

  function handleDelete() {
    if (!title) return
    closeDetailDrawer()
    removeTitle(title.id)
  }

  function handleModeSelect(mode: SelectorMode) {
    setManualMode(mode)
  }

  function handleTogglePin(mode: 'bw' | 'color') {
    if (!title) return
    const newVariant = pinnedModeRaw === mode ? null : mode
    setPinnedMode(title.id, EASTER_EGG_KEY, newVariant)
  }

  function handleSaveViewing() {
    if (isMatrix) {
      setShowMatrixModal(true)
    } else {
      logViewing()
    }
  }

  function handleMatrixBlue() {
    setShowMatrixModal(false)
    logViewing()
  }

  function handleMatrixRed() {
    setShowMatrixModal(false)
    logViewing()
    setShowMatrixRain(true)
  }

  return (
    <>
    {noirAnim && <SpiderWebOverlay mode={noirAnim} />}
    {showMatrixRain && <MatrixDigitalRain onDone={() => setShowMatrixRain(false)} />}
    <MatrixPillModal open={showMatrixModal} onBlue={handleMatrixBlue} onRed={handleMatrixRed} />
    <CinemaModal
      open={isDetailDrawerOpen}
      onClose={onClose}
      maxWidth="sm:max-w-2xl"
      title={title.title}
      description={title.synopsis ?? `Details and viewing history for ${title.title}.`}
      expanded
    >
      {/* Poster lightbox — rendered above the dialog content via z-[215] */}
      {posterLightboxOpen && title.posterUrl && (
        <PosterLightbox
          src={title.posterUrl}
          alt={title.title}
          onClose={() => setPosterLightboxOpen(false)}
        />
      )}

      {/* Person detail panel */}
      {activePerson && (
        <PersonDetailPanel
          person={activePerson}
          onClose={() => setActivePerson(null)}
        />
      )}

      {/* Send to a friend */}
      {sendPanelOpen && (
        <SendRecommendationPanel
          title={title}
          onClose={() => setSendPanelOpen(false)}
        />
      )}

      <div className="overflow-y-auto flex-1 scrollbar-thin pb-16 sm:pb-0">
        {/* Hero: cinematic backdrop (stored or fetched) or blurred-poster fallback */}
        {(title.backdropUrl || heroBackdropUrl) ? (
          <HeroBackdrop
            title={title}
            backdropOverride={heroBackdropUrl ?? undefined}
            onPosterClick={() => setPosterLightboxOpen(true)}
          >
            <div className="flex items-center gap-2">
              {title.type === 'movie' ? (
                <Film className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
              ) : (
                <Tv className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
              )}
              <span className="font-mono text-xs text-muted-foreground uppercase tracking-wider">
                {title.type === 'tv' ? 'Series' : 'Film'}
              </span>
              {title.network && (
                <span className="font-mono text-xs text-muted-foreground">· {title.network}</span>
              )}
            </div>
            {displayLogoUrl ? (
              <img
                src={displayLogoUrl}
                alt={title.title}
                className="object-contain object-left max-h-20 sm:max-h-28 max-w-[90%] drop-shadow-lg"
              />
            ) : (
              <CardTitle className="text-xl leading-tight">{title.title}</CardTitle>
            )}
            <div className="flex flex-wrap items-center gap-2">
                <MetaBadge className="h-7 inline-flex items-center whitespace-nowrap border-amber/20 text-amber/80">{title.year}</MetaBadge>
              {title.director && (
                <MetaBadge className="h-7 inline-flex items-center whitespace-nowrap border-amber/20 text-amber/80">{title.director}</MetaBadge>
              )}
              {title.runtime && title.type === 'movie' && (
                <MetaBadge className="h-7 inline-flex items-center gap-1 whitespace-nowrap border-amber/20 text-amber/80">
                  <Clock className="w-3 h-3 shrink-0" />
                  {fmtRuntime(title.runtime)}
                </MetaBadge>
              )}
              {title.contentRating && <MetaBadge className="h-7 inline-flex items-center whitespace-nowrap border-amber/20 text-amber/80">{title.contentRating}</MetaBadge>}
              {title.genres.slice(0, 2).map((genre) => (
                <MetaBadge key={genre} className="border-amber/20 text-amber/80">{genre}</MetaBadge>
              ))}
            </div>
            {isSpiderNoir && (
              <SpiderNoirModeSelector
                unlockedModes={unlockedModes}
                earnedModes={earnedModes}
                selected={manualMode}
                pinned={pinnedModeRaw}
                onSelect={handleModeSelect}
                onTogglePin={handleTogglePin}
              />
            )}
            {isMatrix && (title.viewings.length > 0 || title.status === 'watched') && (
              <MatrixPillSelector onRedPill={() => setShowMatrixRain(true)} />
            )}
            <div className="flex flex-wrap items-center gap-3">
              <div className="h-8 flex items-center rounded-md px-2.5 bg-black/55 backdrop-blur-sm border border-white/10">
                <StarRating
                  value={title.rating ?? 0}
                  size="sm"
                  onChange={isSharedView ? undefined : (rating) => updateTitle(title.id, { rating })}
                />
              </div>
              <HeroScores imdb={title.imdbRating} rt={title.rtScore} meta={title.metacriticScore} />
            </div>
          </HeroBackdrop>
        ) : (
          <div className="relative overflow-hidden shrink-0">
            {(title.backdropUrl ?? title.posterUrl) && (
              <div
                className="absolute inset-0"
                style={{
                  backgroundImage: `url(${title.backdropUrl ?? title.posterUrl})`,
                  backgroundSize: 'cover',
                  backgroundPosition: 'center top',
                  filter: 'blur(20px)',
                  transform: 'scale(1.3)',
                  opacity: 0.18,
                }}
              />
            )}
            <div className="absolute inset-0" style={{ background: 'linear-gradient(to bottom, hsl(var(--card) / 0.55) 0%, hsl(var(--card) / 0.92) 45%, hsl(var(--card)) 65%)' }} />
            <div className="relative z-10 flex gap-5 px-6 pt-10 pb-6">
              <div className="w-28 sm:w-36 shrink-0">
                {title.posterUrl ? (
                  <button
                    type="button"
                    onClick={() => setPosterLightboxOpen(true)}
                    aria-label={`View full poster for ${title.title}`}
                    className="block w-full rounded-lg overflow-hidden transition-opacity hover:opacity-90 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                    title="View full poster"
                  >
                    <DynamicPoster title={title} hideBadges />
                  </button>
                ) : (
                  <DynamicPoster title={title} hideBadges />
                )}
              </div>
              <div className="flex-1 min-w-0 space-y-2 pt-6">
                <div className="flex items-center gap-2">
                  {title.type === 'movie' ? (
                    <Film className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                  ) : (
                    <Tv className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                  )}
                  <span className="font-mono text-xs text-muted-foreground uppercase tracking-wider">
                    {title.type === 'tv' ? 'Series' : 'Film'}
                  </span>
                  {title.network && (
                    <span className="font-mono text-xs text-muted-foreground">· {title.network}</span>
                  )}
                </div>
                <CardTitle className="text-xl leading-tight">{title.title}</CardTitle>
                <div className="flex flex-wrap items-center gap-2">
                  <MetaBadge className="h-7 inline-flex items-center whitespace-nowrap border-amber/20 text-amber/80">{title.year}</MetaBadge>
                  {title.director && (
                    <MetaBadge className="h-7 inline-flex items-center whitespace-nowrap border-amber/20 text-amber/80">{title.director}</MetaBadge>
                  )}
                  {title.runtime && title.type === 'movie' && (
                    <MetaBadge className="h-7 inline-flex items-center gap-1 whitespace-nowrap border-amber/20 text-amber/80">
                      <Clock className="w-3 h-3 shrink-0" />
                      {fmtRuntime(title.runtime)}
                    </MetaBadge>
                  )}
                  {title.contentRating && <MetaBadge className="h-7 inline-flex items-center whitespace-nowrap border-amber/20 text-amber/80">{title.contentRating}</MetaBadge>}
                  {title.genres.slice(0, 2).map((genre) => (
                    <MetaBadge key={genre} className="border-amber/20 text-amber/80">{genre}</MetaBadge>
                  ))}
                </div>
                {isSpiderNoir && (
                  <SpiderNoirModeSelector
                    unlockedModes={unlockedModes}
                    earnedModes={earnedModes}
                    selected={manualMode}
                    pinned={pinnedModeRaw}
                    onSelect={handleModeSelect}
                    onTogglePin={handleTogglePin}
                  />
                )}
                {isMatrix && (title.viewings.length > 0 || title.status === 'watched') && (
                  <MatrixPillSelector onRedPill={() => setShowMatrixRain(true)} />
                )}
                <div className="flex flex-wrap items-center gap-3">
                  <div className="h-8 flex items-center rounded-md px-2.5 bg-black/55 backdrop-blur-sm border border-white/10">
                    <StarRating
                      value={title.rating ?? 0}
                      size="sm"
                      onChange={isSharedView ? undefined : (rating) => updateTitle(title.id, { rating })}
                    />
                  </div>
                  <HeroScores imdb={title.imdbRating} rt={title.rtScore} meta={title.metacriticScore} />
                </div>
              </div>
            </div>
          </div>
        )}

        {!isSharedView && title.type === 'movie' && <OutingBanner title={title} />}

        {/* Scrollable body */}
        <div className="px-4 sm:px-6 pb-6 grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_300px] lg:gap-x-8 lg:items-start">
          {/* Each column owns its vertical flow, so a tall sidebar never creates
              empty grid rows between primary-content sections. */}
          <main className="space-y-5 min-w-0">
            {/* Left column — status, synopsis, genres */}
            <div className="space-y-5 min-w-0 lg:col-start-1 lg:row-start-1">
              {/* Synopsis */}
              {(title.synopsis || title.genres.length > 0 || !isSharedView || title.tags.length > 0) && (
                <SectionCard title="Synopsis">
                  {title.synopsis && <BodyText className="text-sm leading-relaxed max-w-2xl">{title.synopsis}</BodyText>}
                  {(title.genres.length > 0 || !isSharedView || title.tags.length > 0) && (
                    <div className={cn('flex flex-wrap items-center gap-1.5', title.synopsis && 'mt-4 pt-4 border-t')} style={{ borderColor: 'var(--line)' }}>
                      {title.genres.length > 0 && (
                        <>
                          {title.genres.map((genre) => (
                            <MetaBadge key={genre} className="border-amber/20 text-amber/70">{genre}</MetaBadge>
                          ))}
                        </>
                      )}
                      {(!isSharedView || title.tags.length > 0) && (
                        <DrawerTagEditor
                          tags={title.tags}
                          isSharedView={isSharedView}
                          onChange={(tags) => updateTitle(title.id, { tags })}
                        />
                      )}
                    </div>
                  )}
                </SectionCard>
              )}
            </div>

          {/* Cast & Crew */}
          {(title.cast?.length || title.crew?.length || title.studios?.length) ? (
            <SectionCard className="lg:col-start-1">
              <CastCrewSection
                cast={title.cast}
                crew={title.crew}
                studios={title.studios}
                onPersonClick={setActivePerson}
                onStudioClick={browseByStudio}
              />
            </SectionCard>
          ) : null}

          {/* Franchise — the collection's other movies + watch progress */}
          {title.type === 'movie' && title.collectionId != null && (
            <SectionCard className="lg:col-start-1">
              <FranchiseSection
                collectionId={title.collectionId}
                collectionName={title.collectionName}
                currentTmdbId={title.tmdbId}
                isSharedView={isSharedView}
              />
            </SectionCard>
          )}

          {/* ── TV Series section ───────────────────────────────────── */}
          {title.type === 'tv' && title.seasons && title.seasons.length > 0 && (
            <SectionCard title="Season & Episodes" className="lg:col-start-1">
              <TVSeriesSection
                titleId={title.id}
                seasons={title.seasons}
                isSharedView={isSharedView}
                isSpiderNoir={title.tmdbId === SPIDER_NOIR_TMDB_ID}
                onPersonClick={setActivePerson}
                onColorModeSelected={handleModeSelect}
              />
            </SectionCard>
          )}

          {/* ── Movie section (and TV without seasons) ─────────────── */}
          {title.type === 'movie' && (
            <>
              {/* Viewing History */}
              <SectionCard
                title="Viewing History"
                className="lg:col-start-1"
                action={!showLogForm && !isSharedView ? (
                    <div className="flex items-center gap-3">
                      {(title.status === 'watchlist' || title.status === 'watching') && (
                        <button
                          onClick={() => openOutingSchedule(title.id)}
                          className="flex items-center gap-1 text-xs font-mono text-amber/70 hover:text-amber transition-colors"
                        >
                          <Ticket className="w-3.5 h-3.5" />
                          I've got tickets
                        </button>
                      )}
                      <button
                        onClick={() => setShowLogForm(true)}
                        className="flex items-center gap-1 text-xs font-mono text-amber/70 hover:text-amber transition-colors"
                      >
                        <Plus className="w-3.5 h-3.5" />
                        Log a viewing
                      </button>
                    </div>
                ) : undefined}
              >

                {showLogForm && (
                  <div className="border-t pt-3 mb-4 space-y-3" style={{ borderColor: 'var(--line)' }}>
                    <div>
                      <label htmlFor="viewing-date" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
                        <Calendar className="inline w-3 h-3 mr-1" />
                        Date Watched
                      </label>
                      {!logPrePlatform && (
                        <Input
                          id="viewing-date"
                          type="date"
                          value={logDate}
                          onChange={(e) => setLogDate(e.target.value)}
                          className="bg-secondary/50 border-border font-mono"
                        />
                      )}
                      <label htmlFor={`drawer-pre-platform-${title.id}`} className="flex items-center gap-2 cursor-pointer mt-2">
                        <input
                          id={`drawer-pre-platform-${title.id}`}
                          type="checkbox"
                          checked={logPrePlatform}
                          onChange={(e) => setLogPrePlatform(e.target.checked)}
                          className="accent-amber w-3.5 h-3.5"
                        />
                        <span className="font-sans text-xs text-muted-foreground">
                          Watched before joining CinemArchive (no date)
                        </span>
                      </label>
                    </div>
                    <div>
                      <p className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                        Rating
                      </p>
                      <StarRating value={logRating} onChange={setLogRating} size="md" />
                    </div>
                    <VenueCompanionsFields
                      venue={logVenue}
                      onVenueChange={setLogVenue}
                      companions={logCompanions}
                      onCompanionsChange={setLogCompanions}
                    />
                    <div>
                      <label htmlFor="viewing-notes" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
                        <FileText className="inline w-3 h-3 mr-1" />
                        Notes
                      </label>
                      <textarea
                        id="viewing-notes"
                        value={logNotes}
                        onChange={(e) => setLogNotes(e.target.value)}
                        placeholder="Your thoughts…"
                        rows={2}
                        className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-2 focus:ring-amber/30"
                      />
                    </div>
                    <div className="flex gap-2">
                      <Button
                        className="flex-1 bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
                        onClick={handleSaveViewing}
                        disabled={showMovieSaved}
                      >
                        {showMovieSaved ? (
                          <>
                            <Check className="w-4 h-4 mr-2" />
                            Saved
                          </>
                        ) : (
                          <>
                            <Star className="w-4 h-4 mr-2" />
                            Save Viewing
                          </>
                        )}
                      </Button>
                      <Button variant="outline" onClick={() => setShowLogForm(false)}>
                        Cancel
                      </Button>
                    </div>
                  </div>
                )}

                <ViewingTimeline
                  title={title}
                  isSharedView={isSharedView}
                  onDeleteViewing={(viewingId) => removeViewing(title.id, viewingId)}
                  onLogViewing={() => setShowLogForm(true)}
                />
              </SectionCard>
            </>
          )}

          {/* Trailers */}
          {videos.length > 0 && (
            <SectionCard className="lg:col-start-1">
              <TrailerRow videos={videos} />
            </SectionCard>
          )}

          {/* Comments & reactions — friends-only, hidden for anonymous share-link visitors */}
          {viewerContext.kind !== 'shared-link' && (
            <SectionCard className="lg:col-start-1">
              <TitleCommentsPanel titleId={title.id} />
            </SectionCard>
          )}

          {/* Maintenance actions */}
          {!isSharedView && (
            <div
              className="pt-2 border-t"
              style={{ borderColor: 'var(--line)' }}
            >
              {pendingDeleteTitle ? (
                <div className="flex items-center justify-between">
                  <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
                    Remove from library forever?
                  </span>
                  <div className="flex items-center gap-3">
                    <button
                      onClick={handleDelete}
                      className="font-mono text-xs transition-opacity hover:opacity-80"
                      style={{ color: 'var(--ember)' }}
                      aria-label="Delete forever: title from library"
                    >
                      Delete forever
                    </button>
                    <button
                      onClick={() => setPendingDeleteTitle(false)}
                      className="font-mono text-xs transition-opacity hover:opacity-80"
                      style={{ color: 'var(--paper-faint)' }}
                      aria-label="Cancel deleting title from library"
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              ) : (
                <div className="flex flex-wrap items-center gap-2">
                  <button
                    onClick={openRefreshMetadata}
                    className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-[var(--line)] text-muted-foreground hover:text-amber hover:border-amber/40 hover:bg-amber/5 transition-all focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
                  >
                    <RefreshCw className="w-3.5 h-3.5" />
                    Refresh poster &amp; metadata
                  </button>
                  {title.type === 'movie' && (title.status === 'watched' || title.status === 'dropped') && (
                    <button
                      onClick={() => openOutingSchedule(title.id)}
                      className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-[var(--line)] text-muted-foreground hover:text-amber hover:border-amber/40 hover:bg-amber/5 transition-all focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
                    >
                      <Ticket className="w-3.5 h-3.5" />
                      Plan a cinema trip
                    </button>
                  )}
                  {user && (
                    <button
                      onClick={() => setSendPanelOpen(true)}
                      className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-[var(--line)] text-muted-foreground hover:text-amber hover:border-amber/40 hover:bg-amber/5 transition-all focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
                    >
                      <Send className="w-3.5 h-3.5" />
                      Send to a friend
                    </button>
                  )}
                  <button
                    onClick={() => setPendingDeleteTitle(true)}
                    className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-[var(--line)] text-muted-foreground hover:text-ember hover:border-ember/30 hover:bg-ember/5 transition-all focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ember/60"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                    Remove from library
                  </button>
                </div>
              )}
            </div>
          )}
          </main>

          {/* Right column — supplementary metadata flows independently. */}
          <aside className="space-y-5 min-w-0">
            <StatusCard
              status={title.status}
              isSharedView={isSharedView}
              onChange={(status) => updateTitle(title.id, { status })}
            />
            {title.type === 'movie' && (
              <SectionCard title="Viewing Stats">
                <div className="space-y-2">
                  <div className="bg-secondary/40 rounded-lg px-3 py-3 min-w-0 flex items-center justify-between gap-3">
                    <div className="flex items-center gap-1.5 whitespace-nowrap">
                      <Eye className="w-3.5 h-3.5 text-muted-foreground" aria-hidden="true" />
                      <StatLabel>Viewings</StatLabel>
                    </div>
                    <span className="font-mono text-base leading-none font-medium text-amber tabular-nums shrink-0">
                      {title.viewings.length}
                    </span>
                  </div>
                  <div className="bg-secondary/40 rounded-lg px-3 py-3 min-w-0 flex items-center justify-between gap-3">
                    <div className="flex items-center gap-1.5 whitespace-nowrap">
                      <Calendar className="w-3.5 h-3.5 text-muted-foreground" aria-hidden="true" />
                      <StatLabel>Last Seen</StatLabel>
                    </div>
                    <span className="font-mono text-xs leading-none font-medium text-amber tabular-nums whitespace-nowrap shrink-0 text-right">
                      {(() => {
                        if (title.viewings.length === 0) return '—'
                        const latest = title.viewings.slice().sort((a, b) => viewingTime(b) - viewingTime(a))[0]
                        return latest.date ? fmtDate(latest.date) : 'Before joining'
                      })()}
                    </span>
                  </div>
                </div>
              </SectionCard>
            )}
            <SectionCard title="Details">
              <dl className="space-y-2 rounded-lg bg-secondary/30 p-3">
                {title.network && <DetailRow label="Network" value={title.network} />}
                {title.runtime ? <DetailRow label="Runtime" value={`${title.runtime} min`} /> : null}
                {title.contentRating && <DetailRow label="Rated" value={title.contentRating} />}
                {title.originalLanguage && <DetailRow label="Language" value={languageName(title.originalLanguage)} />}
                {title.releaseDate && (
                  <DetailRow
                    label={title.releaseDate > new Date().toISOString().slice(0, 10) ? 'Releases' : 'Released'}
                    value={fmtReleaseDate(title.releaseDate)}
                  />
                )}
                {title.studios && title.studios.length > 0 && (
                  <DetailRow label="Studio" value={title.studios.join(', ')} />
                )}
                {title.collectionName && (
                  <DetailRow label="Franchise" value={title.collectionName.replace(/\s+Collection$/i, '')} />
                )}
                <DetailRow label="Added" value={fmtDate(title.addedAt)} />
              </dl>
            </SectionCard>

            {title.imdbId && (
              <SectionCard title="Critical Reception">
                <ReviewBadges
                  imdb={title.imdbRating}
                  rt={title.rtScore}
                  meta={title.metacriticScore}
                  awardsCount={title.awardsCount}
                  bechdelOutcome={title.bechdelOutcome}
                  bechdelScore={title.bechdelScore}
                  showScores={false}
                />
              </SectionCard>
            )}

            <SectionCard>
              <ExternalLinks media={title} />
            </SectionCard>

            <SectionCard>
              <WatchProvidersSection
                providers={watchProviders}
                customUrl={title.customWatchUrl}
                inHomeCollection={title.inHomeCollection}
                physicalMedia={title.physicalMedia}
                isSharedView={isSharedView}
                onSaveCustomUrl={(url) => updateTitle(title.id, { customWatchUrl: url })}
                onToggleHomeCollection={(value) => updateTitle(title.id, { inHomeCollection: value })}
                onChangePhysicalMedia={(items) => updateTitle(title.id, { physicalMedia: items })}
              />
            </SectionCard>
          </aside>
        </div>
      </div>
    </CinemaModal>
    </>
  )
}
