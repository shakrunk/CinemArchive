import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { Search, Compass, X, Film, Tv, Check, Plus, Info, User, Building2, ChevronLeft, ChevronRight, ChevronDown, SlidersHorizontal, type LucideIcon } from 'lucide-react'
import { useShallow } from 'zustand/react/shallow'
import { useAppStore } from 'src/store/useAppStore'
import {
  searchMedia, fetchTrending, fetchDiscover, fetchMediaDetails, fetchTitleImages,
  searchPersons, fetchPersonCredits, searchCompanies, fetchCompanyTitles,
  MOVIE_GENRES, TV_GENRES,
  type SearchResult, type PersonResult, type CompanyResult,
} from 'src/lib/media'
import type { MediaType } from 'src/store/mockData'
import { cn, fmtRuntime, staggerDelays } from 'src/lib/utils'
import { usePrefersReducedMotionRef } from 'src/lib/motion'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { ReviewBadges, ExternalLinks } from 'src/components/ui/media-badges'
import { Chip } from 'src/components/ui/chip'
import { useClickOutside } from 'src/lib/useClickOutside'

// ─── Types ────────────────────────────────────────────────────────────────────

type FilterType = 'all' | MediaType
type SearchMode = 'titles' | 'people' | 'studios'

const SEARCH_PLACEHOLDERS: Record<SearchMode, string> = {
  titles: 'Search movies & TV shows…',
  people: 'Search by actor, director, crew…',
  studios: 'Search by studio or company…',
}

// ─── DiscoverCard ─────────────────────────────────────────────────────────────

interface DiscoverCardProps {
  result: SearchResult
  isOwned: boolean
  style?: React.CSSProperties
  className?: string
  isSharedView: boolean
  onAdd: (result: SearchResult) => void
  onSelect: (result: SearchResult) => void
}

function DiscoverCard({ result, isOwned, isSharedView, onAdd, onSelect, style, className }: DiscoverCardProps) {
  const [imgError, setImgError] = useState(false)
  const pushNotification = useAppStore((s) => s.pushNotification)

  return (
    <div
      className={cn('discover-card film-frame group relative cursor-pointer', className)}
      style={style}
      onClick={() => onSelect(result)}
      role="button"
      tabIndex={0}
      aria-label={`View details for ${result.title}`}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(result) } }}
    >
      <div className="relative aspect-[2/3] overflow-hidden film-frame__window transition-transform duration-200 group-hover:scale-[1.015]">
        {result.posterUrl && !imgError ? (
          <img
            src={result.posterUrl}
            alt={result.title}
            loading="lazy"
            onError={() => setImgError(true)}
            className="w-full h-full object-cover rounded-[1px]"
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center" style={{ background: 'var(--inset)' }}>
            {result.type === 'tv' ? (
              <Tv className="w-8 h-8 text-paper-faint opacity-30" />
            ) : (
              <Film className="w-8 h-8 text-paper-faint opacity-30" />
            )}
          </div>
        )}

        {/* Base gradient + frame label — always visible, like a printed frame caption.
            Fixed dark tones (not theme-swapping void/paper), so the scrim stays legible
            over poster art in light mode too — mirrors .poster.has-img in index.css. */}
        <div className="absolute inset-0 bg-gradient-to-t from-[#080503] via-[#080503]/55 to-transparent" />
        {/* Stronger scrim on hover, for contrast under the action row */}
        <div className="absolute inset-0 bg-gradient-to-t from-[#080503] via-[#080503]/85 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-200" />
        <div className="absolute inset-0 flex flex-col justify-end p-2.5">
          <p className="font-serif text-[13px] font-semibold text-[rgb(var(--ivory))] leading-snug line-clamp-2 mb-0.5">
            {result.title}
          </p>
          <p className="font-mono text-[10px] text-white/60">
            {result.year > 0 ? result.year : ''}
            {result.type === 'tv' && result.seasonCount ? ` · ${result.seasonCount}S` : ''}
          </p>
          {result.genres.length > 0 && (
            <p className="font-mono text-[9px] text-amber/70 truncate mt-1">
              {result.genres.slice(0, 2).join(' · ')}
            </p>
          )}

          {/* Actions — reveal on hover only. overflow-hidden clips the row's content
              while collapsed (grid-rows-[0fr]); switched to visible once hovered so the
              details tooltip — which pops up *above* the row via `bottom-full` — isn't
              clipped by this same box once expanded. */}
          <div className="grid grid-rows-[0fr] group-hover:grid-rows-[1fr] transition-[grid-template-rows] duration-200">
            <div className="overflow-hidden group-hover:overflow-visible">
              {isOwned ? (
                <div className="flex items-center gap-1 text-amber text-[10px] font-mono pt-2">
                  <Check className="w-3 h-3" />
                  In your library
                </div>
              ) : !isSharedView ? (
                <div className="flex items-center gap-0 group-hover:gap-1.5 transition-all duration-300 delay-[1500ms] pt-2">
                  <button
                    onClick={(e) => { e.stopPropagation(); onAdd(result) }}
                    aria-label={`Add ${result.title} to library`}
                    className="flex items-center justify-center gap-1 flex-1 py-1.5 rounded text-[11px] font-bold transition-colors btn-amber"
                  >
                    <Plus className="w-3 h-3" />
                    Add to Library
                  </button>

                  {/* Details hint — expands after a long hover, with tooltip */}
                  <div
                    className="relative group/detail shrink-0 w-0 group-hover:w-[30px] opacity-0 group-hover:opacity-100 transition-all duration-300 delay-[1500ms]"
                  >
                    {/* Tooltip — always visible once the wrapper fades in */}
                    <div className="absolute bottom-full right-0 mb-1.5 pointer-events-none z-10">
                      <div
                        className="relative px-2 py-1 rounded shadow-lg"
                        style={{ background: '#0d0906', border: '1px solid rgba(233,178,102,0.35)' }}
                      >
                        <p className="font-mono text-[9px] text-white/60 whitespace-nowrap">Click for more details</p>
                        <div
                          className="absolute top-full right-2.5"
                          style={{
                            width: 0, height: 0,
                            borderLeft: '4px solid transparent',
                            borderRight: '4px solid transparent',
                            borderTop: '4px solid rgba(233,178,102,0.35)',
                          }}
                        />
                      </div>
                    </div>

                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        onSelect(result)
                        pushNotification({
                          message: 'Next time, click on the poster itself to see more details.',
                          kind: 'tip',
                          autoClose: 5000,
                        })
                      }}
                      aria-label={`See details for ${result.title}`}
                      className="w-full h-[30px] rounded flex items-center justify-center btn-amber"
                    >
                      <Info className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              ) : null}
            </div>
          </div>
        </div>

        {/* Always-visible "in library" badge */}
        {isOwned && (
          <div
            className="absolute top-1.5 right-1.5 w-5 h-5 rounded-full flex items-center justify-center shadow-md"
            style={{ background: 'var(--amber)' }}
            title="Already in your library"
          >
            <Check className="w-[11px] h-[11px] text-[color:var(--on-amber)]" strokeWidth={3} />
          </div>
        )}
      </div>

      {/* Format + quick-add — the frame's edge-print; title already lives on the poster above */}
      <div className="film-frame__caption px-1.5 py-1.5 flex items-center justify-between gap-1">
        <p className="font-mono text-[10px] text-white/60 truncate">
          {result.type === 'tv' ? 'TV' : 'Movie'}
          {result.year > 0 ? ` · ${result.year}` : ''}
        </p>
        {isOwned ? (
          <Check className="w-3 h-3 text-amber shrink-0" />
        ) : !isSharedView ? (
          <button
            onClick={(e) => { e.stopPropagation(); onAdd(result) }}
            aria-label={`Add ${result.title} to library`}
            className="shrink-0 w-6 h-6 rounded-full flex items-center justify-center transition-colors btn-amber sm:hidden"
          >
            <Plus className="w-3.5 h-3.5" />
          </button>
        ) : null}
      </div>
    </div>
  )
}

