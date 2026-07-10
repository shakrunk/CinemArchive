import { useState, useEffect, useRef, useCallback } from 'react'
import { Play, Pause, ChevronLeft, ChevronRight, Volume2, VolumeX, Maximize } from 'lucide-react'
import type { TitleVideo } from 'src/lib/media'
import { SubsectionLabel } from 'src/components/ui/typography'
import { ModalCloseButton } from 'src/components/ui/modal-close-button'

// ── Minimal YT IFrame API types ───────────────────────────────────────────────
interface YTWindow extends Window {
  YT?: {
    Player: new (el: HTMLElement, opts: {
      videoId: string
      playerVars?: Record<string, number>
      events?: {
        onReady?: (e: { target: YTPlayer }) => void
        onStateChange?: (e: { data: number; target: YTPlayer }) => void
      }
    }) => YTPlayer
  }
  onYouTubeIframeAPIReady?: () => void
}
interface YTPlayer {
  playVideo(): void
  pauseVideo(): void
  seekTo(s: number, allowSeekAhead: boolean): void
  getCurrentTime(): number
  getDuration(): number
  mute(): void
  unMute(): void
  isMuted(): boolean
  getPlayerState(): number
  destroy(): void
}

// ── YouTube IFrame API singleton loader ───────────────────────────────────────
let ytApiReady: Promise<void> | null = null

function loadYTApi(): Promise<void> {
  if (ytApiReady) return ytApiReady
  ytApiReady = new Promise<void>((resolve) => {
    const w = window as YTWindow
    if (w.YT?.Player) { resolve(); return }
    const prev = w.onYouTubeIframeAPIReady
    w.onYouTubeIframeAPIReady = () => { prev?.(); resolve() }
    if (!document.querySelector('script[src*="youtube.com/iframe_api"]')) {
      const s = document.createElement('script')
      s.src = 'https://www.youtube.com/iframe_api'
      document.head.appendChild(s)
    }
  })
  return ytApiReady
}

function fmtTime(s: number): string {
  if (!isFinite(s) || s < 0) return '0:00'
  return `${Math.floor(s / 60)}:${Math.floor(s % 60).toString().padStart(2, '0')}`
}

// ── Amber icon button ─────────────────────────────────────────────────────────
function IconBtn({
  onClick, label, children, amber = false,
}: { onClick: () => void; label: string; children: React.ReactNode; amber?: boolean }) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className="focus:outline-none transition-colors"
      style={{ color: amber ? 'var(--amber)' : 'var(--paper-faint)', lineHeight: 0 }}
      onMouseEnter={(e) => { if (!amber) e.currentTarget.style.color = 'var(--paper)' }}
      onMouseLeave={(e) => { if (!amber) e.currentTarget.style.color = 'var(--paper-faint)' }}
    >
      {children}
    </button>
  )
}

// ── TrailerPlayer ─────────────────────────────────────────────────────────────
// Receives `key={video.key}` from the parent so it fully remounts on video
// change — all state resets naturally without synchronous setState in effects.
interface TrailerPlayerProps {
  video: TitleVideo
  videoIndex: number
  totalVideos: number
  onPrev: () => void
  onNext: () => void
  onClose: () => void
}

