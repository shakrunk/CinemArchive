import { isCatalogTitleOwned } from 'src/lib/catalogIdentity'
import { useState, useEffect, useCallback, useRef } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import {
  type SearchResult,
} from 'src/lib/media'
import { usePrefersReducedMotionRef } from 'src/lib/motion'
import { DiscoverCard } from './DiscoverCard'

// The track is duplicated once and driven by transform (not native scroll), so it can
// loop seamlessly: past the end of the first copy, the visible position wraps back to
// the same spot in the second copy. Speed is applied via requestAnimationFrame directly
// to the transform + the --reel-scroll-x custom property (which the sprocket-hole masks
// read), never through React state, so scrolling never triggers a re-render.

const CAROUSEL_SPEED_PX_S = 26

// Momentum glide after a drag release: exponential decay rate (s⁻¹) and the speed
// below which the glide is considered stopped. Velocity is clamped so a wild fling
// can't launch the strip several loop-widths in a blink.
const MOMENTUM_FRICTION = 4
const MOMENTUM_MIN_SPEED_PX_S = 8
const MOMENTUM_MAX_SPEED_PX_S = 3200

// Sprocket-hole pitch from the .reel-strip mask in index.css (repeating-linear-gradient
// period, 8px hole + 30.4px stock). Mirror any change to that value here.
const SPROCKET_PITCH_PX = 38.4

interface DiscoverCarouselProps {
  results: SearchResult[]
  ownedCatalogKeys: ReadonlySet<string>
  isSharedView: boolean
  onAdd: (result: SearchResult) => void
  onSelect: (result: SearchResult) => void
  delays: number[]
  /** Sticky auto-scroll pause, controlled by the header's CarouselPauseButton —
   *  the strip itself only owns the transient hover/focus pause (KP-044). */
  paused: boolean
}