// ─── DiscoverCarousel — a continuously-scrolling film strip, drag-to-scrub ────
// The track is duplicated once and driven by transform (not native scroll), so it can
// loop seamlessly: past the end of the first copy, the visible position wraps back to
// the same spot in the second copy. Speed is applied via requestAnimationFrame directly
// to the transform + the --reel-scroll-x custom property (which the sprocket-hole masks
// read), never through React state, so scrolling never triggers a re-render.

const CAROUSEL_SPEED_PX_S = 26

// Sprocket-hole pitch from the .reel-strip mask in index.css (repeating-linear-gradient
// period, 8px hole + 30.4px stock). Mirror any change to that value here.
const SPROCKET_PITCH_PX = 38.4

interface DiscoverCarouselProps {
  results: SearchResult[]
  libraryTmdbIds: Set<number>
  isSharedView: boolean
  onAdd: (result: SearchResult) => void
  onSelect: (result: SearchResult) => void
  delays: number[]
}

function DiscoverCarousel({ results, libraryTmdbIds, isSharedView, onAdd, onSelect, delays }: DiscoverCarouselProps) {
  const wrapperRef = useRef<HTMLDivElement>(null)
  const trackRef = useRef<HTMLDivElement>(null)
  const markerRef = useRef<HTMLDivElement>(null)
  const scrollXRef = useRef(0)
  const singleSetWidthRef = useRef(0)
  const draggingRef = useRef(false)
  const dragMovedRef = useRef(false)
  const dragStartXRef = useRef(0)
  const dragStartScrollRef = useRef(0)
  const pausedRef = useRef(false)
  const reducedMotionRef = usePrefersReducedMotionRef()
  const [isGrabbing, setIsGrabbing] = useState(false)

  const applyTransform = useCallback(() => {
    const width = singleSetWidthRef.current
    if (!trackRef.current || width <= 0) return
    const normalized = ((scrollXRef.current % width) + width) % width
    // Snapped to a whole *device* pixel (not CSS pixel) before it hits the DOM
    // (scrollXRef itself stays full float precision for smooth accumulation). A
    // fractional translateX on the track lets the compositor round each frame's edge
    // independently, so adjacent frames' borders can land a device pixel apart — a
    // hairline seam that flickers open onto whatever's behind on every other frame,
    // worst in Firefox. Snapping to whole CSS pixels prevents that but throttles
    // motion to 1px steps even on Retina displays, where each CSS px is 2-3 device
    // px — at this carousel's ~26px/s speed that reads as visible stutter. Snapping
    // to the device pixel grid instead keeps the same anti-seam guarantee at native
    // resolution.
    const dpr = window.devicePixelRatio || 1
    trackRef.current.style.transform = `translateX(${-Math.round(normalized * dpr) / dpr}px)`
    // Drives the sprocket-hole mask-position so the top/bottom reel strips glide past
    // in sync with the posters, like film actually threading through a projector gate.
    // Wrapped on a modulus of the hole pitch — NOT the poster-loop width above — so the
    // hole pattern never jumps out of phase with itself at the loop seam (the loop width
    // is almost never an exact multiple of the pitch).
    const maskWrap = SPROCKET_PITCH_PX * 1000
    const maskShift = ((scrollXRef.current % maskWrap) + maskWrap) % maskWrap
    wrapperRef.current?.style.setProperty('--reel-scroll-x', `${-maskShift}px`)
  }, [])

  // Measure the width of one (non-duplicated) copy of the results via a zero-width
  // marker placed between the two copies — a translateX on the track shifts marker
  // and track by the same amount, so their gap stays a stable read of the set width.
  useEffect(() => {
    function measure() {
      const track = trackRef.current
      const marker = markerRef.current
      if (!track || !marker) return
      singleSetWidthRef.current = marker.getBoundingClientRect().left - track.getBoundingClientRect().left
      applyTransform()
    }
    measure()
    const ro = new ResizeObserver(measure)
    if (trackRef.current) ro.observe(trackRef.current)
    return () => ro.disconnect()
  }, [results, applyTransform])

  useEffect(() => {
    if (results.length === 0) return
    let raf = 0
    let lastTs: number | null = null
    function tick(ts: number) {
      if (lastTs == null) lastTs = ts
      const dt = (ts - lastTs) / 1000
      lastTs = ts
      if (!draggingRef.current && !pausedRef.current && !reducedMotionRef.current) {
        scrollXRef.current += CAROUSEL_SPEED_PX_S * dt
        applyTransform()
      }
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [results.length, applyTransform, reducedMotionRef])

  function onPointerDown(e: React.PointerEvent) {
    draggingRef.current = true
    dragMovedRef.current = false
    dragStartXRef.current = e.clientX
    dragStartScrollRef.current = scrollXRef.current
    setIsGrabbing(true)
  }

  function onPointerMove(e: React.PointerEvent) {
    if (!draggingRef.current) return
    const delta = e.clientX - dragStartXRef.current
    // Pointer capture is deferred until movement crosses the drag threshold — grabbing
    // it eagerly on pointerdown retargets the *click* compatibility event (per the
    // Pointer Events spec) to this wrapper for every plain click too, so descendant
    // onClick handlers (poster, Info button) never see it.
    if (!dragMovedRef.current && Math.abs(delta) > 5) {
      dragMovedRef.current = true
      e.currentTarget.setPointerCapture(e.pointerId)
    }
    scrollXRef.current = dragStartScrollRef.current - delta
    applyTransform()
  }

  function endDrag(e: React.PointerEvent) {
    draggingRef.current = false
    setIsGrabbing(false)
    if (e.currentTarget.hasPointerCapture(e.pointerId)) {
      e.currentTarget.releasePointerCapture(e.pointerId)
    }
  }

  // Pause on pointer hover (mouse) and on keyboard focus (Tab-ing through cards),
  // so the strip doesn't slide away from underneath a focused or hovered card.
  function pause() { pausedRef.current = true }
  function resume() { pausedRef.current = false }

  // Chevron override — a manual nudge layered on top of the auto-scroll/drag, for
  // anyone who'd rather page through than wait for the marquee or grab-drag it.
  // The auto-scroll keeps drifting from wherever this lands, same as after a drag.
  function scrollByPage(dir: 1 | -1) {
    const amount = (trackRef.current?.parentElement?.clientWidth ?? 300) * 0.85 * dir
    scrollXRef.current += amount
    const track = trackRef.current
    if (track) track.style.transition = 'transform 0.4s var(--ease, ease)'
    applyTransform()
    window.setTimeout(() => { if (track) track.style.transition = '' }, 400)
  }

  const handleSelect = useCallback((result: SearchResult) => {
    if (dragMovedRef.current) { dragMovedRef.current = false; return }
    onSelect(result)
  }, [onSelect])

  const loopedResults = results.length > 0 ? [...results, ...results] : []

  return (
    <div ref={wrapperRef} className="group/carousel -mx-4 sm:-mx-8 px-4 sm:px-8">
      <div
        className="film-strip"
        style={{ cursor: isGrabbing ? 'grabbing' : 'grab', touchAction: 'pan-y', userSelect: 'none' }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onMouseEnter={pause}
        onMouseLeave={resume}
        onFocusCapture={pause}
        onBlurCapture={resume}
        onDragStart={(e) => e.preventDefault()}
      >
        {/* Overlaid on the track's own top/bottom edge (not a separate band above/below
            it), so the punched sprocket holes are real cutouts down to .film-strip's
            black background — see the .reel-strip comment in index.css. */}
        <div className="reel-strip reel-strip--top" />
        <div className="reel-strip reel-strip--bottom" />

        <div className="relative">
          <div ref={trackRef} className="discover-grid flex" style={{ willChange: 'transform' }}>
            {results.map((result, i) => (
              <DiscoverCard
                key={`${result.type}-${result.tmdbId}-a-${i}`}
                result={result}
                isOwned={result.tmdbId != null && libraryTmdbIds.has(result.tmdbId)}
                isSharedView={isSharedView}
                onAdd={onAdd}
                onSelect={handleSelect}
                style={{ ['--poster-delay' as string]: `${delays[i] ?? 0}ms` }}
                className="shrink-0 w-[38vw] sm:w-[170px] md:w-[185px]"
              />
            ))}
            <div ref={markerRef} aria-hidden style={{ width: 0, flex: '0 0 0' }} />
            {loopedResults.slice(results.length).map((result, i) => (
              <DiscoverCard
                key={`${result.type}-${result.tmdbId}-b-${i}`}
                result={result}
                isOwned={result.tmdbId != null && libraryTmdbIds.has(result.tmdbId)}
                isSharedView={isSharedView}
                onAdd={onAdd}
                onSelect={handleSelect}
                style={{ ['--poster-delay' as string]: `${delays[i] ?? 0}ms` }}
                className="shrink-0 w-[38vw] sm:w-[170px] md:w-[185px]"
              />
            ))}
          </div>

          {/* Reel vignette — a constant soft edge, not a "more content" affordance,
              since the strip loops endlessly and there's no true edge to signal. */}
          <div className="reel-scrim reel-scrim--left is-visible" />
          <div className="reel-scrim reel-scrim--right is-visible" />

          {/* Manual override — visible on hover, since the strip is already paused then */}
          <button
            onClick={() => scrollByPage(-1)}
            aria-label="Scroll left"
            className="hidden sm:flex absolute left-2 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full items-center justify-center opacity-0 group-hover/carousel:opacity-100 transition-opacity shadow-lg z-10"
            style={{ background: 'rgb(var(--void-rgb) / 0.85)', border: '1px solid var(--line)' }}
          >
            <ChevronLeft className="w-4 h-4 text-paper" />
          </button>
          <button
            onClick={() => scrollByPage(1)}
            aria-label="Scroll right"
            className="hidden sm:flex absolute right-2 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full items-center justify-center opacity-0 group-hover/carousel:opacity-100 transition-opacity shadow-lg z-10"
            style={{ background: 'rgb(var(--void-rgb) / 0.85)', border: '1px solid var(--line)' }}
          >
            <ChevronRight className="w-4 h-4 text-paper" />
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── TasteDropdown — themed picker for the "Because you watched" / "More starring" rows ──

interface TasteDropdownOption {
  id: string
  label: string
}

interface TasteDropdownProps {
  options: TasteDropdownOption[]
  value: string | null
  onChange: (id: string) => void
  ariaLabel: string
  placeholder?: string
}

function TasteDropdown({ options, value, onChange, ariaLabel, placeholder = 'Select…' }: TasteDropdownProps) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useClickOutside(ref, () => setOpen(false), open)

  const selected = options.find((o) => o.id === value)

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label={ariaLabel}
        aria-expanded={open}
        disabled={options.length === 0}
        className="flex items-center gap-1.5 font-mono text-xs rounded-md border px-2.5 py-1.5 text-amber-bright hover:border-amber/40 transition-colors max-w-[220px] disabled:opacity-40 disabled:cursor-not-allowed"
        style={{ background: 'var(--inset)', borderColor: open ? 'rgba(233,178,102,0.4)' : 'var(--line)' }}
      >
        <span className="truncate">{selected?.label ?? placeholder}</span>
        <ChevronDown className={cn('w-3.5 h-3.5 shrink-0 transition-transform', open && 'rotate-180')} />
      </button>

      {open && (
        <div
          role="listbox"
          aria-label={ariaLabel}
          className="absolute left-0 top-[calc(100%+6px)] w-56 max-h-64 overflow-y-auto rounded-lg border shadow-2xl z-30 py-1 bg-card scrollbar-thin"
          style={{ borderColor: 'var(--line)' }}
        >
          {options.map((o) => (
            <button
              key={o.id}
              type="button"
              role="option"
              aria-selected={o.id === value}
              onClick={() => { onChange(o.id); setOpen(false) }}
              className={cn(
                'w-full text-left px-3 py-1.5 text-[13px] font-sans truncate transition-colors',
                o.id === value
                  ? 'text-amber-bright bg-amber/15'
                  : 'text-paper-faint hover:text-paper hover:bg-[color:var(--inset-strong)]'
              )}
            >
              {o.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

// ─── Empty state — no-results / prompt states across all three search modes ───

function DiscoverEmptyState({
  icon: Icon,
  message,
  onClearSearch,
  dim = false,
}: {
  icon: LucideIcon
  message: string
  onClearSearch?: () => void
  dim?: boolean
}) {
  return (
    <div className="py-16 text-center flex flex-col items-center">
      <Icon className={cn('w-10 h-10 mx-auto mb-3 text-paper-faint', dim ? 'opacity-20' : 'opacity-30')} />
      <p className={cn('font-mono text-sm text-paper-faint', onClearSearch && 'mb-4')}>{message}</p>
      {onClearSearch && (
        <button
          onClick={onClearSearch}
          className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber mx-auto"
        >
          <X className="w-3.5 h-3.5" />
          Clear search
        </button>
      )}
    </div>
  )
}

// ─── Loading skeleton ─────────────────────────────────────────────────────────

function DiscoverSkeleton() {
  return (
    <div className="-mx-4 sm:-mx-8 px-4 sm:px-8" aria-hidden>
      <div className="film-strip">
        <div className="reel-strip reel-strip--top" />
        <div className="reel-strip reel-strip--bottom" />
        <div className="discover-grid flex">
          {Array.from({ length: 10 }, (_, i) => (
            <div key={i} className="film-frame shrink-0 w-[38vw] sm:w-[170px] md:w-[185px]">
              <div className="film-frame__window aspect-[2/3]">
                <div className="w-full h-full rounded-[1px] animate-pulse" style={{ background: 'var(--inset)' }} />
              </div>
              <div className="film-frame__caption px-1.5 py-1.5">
                <div className="h-2.5 w-2/3 rounded animate-pulse" style={{ background: 'var(--inset)' }} />
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

// ─── PersonPicker ─────────────────────────────────────────────────────────────

interface PersonPickerProps {
  persons: PersonResult[]
  onSelect: (person: PersonResult) => void
}

function PersonPicker({ persons, onSelect }: PersonPickerProps) {
  return (
    <div className="max-w-lg mx-auto space-y-2">
      {persons.map((p) => (
        <button
          key={p.id}
          onClick={() => onSelect(p)}
          className="w-full flex items-center gap-3 p-3 rounded-lg border text-left transition-colors hover:border-amber/30"
          style={{ background: 'var(--inset)', borderColor: 'var(--line)' }}
        >
          <div
            className="w-10 h-10 rounded-full overflow-hidden shrink-0 border flex items-center justify-center"
            style={{ borderColor: 'var(--line)', background: 'var(--void)' }}
          >
            {p.profileUrl ? (
              <img src={p.profileUrl} alt={p.name} className="w-full h-full object-cover" />
            ) : (
              <User className="w-4 h-4 text-paper-faint opacity-40" />
            )}
          </div>
          <div className="min-w-0">
            <p className="font-serif text-sm text-paper">{p.name}</p>
            {p.department && (
              <p className="font-mono text-[10px] text-amber/60 mt-0.5">{p.department}</p>
            )}
            {p.knownFor && (
              <p className="font-mono text-[10px] text-paper-faint truncate mt-0.5">{p.knownFor}</p>
            )}
          </div>
        </button>
      ))}
    </div>
  )
}

// ─── CompanyPicker ────────────────────────────────────────────────────────────

interface CompanyPickerProps {
  companies: CompanyResult[]
  onSelect: (company: CompanyResult) => void
}

function CompanyPicker({ companies, onSelect }: CompanyPickerProps) {
  return (
    <div className="max-w-lg mx-auto space-y-2">
      {companies.map((c) => (
        <button
          key={c.id}
          onClick={() => onSelect(c)}
          className="w-full flex items-center gap-3 p-3 rounded-lg border text-left transition-colors hover:border-amber/30"
          style={{ background: 'var(--inset)', borderColor: 'var(--line)' }}
        >
          <div
            className="w-10 h-10 rounded-lg overflow-hidden shrink-0 border flex items-center justify-center"
            style={{ borderColor: 'var(--line)', background: 'var(--void)' }}
          >
            {c.logoUrl ? (
              <img src={c.logoUrl} alt={c.name} className="w-full h-full object-contain p-1" style={{ filter: 'invert(1)' }} />
            ) : (
              <Building2 className="w-4 h-4 text-paper-faint opacity-40" />
            )}
          </div>
          <div className="min-w-0">
            <p className="font-serif text-sm text-paper">{c.name}</p>
            {c.originCountry && (
              <p className="font-mono text-[10px] text-paper-faint mt-0.5">{c.originCountry}</p>
            )}
          </div>
        </button>
      ))}
    </div>
  )
}

// ─── Detail modal ─────────────────────────────────────────────────────────────

interface DiscoverDetailModalProps {
  result: SearchResult | null
  isOwned: boolean
  isSharedView: boolean
  onClose: () => void
  onAdd: (result: SearchResult) => void
}

function DiscoverDetailModal({ result, isOwned, isSharedView, onClose, onAdd }: DiscoverDetailModalProps) {
  const [details, setDetails] = useState<SearchResult | null>(null)
  const [logoUrl, setLogoUrl] = useState<string | null>(null)
  const [loadedTmdbId, setLoadedTmdbId] = useState<number | null | undefined>(undefined)

  // Reset stale details/logo the moment the result identity changes, rather than in an
  // effect — this is React's documented "adjusting state when a prop changes" pattern.
  if ((result?.tmdbId ?? null) !== loadedTmdbId) {
    setLoadedTmdbId(result?.tmdbId ?? null)
    setDetails(null)
    setLogoUrl(null)
  }

  // Derived rather than tracked separately: we're hydrating exactly while a result is open
  // and its details haven't landed yet (details is reset to null above on every identity change).
  const hydrating = !!result && details === null

  useEffect(() => {
    if (!result) return
    fetchMediaDetails(result)
      .then(({ result: r }) => setDetails(r))
      .catch(() => setDetails(result))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [result?.tmdbId])

  useEffect(() => {
    if (!result?.tmdbId) return
    let cancelled = false
    fetchTitleImages(result.tmdbId, result.type).then(({ logoUrl: logo }) => {
      if (!cancelled) setLogoUrl(logo)
    })
    return () => { cancelled = true }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [result?.tmdbId])

  const data = details ?? result
  if (!data) return null

  function handleAdd() {
    onClose()
    onAdd(data!)
  }

  const hasScores = data.imdbId != null
  const hasBackdrop = !!data.backdropUrl

  return (
    <CinemaModal
      open={!!result}
      onClose={onClose}
      title={data.title}
      description={data.synopsis}
      maxWidth="sm:max-w-2xl"
    >
      <div className="overflow-y-auto max-h-[90vh]">
        {/* Backdrop */}
        {hasBackdrop ? (
          <div className="relative aspect-[16/8] overflow-hidden shrink-0">
            <img
              src={data.backdropUrl}
              alt=""
              aria-hidden
              className="absolute inset-0 w-full h-full object-cover object-center"
              style={{
                maskImage: 'linear-gradient(to bottom, #000 0%, #000 63%, transparent 100%)',
                WebkitMaskImage: 'linear-gradient(to bottom, #000 0%, #000 63%, transparent 100%)',
              }}
            />
            <div
              className="absolute inset-0"
              style={{
                background: 'linear-gradient(to bottom, hsl(var(--card) / 0.05) 0%, hsl(var(--card) / 0.3) 45%, hsl(var(--card) / 0.75) 68%, hsl(var(--card)) 88%)',
              }}
            />
          </div>
        ) : (
          <div className="h-14" />
        )}

        {/* Content */}
        <div className={cn('px-5 pb-6', hasBackdrop ? '-mt-20 relative z-10' : 'pt-2')}>
          {/* Poster + title */}
          <div className="flex gap-4 items-end mb-4">
            <div
              className="w-24 shrink-0 rounded-lg overflow-hidden shadow-xl border"
              style={{ borderColor: 'var(--line)' }}
            >
              {data.posterUrl ? (
                <img src={data.posterUrl} alt={data.title} className="w-full aspect-[2/3] object-cover" />
              ) : (
                <div className="aspect-[2/3] flex items-center justify-center" style={{ background: 'var(--inset)' }}>
                  {data.type === 'tv'
                    ? <Tv className="w-6 h-6 text-paper-faint opacity-30" />
                    : <Film className="w-6 h-6 text-paper-faint opacity-30" />}
                </div>
              )}
            </div>

            <div className="flex-1 min-w-0 pb-1">
              {logoUrl ? (
                <img
                  src={logoUrl}
                  alt={data.title}
                  className="object-contain object-left max-h-20 max-w-[90%] drop-shadow-lg mb-1.5"
                />
              ) : (
                <h2 className="font-serif text-xl font-semibold text-paper leading-tight mb-1.5">
                  {data.title}
                </h2>
              )}
              <div className="flex flex-wrap items-center gap-1.5 mb-1">
                <span className="font-mono text-xs text-paper-faint">
                  {data.year > 0 ? data.year : ''}
                  {data.runtime ? ` · ${fmtRuntime(data.runtime)}` : ''}
                  {data.type === 'tv' && data.seasonCount
                    ? ` · ${data.seasonCount} season${data.seasonCount !== 1 ? 's' : ''}`
                    : ''}
                </span>
                {data.contentRating && (
                  <span
                    className="font-mono text-[9px] px-1.5 py-0.5 rounded border text-paper-faint"
                    style={{ borderColor: 'var(--line)' }}
                  >
                    {data.contentRating}
                  </span>
                )}
                <span
                  className="font-mono text-[9px] px-1.5 py-0.5 rounded"
                  style={{ background: 'var(--inset)', color: 'var(--paper-faint)' }}
                >
                  {data.type === 'tv' ? 'TV' : 'Movie'}
                </span>
              </div>
              {data.director && (
                <p className="font-mono text-[11px] text-paper-faint">
                  {data.type === 'tv' ? 'Created by ' : 'Dir. '}{data.director}
                </p>
              )}
              {data.network && (
                <p className="font-mono text-[11px] text-amber/70 mt-0.5">{data.network}</p>
              )}
            </div>
          </div>

          {/* Genres */}
          {data.genres.length > 0 && (
            <div className="flex flex-wrap gap-1.5 mb-4">
              {data.genres.map((g) => (
                <span
                  key={g}
                  className="font-mono text-[10px] px-2 py-0.5 rounded-full border text-paper-faint"
                  style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
                >
                  {g}
                </span>
              ))}
            </div>
          )}

          {/* Synopsis */}
          {hydrating && !data.synopsis ? (
            <div className="space-y-1.5 mb-4">
              <div className="h-3 rounded animate-pulse w-full" style={{ background: 'var(--inset)' }} />
              <div className="h-3 rounded animate-pulse w-5/6" style={{ background: 'var(--inset)' }} />
              <div className="h-3 rounded animate-pulse w-4/6" style={{ background: 'var(--inset)' }} />
            </div>
          ) : data.synopsis ? (
            <p className="font-sans text-sm text-paper/75 leading-relaxed mb-4">{data.synopsis}</p>
          ) : null}

          {/* Scores */}
          {hydrating && !hasScores ? (
            <div className="flex gap-2 mb-4">
              {[1, 2, 3].map((i) => (
                <div key={i} className="h-8 w-20 rounded animate-pulse" style={{ background: 'var(--inset)' }} />
              ))}
            </div>
          ) : hasScores ? (
            <div className="mb-4">
              <ReviewBadges
                imdb={data.imdbRating}
                rt={data.rtScore}
                meta={data.metacriticScore}
                awardsCount={data.awardsCount}
                bechdelOutcome={data.bechdelOutcome}
                bechdelScore={data.bechdelScore}
              />
            </div>
          ) : null}

          {/* Cast */}
          {hydrating && !data.cast ? (
            <div className="mb-5">
              <h3 className="font-mono text-[10px] uppercase tracking-[0.1em] text-paper-faint mb-2">Cast</h3>
              <div className="flex gap-2.5 overflow-x-auto pb-1 -mx-5 px-5">
                {[1, 2, 3, 4, 5].map((i) => (
                  <div
                    key={i}
                    className="shrink-0 w-[88px] rounded-lg overflow-hidden animate-pulse"
                    style={{ border: '1px solid var(--line)' }}
                  >
                    <div
                      className="w-full flex items-center justify-center"
                      style={{ background: 'hsl(var(--card))', aspectRatio: '2/3' }}
                    >
                      <User className="w-10 h-10" style={{ color: 'var(--line)' }} />
                    </div>
                    <div className="p-1.5 space-y-1.5" style={{ background: 'var(--inset)' }}>
                      <div className="h-2.5 rounded" style={{ background: 'var(--line)', width: '80%' }} />
                      <div className="h-2 rounded" style={{ background: 'var(--line)', width: '55%' }} />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : data.cast && data.cast.length > 0 ? (
            <div className="mb-5">
              <h3 className="font-mono text-[10px] uppercase tracking-[0.1em] text-paper-faint mb-2">Cast</h3>
              <div className="flex gap-2.5 overflow-x-auto pb-1 scrollbar-none -mx-5 px-5">
                {data.cast.slice(0, 10).map((c) => (
                  <div
                    key={c.tmdbPersonId}
                    className="shrink-0 w-[88px] rounded-lg overflow-hidden"
                    style={{ border: '1px solid var(--line)', background: 'var(--inset)' }}
                  >
                    <div className="overflow-hidden" style={{ aspectRatio: '2/3' }}>
                      {c.profileUrl ? (
                        <img src={c.profileUrl} alt={c.name} className="w-full h-full object-cover" />
                      ) : (
                        <div className="w-full h-full flex items-center justify-center" style={{ background: 'hsl(var(--card))' }}>
                          <span className="font-serif text-2xl" style={{ color: 'var(--paper-faint)', opacity: 0.4 }}>
                            {c.name.charAt(0).toUpperCase()}
                          </span>
                        </div>
                      )}
                    </div>
                    <div className="p-1.5">
                      <p className="font-sans font-semibold text-[11px] leading-tight line-clamp-2" style={{ color: 'var(--paper)' }}>
                        {c.name}
                      </p>
                      <p className="font-mono text-[9px] line-clamp-1 mt-0.5" style={{ color: 'var(--paper-faint)', opacity: c.character ? 0.6 : 0 }}>
                        {c.character || ' '}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          {/* External links */}
          <div className="mb-5">
            <ExternalLinks media={data} />
          </div>

          {/* Add / In Library */}
          {isOwned ? (
            <div className="flex items-center gap-2 text-amber font-mono text-sm py-2.5">
              <Check className="w-4 h-4" />
              Already in your library
            </div>
          ) : !isSharedView ? (
            <button
              onClick={handleAdd}
              className="w-full py-2.5 rounded-lg font-bold text-sm flex items-center justify-center gap-2 transition-colors btn-amber mt-2"
            >
              <Plus className="w-4 h-4" />
              Add to Library
            </button>
          ) : null}
        </div>
      </div>
    </CinemaModal>
  )
}

// ─── Discover view ────────────────────────────────────────────────────────────

export function Discover() {
  const { titles, isSharedView, openAddTitlePreselected } = useAppStore(
    useShallow((s) => ({
      titles: s.titles,
      isSharedView: s.isSharedView,
      openAddTitlePreselected: s.openAddTitlePreselected,
    }))
  )

  // ── Core ──
  const [query, setQuery] = useState('')
  const [filterType, setFilterType] = useState<FilterType>('all')
  const [searchMode, setSearchMode] = useState<SearchMode>('titles')

  // ── Titles mode ──
  const [selectedGenreId, setSelectedGenreId] = useState<number | null>(null)
  const [trending, setTrending] = useState<SearchResult[]>([])
  const [discoverResults, setDiscoverResults] = useState<SearchResult[]>([])
  const [searchResults, setSearchResults] = useState<SearchResult[]>([])

  // ── People mode ──
  const [personResults, setPersonResults] = useState<PersonResult[]>([])
  const [selectedPerson, setSelectedPerson] = useState<PersonResult | null>(null)
  const [allPersonCredits, setAllPersonCredits] = useState<SearchResult[]>([])

  // ── Studios mode ──
  const [companyResults, setCompanyResults] = useState<CompanyResult[]>([])
  const [selectedCompany, setSelectedCompany] = useState<CompanyResult | null>(null)
  const [companyTitles, setCompanyTitles] = useState<SearchResult[]>([])

  // ── Shared ──
  const [selectedResult, setSelectedResult] = useState<SearchResult | null>(null)
  const [isSearchFocused, setIsSearchFocused] = useState(false)
  // Start loading so skeleton shows while the first trending fetch runs.
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const [genresExpanded, setGenresExpanded] = useState(true)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const filterPanelRef = useRef<HTMLDivElement>(null)

  // Fast owned-title lookup by tmdbId
  const libraryTmdbIds = useMemo(
    () => new Set(titles.map((t) => t.tmdbId).filter((id): id is number => id != null)),
    [titles]
  )

  // ── "Because you watched" — front-end only for now, see TODO near becauseWatchedResults ──
  const [becauseWatchedOverrideId, setBecauseWatchedOverrideId] = useState<string | null>(null)

  // ── "More starring" — real TMDB filmography via fetchPersonCredits, keyed off library cast ──
  const [moreStarringOverridePersonId, setMoreStarringOverridePersonId] = useState<number | null>(null)
  const [moreStarringResults, setMoreStarringResults] = useState<SearchResult[]>([])
  const [loadedMoreStarringPersonId, setLoadedMoreStarringPersonId] = useState<number | null>(null)

  // Close the filter popover on outside click
  useClickOutside(filterPanelRef, () => setFiltersOpen(false), filtersOpen)

  // Basis defaults to the first library title unless the user picked one explicitly
  const becauseWatchedId = becauseWatchedOverrideId ?? (titles.length > 0 ? titles[0].id : null)

  const castOptions = useMemo(() => {
    const seen = new Map<number, string>()
    for (const t of titles) {
      for (const c of t.cast ?? []) {
        if (!seen.has(c.tmdbPersonId)) seen.set(c.tmdbPersonId, c.name)
      }
    }
    return Array.from(seen, ([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name))
  }, [titles])

  // Basis defaults to the first known cast member unless the user picked one explicitly
  const moreStarringPersonId = moreStarringOverridePersonId ?? (castOptions.length > 0 ? castOptions[0].id : null)

  // Derived rather than tracked separately: loading exactly while we have a basis whose
  // credits haven't landed yet (mirrors DiscoverDetailModal's `hydrating` derivation above).
  const moreStarringLoading = moreStarringPersonId !== null && moreStarringPersonId !== loadedMoreStarringPersonId

  // Real filmography lookup — unlike "because you watched" this is fully wired to TMDB
  useEffect(() => {
    if (moreStarringPersonId === null) return
    let cancelled = false
    fetchPersonCredits(moreStarringPersonId)
      .then((credits) => {
        if (cancelled) return
        setMoreStarringResults(credits.filter((r) => r.tmdbId == null || !libraryTmdbIds.has(r.tmdbId)))
        setLoadedMoreStarringPersonId(moreStarringPersonId)
      })
      .catch((err) => {
        console.error('more starring credits error:', err)
        if (cancelled) return
        setMoreStarringResults([])
        setLoadedMoreStarringPersonId(moreStarringPersonId)
      })
    return () => { cancelled = true }
  }, [moreStarringPersonId, libraryTmdbIds])

  const genres = filterType === 'tv' ? TV_GENRES : MOVIE_GENRES

  // Person credits filtered client-side by type — no extra fetch when filter changes
  const personCredits = useMemo(
    () => filterType === 'all' ? allPersonCredits : allPersonCredits.filter((r) => r.type === filterType),
    [allPersonCredits, filterType]
  )

  // ── Effects ──

  // Trending — titles mode, no query, no genre
  useEffect(() => {
    if (searchMode !== 'titles' || query.trim() || selectedGenreId !== null) return
    let cancelled = false
    const type: MediaType | 'all' = filterType
    fetchTrending(type)
      .then((data) => { if (!cancelled) { setTrending(data); setPage(1); setHasMore(data.length > 0); setLoading(false) } })
      .catch((err) => { console.error('fetchTrending error:', err); if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [filterType, query, selectedGenreId, searchMode])

  // Genre results — titles mode, genre selected, no query
  useEffect(() => {
    if (searchMode !== 'titles' || selectedGenreId === null || query.trim()) return
    let cancelled = false
    const mediaType: MediaType = filterType === 'all' ? 'movie' : filterType
    fetchDiscover(mediaType, selectedGenreId)
      .then((data) => { if (!cancelled) { setDiscoverResults(data); setPage(1); setHasMore(data.length > 0); setLoading(false) } })
      .catch((err) => { console.error('fetchDiscover error:', err); if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [filterType, selectedGenreId, query, searchMode])

  // Company titles — re-fetch when type filter changes while a company is selected
  // (the loading flag for this case is set by handleTypeChange, which triggers this effect)
  useEffect(() => {
    if (searchMode !== 'studios' || !selectedCompany) return
    let cancelled = false
    const type: MediaType = filterType === 'all' ? 'movie' : filterType
    fetchCompanyTitles(selectedCompany.id, type)
      .then((data) => { if (!cancelled) { setCompanyTitles(data); setLoading(false) } })
      .catch((err) => { console.error('company titles error:', err); if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [selectedCompany, filterType, searchMode])

  // ── Handlers ──

  const handleSearch = useCallback((value: string) => {
    setQuery(value)
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current)
    // Typing resets any active person/company selection back to picker mode
    setSelectedPerson(null)
    setAllPersonCredits([])
    setSelectedCompany(null)
    setCompanyTitles([])
    if (!value.trim()) {
      setSearchResults([])
      setPersonResults([])
      setCompanyResults([])
      return
    }
    setLoading(true)
    searchTimerRef.current = setTimeout(async () => {
      try {
        if (searchMode === 'titles') {
          const all = await searchMedia(value)
          const filtered = filterType === 'all' ? all : all.filter((r) => r.type === filterType)
          setSearchResults(filtered)
        } else if (searchMode === 'people') {
          const results = await searchPersons(value)
          setPersonResults(results)
        } else {
          const results = await searchCompanies(value)
          setCompanyResults(results)
        }
      } catch (err) {
        console.error('search error:', err)
        setSearchResults([])
        setPersonResults([])
        setCompanyResults([])
      } finally {
        setLoading(false)
      }
    }, 400)
  }, [filterType, searchMode])

  async function handlePersonSelect(person: PersonResult) {
    setPersonResults([])
    setSelectedPerson(person)
    setQuery(person.name)
    setLoading(true)
    try {
      const credits = await fetchPersonCredits(person.id)
      setAllPersonCredits(credits)
    } catch (err) {
      console.error('person credits error:', err)
      setAllPersonCredits([])
    } finally {
      setLoading(false)
    }
  }

  async function handleCompanySelect(company: CompanyResult) {
    setCompanyResults([])
    setSelectedCompany(company)
    setQuery(company.name)
    setLoading(true)
    try {
      const type: MediaType = filterType === 'all' ? 'movie' : filterType
      const results = await fetchCompanyTitles(company.id, type)
      setCompanyTitles(results)
    } catch (err) {
      console.error('company titles error:', err)
      setCompanyTitles([])
    } finally {
      setLoading(false)
    }
  }

  function handleSearchModeChange(mode: SearchMode) {
    setSearchMode(mode)
    setQuery('')
    setSearchResults([])
    setPersonResults([])
    setSelectedPerson(null)
    setAllPersonCredits([])
    setCompanyResults([])
    setSelectedCompany(null)
    setCompanyTitles([])
    setSelectedGenreId(null)
    setLoading(mode === 'titles')
    inputRef.current?.focus()
  }

  function clearSearch() {
    setQuery('')
    setSearchResults([])
    setPersonResults([])
    setSelectedPerson(null)
    setAllPersonCredits([])
    setCompanyResults([])
    setSelectedCompany(null)
    setCompanyTitles([])
    inputRef.current?.focus()
  }

  function handleGenreSelect(id: number | null) {
    if (id !== null) setLoading(true)
    setSelectedGenreId(id)
    setSearchResults([])
    setQuery('')
    setPage(1)
    setHasMore(false)
  }

  function handleTypeChange(type: FilterType) {
    setFilterType(type)
    setSelectedGenreId(null)
    setPage(1)
    setHasMore(false)
    if (searchMode === 'titles') {
      setLoading(true)
      setSearchResults([])
      setQuery('')
    } else if (searchMode === 'studios' && selectedCompany) {
      setLoading(true)
    }
    // People: re-filters via personCredits memo
  }

  async function handleLoadMore() {
    setLoadingMore(true)
    const nextPage = page + 1
    try {
      let newResults: SearchResult[] = []
      if (selectedGenreId !== null) {
        const mediaType: MediaType = filterType === 'all' ? 'movie' : filterType
        newResults = await fetchDiscover(mediaType, selectedGenreId, nextPage)
        setDiscoverResults((prev) => [...prev, ...newResults])
      } else {
        newResults = await fetchTrending(filterType, nextPage)
        setTrending((prev) => [...prev, ...newResults])
      }
      setPage(nextPage)
      setHasMore(newResults.length > 0)
    } catch (err) {
      console.error('load more error:', err)
    } finally {
      setLoadingMore(false)
    }
  }

  // ── Derived display state ──

  const inPickerMode =
    (searchMode === 'people' && !selectedPerson) ||
    (searchMode === 'studios' && !selectedCompany)

  const displayResults = (() => {
    if (searchMode === 'titles') {
      if (query.trim()) return searchResults
      if (selectedGenreId !== null) return discoverResults
      return trending
    }
    if (searchMode === 'people') return selectedPerson ? personCredits : []
    return selectedCompany ? companyTitles : []
  })()

  const sectionLabel = (() => {
    if (searchMode === 'people') {
      if (selectedPerson) return `${selectedPerson.name} · Filmography`
      if (query.trim()) return `People matching "${query}"`
      return 'Search for a person'
    }
    if (searchMode === 'studios') {
      if (selectedCompany) return selectedCompany.name
      if (query.trim()) return `Studios matching "${query}"`
      return 'Search for a studio'
    }
    if (query.trim()) return `Results for "${query}"`
    if (selectedGenreId !== null) return genres.find((g) => g.id === selectedGenreId)?.name ?? 'Genre'
    return 'Trending This Week'
  })()

  const discoverDelays = useMemo(() => staggerDelays(displayResults.length), [displayResults.length])

  const becauseWatchedTitle = useMemo(
    () => titles.find((t) => t.id === becauseWatchedId) ?? null,
    [titles, becauseWatchedId]
  )

  // TODO(backend): this reuses the trending pool as a stand-in "similar titles" feed so
  // the UI can be built and reviewed now. Replace with a real recommendation call keyed
  // off `becauseWatchedTitle` (e.g. a media-proxy endpoint wrapping TMDB's
  // /movie|tv/{id}/recommendations, or a Supabase-side taste model) once that lands.
  const becauseWatchedResults = useMemo(() => {
    if (!becauseWatchedTitle) return []
    return trending.filter(
      (r) => r.tmdbId !== becauseWatchedTitle.tmdbId && (r.tmdbId == null || !libraryTmdbIds.has(r.tmdbId))
    )
  }, [becauseWatchedTitle, trending, libraryTmdbIds])

  const becauseWatchedDelays = useMemo(() => staggerDelays(becauseWatchedResults.length), [becauseWatchedResults.length])

  // Hide stale results the moment the basis reverts to no selection (e.g. an empty library)
  // rather than clearing them from an effect.
  const visibleMoreStarringResults = moreStarringPersonId === null ? [] : moreStarringResults

  const moreStarringDelays = useMemo(() => staggerDelays(visibleMoreStarringResults.length), [visibleMoreStarringResults.length])

  const selectedIsOwned = selectedResult?.tmdbId != null && libraryTmdbIds.has(selectedResult.tmdbId)
  const showBack = (searchMode === 'people' && !!selectedPerson) || (searchMode === 'studios' && !!selectedCompany)

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 py-6 sm:py-8">
      {/* Hero heading — centered */}
      <div className="text-center mb-7">
        <h1 className="font-serif text-3xl sm:text-4xl font-bold text-paper leading-tight max-w-xl mx-auto">
          What's missing from your archive?
        </h1>
      </div>

      {/* Search — centered, with filter toggle */}
      <div className="flex items-center gap-2 mb-5 max-w-xl mx-auto">
        <div className="relative flex-1">
          {loading && query.trim() ? (
            <div className="absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none">
              <div className="w-5 h-5 border-2 border-amber/20 border-t-amber/70 rounded-full animate-spin" />
            </div>
          ) : (
            <Search
              className={cn(
                'absolute left-3.5 top-1/2 -translate-y-1/2 w-5 h-5 pointer-events-none transition-colors duration-150',
                isSearchFocused ? 'text-amber/60' : 'text-paper-faint',
              )}
            />
          )}
          <input
            ref={inputRef}
            type="text"
            aria-label={SEARCH_PLACEHOLDERS[searchMode]}
            placeholder={SEARCH_PLACEHOLDERS[searchMode]}
            value={query}
            onChange={(e) => handleSearch(e.target.value)}
            onFocus={() => setIsSearchFocused(true)}
            onBlur={() => setIsSearchFocused(false)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                if (query) clearSearch()
                else inputRef.current?.blur()
              }
            }}
            className="w-full h-12 pl-11 pr-10 rounded-xl border text-base font-sans text-paper placeholder:text-paper-faint focus:outline-none focus:ring-2 focus:ring-amber/30 transition-all duration-150"
            style={{
              background: 'var(--inset)',
              borderColor: isSearchFocused ? 'rgba(233,178,102,0.35)' : 'var(--line)',
            }}
          />
          {query && (
            <button
              onClick={clearSearch}
              aria-label="Clear search"
              className="absolute right-3.5 top-1/2 -translate-y-1/2 text-paper-faint hover:text-paper transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
          )}
        </div>

        {/* Filter icon — toggles the Titles/People/Studios + Genres popover */}
        <div className="relative shrink-0" ref={filterPanelRef}>
          <button
            onClick={() => setFiltersOpen((v) => !v)}
            aria-label="Filters"
            aria-expanded={filtersOpen}
            className={cn(
              'w-12 h-12 rounded-xl border flex items-center justify-center transition-colors',
              filtersOpen || searchMode !== 'titles' || selectedGenreId !== null
                ? 'text-amber border-amber/40 bg-amber/10'
                : 'text-paper-faint border-[var(--line)] hover:text-paper'
            )}
            style={{ background: filtersOpen ? undefined : 'var(--inset)' }}
          >
            <SlidersHorizontal className="w-5 h-5" />
          </button>

          {filtersOpen && (
            <div
              className="absolute right-0 top-[calc(100%+8px)] w-72 rounded-xl border shadow-2xl z-30 p-4 space-y-4 bg-card"
              style={{ borderColor: 'var(--line)' }}
            >
              <div>
                <h3 className="font-mono text-[10px] uppercase tracking-[0.12em] text-paper-faint mb-2">Search by</h3>
                <div className="flex gap-1.5">
                  {([
                    { id: 'titles' as SearchMode, label: 'Titles' },
                    { id: 'people' as SearchMode, label: 'People' },
                    { id: 'studios' as SearchMode, label: 'Studios' },
                  ]).map(({ id, label }) => (
                    <Chip key={id} active={searchMode === id} onClick={() => handleSearchModeChange(id)} className="flex-1 text-center">
                      {label}
                    </Chip>
                  ))}
                </div>
              </div>

              {searchMode === 'titles' && !query.trim() && (
                <div>
                  <button
                    onClick={() => setGenresExpanded((v) => !v)}
                    className="w-full flex items-center justify-between font-mono text-[10px] uppercase tracking-[0.12em] text-paper-faint mb-2"
                  >
                    Genres
                    <ChevronDown className={cn('w-3.5 h-3.5 transition-transform', !genresExpanded && '-rotate-90')} />
                  </button>
                  {genresExpanded && (
                    <div className="space-y-0.5 max-h-56 overflow-y-auto scrollbar-thin" role="radiogroup" aria-label="Filter by genre">
                      <button
                        onClick={() => handleGenreSelect(null)}
                        role="radio"
                        aria-checked={selectedGenreId === null}
                        className="w-full flex items-center gap-2 py-1 text-left text-[13px] font-sans text-paper-faint hover:text-paper transition-colors"
                      >
                        <span
                          className="w-3.5 h-3.5 rounded-full border shrink-0 flex items-center justify-center"
                          style={{ borderColor: selectedGenreId === null ? 'var(--amber)' : 'var(--line)' }}
                        >
                          {selectedGenreId === null && <span className="w-1.5 h-1.5 rounded-full" style={{ background: 'var(--amber)' }} />}
                        </span>
                        All
                      </button>
                      {genres.map((genre) => (
                        <button
                          key={genre.id}
                          onClick={() => handleGenreSelect(genre.id)}
                          role="radio"
                          aria-checked={selectedGenreId === genre.id}
                          className="w-full flex items-center gap-2 py-1 text-left text-[13px] font-sans text-paper-faint hover:text-paper transition-colors"
                        >
                          <span
                            className="w-3.5 h-3.5 rounded-full border shrink-0 flex items-center justify-center"
                            style={{ borderColor: selectedGenreId === genre.id ? 'var(--amber)' : 'var(--line)' }}
                          >
                            {selectedGenreId === genre.id && <span className="w-1.5 h-1.5 rounded-full" style={{ background: 'var(--amber)' }} />}
                          </span>
                          {genre.name}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Format tabs — All / Movies / TV Shows, text style */}
      <div className="flex items-center justify-center gap-6 mb-8">
        {([
          { id: 'all' as FilterType, label: 'Both' },
          { id: 'movie' as FilterType, label: 'Movies' },
          { id: 'tv' as FilterType, label: 'TV Shows' },
        ] as const).map(({ id, label }) => (
          <button
            key={id}
            onClick={() => handleTypeChange(id)}
            className={cn(
              'pb-1.5 border-b-2 font-serif text-lg transition-colors',
              filterType === id
                ? 'text-amber-bright border-amber font-semibold'
                : 'text-paper-faint border-transparent hover:text-paper'
            )}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Main content */}
      <div className="w-full">
        {/* Section label */}
        <div className="flex items-baseline justify-between mb-3">
          <div className="flex items-center gap-1.5">
            {showBack && (
              <button
                onClick={clearSearch}
                aria-label="Back to search"
                className="text-paper-faint hover:text-paper transition-colors"
              >
                <ChevronLeft className="w-3.5 h-3.5" />
              </button>
            )}
            <h2 className="font-serif text-lg font-semibold text-paper">
              {sectionLabel}
            </h2>
          </div>
          {!loading && !inPickerMode && displayResults.length > 0 && (
            searchMode === 'titles' && !query.trim() && hasMore ? (
              <button
                onClick={handleLoadMore}
                disabled={loadingMore}
                className="flex items-center gap-1 text-xs font-mono text-paper-faint hover:text-amber transition-colors disabled:opacity-50"
              >
                {loadingMore ? 'Loading…' : 'View more'}
                <ChevronRight className="w-3.5 h-3.5" />
              </button>
            ) : (
              <span className="font-mono text-[10px] text-paper-faint/60">
                {displayResults.length} title{displayResults.length !== 1 ? 's' : ''}
              </span>
            )
          )}
        </div>

          {inPickerMode ? (
            query.trim() ? (
              loading ? (
                <div className="max-w-lg space-y-2">
                  {Array.from({ length: 4 }, (_, i) => (
                    <div key={i} className="h-16 rounded-lg animate-pulse" style={{ background: 'var(--inset)' }} />
                  ))}
                </div>
              ) : searchMode === 'people' && personResults.length > 0 ? (
                <PersonPicker persons={personResults} onSelect={handlePersonSelect} />
              ) : searchMode === 'studios' && companyResults.length > 0 ? (
                <CompanyPicker companies={companyResults} onSelect={handleCompanySelect} />
              ) : (
                <DiscoverEmptyState
                  icon={searchMode === 'people' ? User : Building2}
                  message="No results found."
                  onClearSearch={clearSearch}
                />
              )
            ) : (
              <DiscoverEmptyState
                icon={searchMode === 'people' ? User : Building2}
                message={searchMode === 'people'
                  ? 'Type a name to browse by actor, director, or crew.'
                  : 'Type a studio name to browse their catalog.'}
                dim
              />
            )
          ) : loading ? (
            <DiscoverSkeleton />
          ) : displayResults.length === 0 ? (
            <DiscoverEmptyState
              icon={Compass}
              message={query.trim() ? 'No results found.' : 'Nothing to show yet.'}
              onClearSearch={query.trim() ? clearSearch : undefined}
            />
          ) : (
            <DiscoverCarousel
              results={displayResults}
              libraryTmdbIds={libraryTmdbIds}
              isSharedView={isSharedView}
              onAdd={openAddTitlePreselected}
              onSelect={setSelectedResult}
              delays={discoverDelays}
            />
          )}
        </div>

        {/* Because you watched — placeholder recommendations, see TODO above becauseWatchedResults */}
        {searchMode === 'titles' && !query.trim() && selectedGenreId === null && titles.length > 0 && (
          <div className="mt-10">
            <div className="flex flex-wrap items-center gap-2.5 mb-3">
              <h2 className="font-serif text-lg font-semibold text-paper">Because you watched</h2>
              <TasteDropdown
                options={titles.map((t) => ({ id: t.id, label: t.title }))}
                value={becauseWatchedId}
                onChange={setBecauseWatchedOverrideId}
                ariaLabel="Choose a title to base recommendations on"
              />
            </div>
            {becauseWatchedResults.length > 0 ? (
              <DiscoverCarousel
                results={becauseWatchedResults}
                libraryTmdbIds={libraryTmdbIds}
                isSharedView={isSharedView}
                onAdd={openAddTitlePreselected}
                onSelect={setSelectedResult}
                delays={becauseWatchedDelays}
              />
            ) : (
              <p className="font-mono text-xs text-paper-faint py-6">
                No suggestions yet — check back once trending titles load.
              </p>
            )}
          </div>
        )}

        {/* More starring — real TMDB filmography for a cast member from the library */}
        {searchMode === 'titles' && !query.trim() && selectedGenreId === null && castOptions.length > 0 && (
          <div className="mt-10">
            <div className="flex flex-wrap items-center gap-2.5 mb-3">
              <h2 className="font-serif text-lg font-semibold text-paper">More starring</h2>
              <TasteDropdown
                options={castOptions.map((c) => ({ id: String(c.id), label: c.name }))}
                value={moreStarringPersonId != null ? String(moreStarringPersonId) : null}
                onChange={(id) => setMoreStarringOverridePersonId(Number(id))}
                ariaLabel="Choose an actor to see more of their titles"
              />
            </div>
            {moreStarringLoading ? (
              <div className="flex gap-3 overflow-hidden">
                {Array.from({ length: 6 }, (_, i) => (
                  <div key={i} className="shrink-0 w-[38vw] sm:w-[170px] md:w-[185px] aspect-[2/3] rounded-lg animate-pulse" style={{ background: 'var(--inset)' }} />
                ))}
              </div>
            ) : visibleMoreStarringResults.length > 0 ? (
              <DiscoverCarousel
                results={visibleMoreStarringResults}
                libraryTmdbIds={libraryTmdbIds}
                isSharedView={isSharedView}
                onAdd={openAddTitlePreselected}
                onSelect={setSelectedResult}
                delays={moreStarringDelays}
              />
            ) : (
              <p className="font-mono text-xs text-paper-faint py-6">
                Nothing else to show — every title with this cast member is already in your archive.
              </p>
            )}
          </div>
        )}

      {/* Detail modal */}
      <DiscoverDetailModal
        result={selectedResult}
        isOwned={selectedIsOwned}
        isSharedView={isSharedView}
        onClose={() => setSelectedResult(null)}
        onAdd={openAddTitlePreselected}
      />
    </div>
  )
}