function TrailerPlayer({ video, videoIndex, totalVideos, onPrev, onNext, onClose }: TrailerPlayerProps) {
  const [isPlaying, setIsPlaying] = useState(false)
  const [isMuted, setIsMuted] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [isBuffering, setIsBuffering] = useState(true)
  const [showControls, setShowControls] = useState(true)

  const playerDivRef = useRef<HTMLDivElement>(null)
  const playerRef = useRef<YTPlayer | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const isSeeking = useRef(false)

  const clearHideTimer = useCallback(() => {
    if (hideTimerRef.current) clearTimeout(hideTimerRef.current)
  }, [])

  const resetHideTimer = useCallback(() => {
    setShowControls(true)
    clearHideTimer()
    hideTimerRef.current = setTimeout(() => {
      if (!isSeeking.current) setShowControls(false)
    }, 3000)
  }, [clearHideTimer])

  const startTick = useCallback(() => {
    if (tickRef.current) clearInterval(tickRef.current)
    tickRef.current = setInterval(() => {
      const p = playerRef.current
      if (!p) return
      if (!isSeeking.current) setCurrentTime(p.getCurrentTime())
      const d = p.getDuration()
      if (isFinite(d) && d > 0) setDuration(d)
    }, 250)
  }, [])

  const stopTick = useCallback(() => {
    if (tickRef.current) { clearInterval(tickRef.current); tickRef.current = null }
  }, [])

  // Create YT player on mount; clean up on unmount.
  // No setState in the effect body — callbacks handle state updates instead.
  useEffect(() => {
    let cancelled = false
    loadYTApi().then(() => {
      if (cancelled || !playerDivRef.current) return
      const w = window as YTWindow
      if (!w.YT) return
      playerRef.current = new w.YT.Player(playerDivRef.current, {
        videoId: video.key,
        playerVars: {
          autoplay: 1,
          controls: 0,
          rel: 0,
          modestbranding: 1,
          iv_load_policy: 3,
          playsinline: 1,
          disablekb: 1,
          fs: 0,
        },
        events: {
          onReady: (e) => {
            if (cancelled) return
            const d = e.target.getDuration()
            if (isFinite(d) && d > 0) setDuration(d)
            setIsMuted(e.target.isMuted())
          },
          onStateChange: (e) => {
            if (cancelled) return
            switch (e.data) {
              case 1: // PLAYING
                setIsPlaying(true)
                setIsBuffering(false)
                startTick()
                resetHideTimer()
                break
              case 2: // PAUSED
                setIsPlaying(false)
                setIsBuffering(false)
                stopTick()
                setShowControls(true)
                clearHideTimer()
                break
              case 3: // BUFFERING
                setIsBuffering(true)
                break
              case 0: // ENDED
                setIsPlaying(false)
                stopTick()
                setShowControls(true)
                clearHideTimer()
                break
            }
          },
        },
      })
    })

    return () => {
      cancelled = true
      stopTick()
      clearHideTimer()
      if (playerRef.current) {
        try { playerRef.current.destroy() } catch { /* no-op */ }
        playerRef.current = null
      }
    }
  }, [video.key, startTick, stopTick, clearHideTimer, resetHideTimer])

  const togglePlay = useCallback(() => {
    const p = playerRef.current
    if (!p) return
    if (p.getPlayerState() === 1) p.pauseVideo()
    else p.playVideo()
    resetHideTimer()
  }, [resetHideTimer])

  const toggleMute = useCallback(() => {
    const p = playerRef.current
    if (!p) return
    if (p.isMuted()) { p.unMute(); setIsMuted(false) }
    else { p.mute(); setIsMuted(true) }
  }, [])

  const toggleFullscreen = useCallback(() => {
    if (!containerRef.current) return
    if (document.fullscreenElement) document.exitFullscreen()
    else containerRef.current.requestFullscreen?.()
    resetHideTimer()
  }, [resetHideTimer])

  // Keyboard shortcuts
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const tag = (e.target as HTMLElement).tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA') return
      if (e.key === 'Escape') { onClose(); return }
      if (e.key === 'ArrowLeft' && !e.ctrlKey && !e.metaKey) onPrev()
      if (e.key === 'ArrowRight' && !e.ctrlKey && !e.metaKey) onNext()
      if (e.key === ' ' || e.key === 'k') { e.preventDefault(); togglePlay() }
      if (e.key === 'm') toggleMute()
      if (e.key === 'f') toggleFullscreen()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose, onPrev, onNext, togglePlay, toggleMute, toggleFullscreen])

  // Scrubber — click and drag
  const handleScrubberMouseDown = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    e.preventDefault()
    isSeeking.current = true
    const el = e.currentTarget

    const seek = (clientX: number) => {
      const rect = el.getBoundingClientRect()
      const pct = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width))
      const t = pct * (playerRef.current?.getDuration() ?? 0)
      setCurrentTime(t)
      playerRef.current?.seekTo(t, true)
    }

    seek(e.clientX)

    const onMove = (ev: MouseEvent) => seek(ev.clientX)
    const onUp = () => {
      isSeeking.current = false
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      resetHideTimer()
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }, [resetHideTimer])

  const progress = duration > 0 ? Math.min(100, (currentTime / duration) * 100) : 0
  const controlsVisible = showControls || !isPlaying

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`${video.name} — trailer`}
      className="fixed inset-0 z-[215] flex items-center justify-center"
      style={{ background: 'radial-gradient(ellipse at 50% 40%, rgba(11,9,7,0.9) 0%, rgba(4,3,2,0.98) 100%)' }}
      onClick={onClose}
    >
      {/* Film grain on backdrop */}
      <div
        aria-hidden="true"
        className="absolute inset-0 pointer-events-none"
        style={{
          backgroundImage: `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='160'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E")`,
          backgroundSize: '180px 180px',
          opacity: 0.045,
        }}
      />

      <ModalCloseButton
        onClick={(e) => { e.stopPropagation(); onClose() }}
        ariaLabel="Close trailer"
        variant="scrim"
        className="z-10"
      />

      {/* Main panel */}
      <div
        ref={containerRef}
        className="relative w-full px-4 sm:px-10"
        style={{ maxWidth: '820px' }}
        onClick={(e) => e.stopPropagation()}
        onMouseMove={isPlaying ? resetHideTimer : undefined}
      >
        {/* Projector beam glow above frame */}
        <div
          aria-hidden="true"
          className="absolute left-1/2 -translate-x-1/2 pointer-events-none"
          style={{
            top: '-56px',
            width: '50%',
            height: '56px',
            background: 'radial-gradient(ellipse at 50% 100%, rgba(233,178,102,0.1) 0%, transparent 70%)',
          }}
        />

        {/* Video frame */}
        <div
          className="relative w-full rounded-xl overflow-hidden"
          style={{
            paddingBottom: '56.25%',
            boxShadow:
              '0 0 0 1px rgba(233,178,102,0.18), ' +
              '0 24px 80px -16px rgba(0,0,0,0.85), ' +
              '0 0 60px -12px rgba(233,178,102,0.07)',
          }}
        >
          {/* YT player mounts here */}
          <div ref={playerDivRef} className="absolute inset-0 w-full h-full" />

          {/* Controls overlay */}
          <div
            className="absolute inset-0 flex flex-col justify-end select-none transition-opacity duration-300"
            style={{ opacity: controlsVisible ? 1 : 0, pointerEvents: controlsVisible ? 'auto' : 'none' }}
          >
            {/* Click-to-toggle area */}
            <div className="absolute inset-0 cursor-pointer" onClick={togglePlay} />

            {/* Bottom gradient mask */}
            <div
              aria-hidden="true"
              className="absolute inset-x-0 bottom-0 pointer-events-none"
              style={{
                height: '100px',
                background: 'linear-gradient(to top, rgba(7,5,4,0.92) 0%, rgba(7,5,4,0.25) 65%, transparent 100%)',
              }}
            />

            {/* Buffering spinner */}
            {isBuffering && (
              <div
                aria-label="Loading"
                className="absolute inset-0 flex items-center justify-center pointer-events-none"
              >
                <div
                  className="w-10 h-10 rounded-full animate-spin"
                  style={{
                    border: '2.5px solid rgba(233,178,102,0.2)',
                    borderTopColor: 'var(--amber)',
                  }}
                />
              </div>
            )}

            {/* Control bar */}
            <div
              className="relative z-10 px-4 pb-3 flex flex-col gap-2.5"
              onClick={(e) => e.stopPropagation()}
            >
              {/* Scrubber */}
              <div
                className="w-full h-1 rounded-full cursor-pointer group/scrub relative"
                style={{ background: 'rgba(233,178,102,0.2)' }}
                onMouseDown={handleScrubberMouseDown}
              >
                <div
                  className="absolute left-0 top-0 h-full rounded-full pointer-events-none"
                  style={{ width: `${progress}%`, background: 'var(--amber)' }}
                />
                <div
                  className="absolute top-1/2 -translate-y-1/2 -translate-x-1/2 w-3 h-3 rounded-full pointer-events-none opacity-0 group-hover/scrub:opacity-100 transition-opacity duration-150"
                  style={{
                    left: `${progress}%`,
                    background: 'var(--amber)',
                    boxShadow: '0 0 8px rgba(233,178,102,0.6)',
                  }}
                />
              </div>

              {/* Buttons */}
              <div className="flex items-center gap-3">
                <IconBtn onClick={togglePlay} label={isPlaying ? 'Pause' : 'Play'} amber>
                  {isPlaying
                    ? <Pause className="w-5 h-5" fill="currentColor" />
                    : <Play className="w-5 h-5" fill="currentColor" />}
                </IconBtn>

                <span
                  className="font-mono tabular-nums"
                  style={{ fontSize: '11px', color: 'var(--paper-dim)', letterSpacing: '0.04em' }}
                >
                  {fmtTime(currentTime)}
                  <span style={{ color: 'var(--paper-faint)', margin: '0 3px' }}>/</span>
                  {fmtTime(duration)}
                </span>

                <div className="flex-1" />

                <IconBtn onClick={toggleMute} label={isMuted ? 'Unmute' : 'Mute'}>
                  {isMuted ? <VolumeX className="w-4 h-4" /> : <Volume2 className="w-4 h-4" />}
                </IconBtn>

                <IconBtn onClick={toggleFullscreen} label="Toggle fullscreen">
                  <Maximize className="w-4 h-4" />
                </IconBtn>
              </div>
            </div>
          </div>
        </div>

        {/* Info + nav strip */}
        <div className="mt-4 flex items-center gap-3">
          <button
            type="button"
            onClick={onPrev}
            disabled={videoIndex === 0}
            aria-label="Previous trailer"
            className="shrink-0 flex items-center justify-center w-8 h-8 rounded-full transition-colors duration-150 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 disabled:opacity-30 disabled:cursor-not-allowed"
            style={{
              background: 'rgba(11,9,7,0.6)',
              color: 'var(--paper-dim)',
              border: '1px solid rgba(233,178,102,0.12)',
            }}
            onMouseEnter={(e) => { if (videoIndex > 0) e.currentTarget.style.color = 'var(--amber)' }}
            onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--paper-dim)' }}
          >
            <ChevronLeft className="w-4 h-4" />
          </button>

          <div className="flex-1 min-w-0 text-center">
            <div
              className="font-serif truncate"
              style={{ fontSize: '14px', color: 'var(--paper)', lineHeight: 1.3 }}
            >
              {video.name}
            </div>
            <div
              className="font-mono uppercase flex items-center justify-center gap-2 mt-0.5"
              style={{ fontSize: '9px', letterSpacing: '0.14em' }}
            >
              <span style={{ color: 'var(--amber-deep)' }}>{video.type}</span>
              {totalVideos > 1 && (
                <>
                  <span style={{ color: 'var(--paper-faint)' }}>·</span>
                  <span style={{ color: 'var(--paper-faint)' }}>{videoIndex + 1} / {totalVideos}</span>
                </>
              )}
            </div>
          </div>

          <button
            type="button"
            onClick={onNext}
            disabled={videoIndex === totalVideos - 1}
            aria-label="Next trailer"
            className="shrink-0 flex items-center justify-center w-8 h-8 rounded-full transition-colors duration-150 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 disabled:opacity-30 disabled:cursor-not-allowed"
            style={{
              background: 'rgba(11,9,7,0.6)',
              color: 'var(--paper-dim)',
              border: '1px solid rgba(233,178,102,0.12)',
            }}
            onMouseEnter={(e) => { if (videoIndex < totalVideos - 1) e.currentTarget.style.color = 'var(--amber)' }}
            onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--paper-dim)' }}
          >
            <ChevronRight className="w-4 h-4" />
          </button>
        </div>

        {/* Dot indicators */}
        {totalVideos > 1 && (
          <div className="flex items-center justify-center gap-2 mt-3">
            {Array.from({ length: totalVideos }, (_, i) => (
              <div
                key={i}
                className="rounded-full transition-all duration-200"
                style={{
                  width: i === videoIndex ? '20px' : '5px',
                  height: '5px',
                  background: i === videoIndex ? 'var(--amber)' : 'rgba(233,178,102,0.22)',
                }}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

// ── TrailerRow (thumbnail strip + lightbox host) ───────────────────────────────
export function TrailerRow({ videos }: { videos: TitleVideo[] }) {
  const [activeIndex, setActiveIndex] = useState<number | null>(null)

  if (videos.length === 0) return null

  const prev = () => setActiveIndex(i => i !== null ? Math.max(0, i - 1) : 0)
  const next = () => setActiveIndex(i => i !== null ? Math.min(videos.length - 1, i + 1) : 0)

  return (
    <>
      <div>
        <SubsectionLabel>Trailers</SubsectionLabel>
        <div className="flex gap-4 overflow-x-auto scrollbar-none -mx-6 px-6 pb-1">
          {videos.map((v, i) => (
            <button
              key={v.key}
              type="button"
              onClick={() => setActiveIndex(i)}
              aria-label={`Watch ${v.name}`}
              className="group shrink-0 w-[220px] sm:w-[260px] text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 rounded-lg"
            >
              <div
                className="relative w-full rounded-lg overflow-hidden group-hover:shadow-[0_0_0_1.5px_rgba(233,178,102,0.5)]"
                style={{ paddingBottom: '56.25%' }}
              >
                <img
                  src={`https://img.youtube.com/vi/${v.key}/hqdefault.jpg`}
                  alt={v.name}
                  className="absolute inset-0 w-full h-full object-cover transition-opacity group-hover:opacity-70"
                />
                {/* Vignette */}
                <div
                  aria-hidden="true"
                  className="absolute inset-0 pointer-events-none"
                  style={{
                    background: 'radial-gradient(ellipse at center, transparent 25%, rgba(7,5,4,0.5) 100%)',
                  }}
                />
                {/* Amber play button */}
                <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                  <div
                    className="rounded-full transition-all duration-200 group-hover:scale-110"
                    style={{
                      padding: '11px',
                      background: 'rgba(7,5,4,0.62)',
                      border: '1.5px solid rgba(233,178,102,0.4)',
                      backdropFilter: 'blur(4px)',
                    }}
                  >
                    <Play className="w-5 h-5" style={{ color: 'var(--amber)' }} fill="var(--amber)" />
                  </div>
                </div>
              </div>

              <div className="mt-2 px-0.5">
                <div
                  className="font-sans line-clamp-1"
                  style={{ fontSize: '12.5px', color: 'var(--paper)' }}
                >
                  {v.name}
                </div>
                <div
                  className="font-mono uppercase"
                  style={{ fontSize: '10px', color: 'var(--amber-deep)', letterSpacing: '0.1em', opacity: 0.6 }}
                >
                  {v.type}
                </div>
              </div>
            </button>
          ))}
        </div>
      </div>

      {activeIndex !== null && (
        <TrailerPlayer
          key={videos[activeIndex].key}
          video={videos[activeIndex]}
          videoIndex={activeIndex}
          totalVideos={videos.length}
          onPrev={prev}
          onNext={next}
          onClose={() => setActiveIndex(null)}
        />
      )}
    </>
  )
}