export function DiscoverCarousel({ results, ownedCatalogKeys, isSharedView, onAdd, onSelect, delays, paused }: DiscoverCarouselProps) {
  const wrapperRef = useRef<HTMLDivElement>(null)
  const trackRef = useRef<HTMLDivElement>(null)
  const markerRef = useRef<HTMLDivElement>(null)
  const isInViewportRef = useRef(false)
  const scrollXRef = useRef(0)
  const singleSetWidthRef = useRef(0)
  const draggingRef = useRef(false)
  const dragMovedRef = useRef(false)
  const dragStartXRef = useRef(0)
  const dragStartScrollRef = useRef(0)
  const pausedRef = useRef(false)
  const velocityRef = useRef(0)
  const lastMoveRef = useRef<{ x: number; t: number } | null>(null)
  // Mirrors the `paused` prop into a ref so the rAF loop can read it without
  // re-subscribing on every toggle.
  const userPausedRef = useRef(paused)
  useEffect(() => { userPausedRef.current = paused }, [paused])
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

  // Keep the ambient marquee from advancing while its carousel is off-screen.
  // Manual dragging, wheel input, and release momentum remain available whenever
  // the user is interacting with the strip.
  useEffect(() => {
    const wrapper = wrapperRef.current
    if (!wrapper) return
    if (!('IntersectionObserver' in window)) {
      isInViewportRef.current = true
      return
    }
    const observer = new IntersectionObserver(([entry]) => {
      isInViewportRef.current = entry.isIntersecting
    })
    observer.observe(wrapper)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (results.length === 0) return
    let raf = 0
    let lastTs: number | null = null
    function tick(ts: number) {
      if (lastTs == null) lastTs = ts
      const dt = (ts - lastTs) / 1000
      lastTs = ts
      if (!draggingRef.current) {
        let moved = false
        // Momentum glide from a released drag — decays independently of, and stacks
        // with, the ambient auto-scroll, so a fling doesn't "kill" the marquee.
        if (Math.abs(velocityRef.current) >= MOMENTUM_MIN_SPEED_PX_S) {
          scrollXRef.current += velocityRef.current * dt
          velocityRef.current *= Math.exp(-MOMENTUM_FRICTION * dt)
          moved = true
        } else {
          velocityRef.current = 0
        }
        if (isInViewportRef.current && !pausedRef.current && !userPausedRef.current && !reducedMotionRef.current) {
          scrollXRef.current += CAROUSEL_SPEED_PX_S * dt
          moved = true
        }
        if (moved) applyTransform()
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
    velocityRef.current = 0
    lastMoveRef.current = { x: e.clientX, t: performance.now() }
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
    // Instantaneous scroll velocity (px/s), low-pass blended so one jittery pointer
    // sample can't spike the release fling. Pointer moving right scrolls X negative,
    // hence the sign flip.
    const now = performance.now()
    const last = lastMoveRef.current
    if (last && now > last.t) {
      const sample = (-(e.clientX - last.x) / (now - last.t)) * 1000
      velocityRef.current = 0.8 * sample + 0.2 * velocityRef.current
    }
    lastMoveRef.current = { x: e.clientX, t: now }
    scrollXRef.current = dragStartScrollRef.current - delta
    applyTransform()
  }

  function endDrag(e: React.PointerEvent) {
    draggingRef.current = false
    setIsGrabbing(false)
    // Momentum: keep the strip gliding at the release velocity, decayed in the rAF
    // loop. Suppressed when the pointer stalled before release (a stale velocity
    // sample would fling a strip the user had deliberately stopped), and under
    // reduced motion.
    const last = lastMoveRef.current
    const stalled = !last || performance.now() - last.t > 100
    if (!dragMovedRef.current || stalled || reducedMotionRef.current) {
      velocityRef.current = 0
    } else {
      velocityRef.current = Math.max(
        -MOMENTUM_MAX_SPEED_PX_S,
        Math.min(MOMENTUM_MAX_SPEED_PX_S, velocityRef.current)
      )
    }
    lastMoveRef.current = null
    if (e.currentTarget.hasPointerCapture(e.pointerId)) {
      e.currentTarget.releasePointerCapture(e.pointerId)
    }
  }

  // Mouse-wheel / trackpad horizontal scroll — the strip is transform-driven (no
  // native scroll surface), so sideways wheel input does nothing without this.
  // Attached natively with passive: false because React's onWheel can't reliably
  // preventDefault (browsers register root wheel listeners as passive), and only
  // horizontal-dominant deltas are consumed so vertical wheel still scrolls the page.
  useEffect(() => {
    const el = wrapperRef.current
    if (!el) return
    function onWheel(e: WheelEvent) {
      if (Math.abs(e.deltaX) <= Math.abs(e.deltaY)) return
      e.preventDefault()
      velocityRef.current = 0
      scrollXRef.current += e.deltaX
      applyTransform()
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [applyTransform])

  // Pause on pointer hover (mouse) and on keyboard focus (Tab-ing through cards),
  // so the strip doesn't slide away from underneath a focused or hovered card.
  function pause() { pausedRef.current = true }
  function resume() { pausedRef.current = false }

  // Chevron override — a manual nudge layered on top of the auto-scroll/drag, for
  // anyone who'd rather page through than wait for the marquee or grab-drag it.
  // The auto-scroll keeps drifting from wherever this lands, same as after a drag.
  function scrollByPage(dir: 1 | -1) {
    const amount = (trackRef.current?.parentElement?.clientWidth ?? 300) * 0.85 * dir
    velocityRef.current = 0
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
                isOwned={isCatalogTitleOwned(ownedCatalogKeys, result)}
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
                isOwned={isCatalogTitleOwned(ownedCatalogKeys, result)}
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
            className="hidden sm:flex absolute left-2 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full items-center justify-center opacity-0 group-hover/carousel:opacity-100 transition-opacity shadow-lg z-10 focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
            style={{ background: 'rgb(var(--void-rgb) / 0.85)', border: '1px solid var(--line)' }}
          >
            <ChevronLeft className="w-4 h-4 text-paper" />
          </button>
          <button
            onClick={() => scrollByPage(1)}
            aria-label="Scroll right"
            className="hidden sm:flex absolute right-2 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full items-center justify-center opacity-0 group-hover/carousel:opacity-100 transition-opacity shadow-lg z-10 focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
            style={{ background: 'rgb(var(--void-rgb) / 0.85)', border: '1px solid var(--line)' }}
          >
            <ChevronRight className="w-4 h-4 text-paper" />
          </button>
        </div>
      </div>
    </div>
  )
}
