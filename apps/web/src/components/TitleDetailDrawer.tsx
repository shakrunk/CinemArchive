import { captureLibrarySession, saveSucceeded } from 'src/lib/localSave'
import { useState, useEffect, useRef, useMemo } from 'react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { StarRating } from 'src/components/ui/star-rating'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { PosterLightbox } from 'src/components/ui/poster-lightbox'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { CardTitle, BodyText, MetaBadge, StatLabel, Eyebrow } from 'src/components/ui/typography'
import { useAppStore, useSelectedTitle } from 'src/store/useAppStore'
import { CastCrewSection } from 'src/components/CastCrewSection'
import { PersonDetailPanel, type PersonDetailTarget } from 'src/components/PersonDetailPanel'
import { getUnlockedModes, getEarnedModes } from 'src/store/episodeUtils'
import {
  Calendar, Check, Clock, Eye, Film, Tv, Plus, FileText, Trash2, Star,
  RefreshCw, Send, Ticket,
} from 'lucide-react'
import { cn, fmtDate, fmtReleaseDate, fmtRuntime, languageName } from 'src/lib/utils'
import type { Title, Viewing, Episode, EpisodeCrew, Companion } from 'src/store/mockData'
import { fetchSeasonDetails, TMDB_STILL_BASE } from 'src/lib/media'
import { SendRecommendationPanel } from 'src/components/SendRecommendationPanel'
import { AddToListSheet } from 'src/components/AddToListSheet'
import { TheaterInterestControl } from './TheaterInterestControl'
import { TitleCommentsPanel } from 'src/components/TitleCommentsPanel'
import SpiderWebOverlay from 'src/components/SpiderWebOverlay'
import { SpiderNoirModeSelector } from 'src/components/SpiderNoirModeSelector'
import { transitionSpiderNoir } from 'src/lib/theme'
import { MatrixPillModal } from 'src/components/MatrixPillModal'
import { MatrixDigitalRain } from 'src/components/MatrixDigitalRain'
import { MatrixPillSelector } from 'src/components/MatrixPillSelector'
import { HeroBackdrop } from 'src/components/ui/hero-backdrop'
import { TrailerRow } from 'src/components/ui/trailer-row'
import { FranchiseSection } from 'src/components/FranchiseSection'
import { WatchProvidersSection } from 'src/components/ui/watch-providers'
import { ReviewBadges, ExternalLinks, HeroScores } from 'src/components/ui/media-badges'
import { SPIDER_NOIR_TMDB_ID, THE_MATRIX_TMDB_ID } from 'src/lib/easterEggThemes'
import { SectionCard } from './title-detail/SectionCard'
import { StatusCard } from './title-detail/StatusCard'
import { ListsCard } from './title-detail/ListsCard'
import { DetailRow } from './title-detail/DetailRow'
import { VenueCompanionsFields } from './title-detail/VenueCompanionsFields'
import { viewingTime } from './title-detail/viewingTime'
import { ViewingTimeline } from './title-detail/ViewingTimeline'
import { TVSeriesSection } from './title-detail/TVSeriesSection'
import { DrawerTagEditor } from './title-detail/DrawerTagEditor'
import { OutingBanner } from './title-detail/OutingBanner'
import { useTitleMedia } from './title-detail/useTitleMedia'

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

// ─── Main drawer ─────────────────────────────────────────────────────────────

export function TitleDetailDrawer() {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const isDetailDrawerOpen = useAppStore((s) => s.isDetailDrawerOpen)
  const closeDetailDrawer = useAppStore((s) => s.closeDetailDrawer)
  const updateTitle = useAppStore((s) => s.updateTitle)
  const addViewing = useAppStore((s) => s.addViewing)
  const updateTitleMetadata = useAppStore((s) => s.updateTitleMetadata)
  const removeTitle = useAppStore((s) => s.removeTitle)
  const removeViewing = useAppStore((s) => s.removeViewing)
  const openRefreshMetadata = useAppStore((s) => s.openRefreshMetadata)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const viewerContext = useAppStore((s) => s.viewerContext)
  const openOutingSchedule = useAppStore((s) => s.openOutingSchedule)
  const browseByStudio = useAppStore((s) => s.browseByStudio)
  const title = useSelectedTitle()
  const user = useAppStore((s) => s.user)

  const isSpiderNoir = title?.tmdbId === SPIDER_NOIR_TMDB_ID
  const isMatrix = title?.tmdbId === THE_MATRIX_TMDB_ID

  const [showMatrixModal, setShowMatrixModal] = useState(false)
  const [showMatrixRain, setShowMatrixRain] = useState(false)

  const [sendPanelOpen, setSendPanelOpen] = useState(false)
  const [isAddToListOpen, setIsAddToListOpen] = useState(false)

  const pinnedModes = useAppStore((s) => s.pinnedModes)
  const setPinnedMode = useAppStore((s) => s.setPinnedMode)
  const unlockTheme = useAppStore((s) => s.unlockTheme)

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

  const { videos, watchProviders, logoUrl, heroBackdropUrl } = useTitleMedia(isDetailDrawerOpen, title?.tmdbId, title?.type)

  const displayLogoUrl = isSpiderNoir
    ? (effectiveNoirMode === 'color' ? SPIDER_NOIR_LOGO_COLOR : effectiveNoirMode === 'bw' ? SPIDER_NOIR_LOGO_BW : SPIDER_NOIR_LOGO_NORMAL)
    : logoUrl

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
    const checkSession = captureLibrarySession()

    const EP_CREW_JOBS_BF = new Set(['Director', 'Writer', 'Teleplay', 'Story'])

    async function backfill() {
      const settled = await Promise.allSettled(
        seasonsNeedingBackfill.map(async (season) => {
          const { episodes: tmdbEps, cast: seasonCast } = await fetchSeasonDetails(snapshotTitle.tmdbId, season.seasonNumber)
          return { season, tmdbEps, seasonCast }
        })
      )

      let updatedSeasons = [...snapshotTitle.seasons!]

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
          const tmdbEpMap = new Map(tmdbEps.map((e) => [e.episode_number, e]))
          updatedEpisodes = existingEpisodes.map((ep) => {
            const tmdbEp = tmdbEpMap.get(ep.episodeNumber)
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

      }

      checkSession()
      if (JSON.stringify(snapshotTitle.seasons) !== JSON.stringify(updatedSeasons)) {
        await updateTitleMetadata(snapshotTitle.id, { seasons: updatedSeasons })
      }
    }

    backfill().catch((e) => console.error('Episode metadata backfill failed:', e))
  }, [title?.id, title?.tmdbId]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!title) return null

  async function logViewing() {
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
    if (!await saveSucceeded(addViewing(title.id, viewing, {
      status: 'watched',
      ...(logRating > 0 ? { rating: logRating } : {}),
    }))) return false
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
    return true
  }

  async function handleDelete() {
    if (!title) return
    if (await saveSucceeded(removeTitle(title.id))) closeDetailDrawer()
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

  async function handleMatrixRed() {
    setShowMatrixModal(false)
    if (await logViewing()) setShowMatrixRain(true)
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
      {/* Poster lightbox — rendered above the dialog content via z-215 */}
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

      {isAddToListOpen && (
        <AddToListSheet
          titleId={title.id}
          titleName={title.title}
          onClose={() => setIsAddToListOpen(false)}
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
              <StatLabel>
                {title.type === 'tv' ? 'Series' : 'Film'}
              </StatLabel>
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
              <div className="h-8 flex items-center rounded-md px-2.5 bg-black/55 backdrop-blur-xs border border-white/10">
                <StarRating
                  value={title.rating ?? 0}
                  size="sm"
                  tone="onArt"
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
                    className="block w-full rounded-lg overflow-hidden transition-opacity hover:opacity-90 focus:outline-hidden focus-visible:ring-2 focus-visible:ring-amber/60"
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
                  <StatLabel>
                    {title.type === 'tv' ? 'Series' : 'Film'}
                  </StatLabel>
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
                  <div className="h-8 flex items-center rounded-md px-2.5 bg-black/55 backdrop-blur-xs border border-white/10">
                    <StarRating
                      value={title.rating ?? 0}
                      size="sm"
                      tone="onArt"
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
                tmdbId={title.tmdbId}
                seasons={title.seasons}
                seriesCast={title.cast}
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
              <TheaterInterestControl titleId={title.id} />
              {/* Viewing History */}
              <SectionCard
                title="Viewing History"
                className="lg:col-start-1"
                action={!showLogForm && !isSharedView ? (
                    <div className="flex items-center gap-3">
                      {(title.status === 'watchlist' || title.status === 'watching') && (
                        <button type="button"
                          onClick={() => openOutingSchedule(title.id)}
                          className="flex items-center gap-1 text-xs font-mono text-amber/70 hover:text-amber transition-colors rounded-sm focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
                        >
                          <Ticket className="w-3.5 h-3.5" />
                          I've got tickets
                        </button>
                      )}
                      <button type="button"
                        onClick={() => setShowLogForm(true)}
                        className="flex items-center gap-1 text-xs font-mono text-amber/70 hover:text-amber transition-colors rounded-sm focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
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
                      <Eyebrow as="label" htmlFor="viewing-date" size="xl" tone="muted" font="sans" className="block mb-2 cursor-pointer">
                        <Calendar className="inline w-3 h-3 mr-1" />
                        Date Watched
                      </Eyebrow>
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
                      <Eyebrow as="p" size="xl" tone="muted" font="sans" className="block mb-2">
                        Rating
                      </Eyebrow>
                      <StarRating value={logRating} onChange={setLogRating} size="md" />
                    </div>
                    <VenueCompanionsFields
                      venue={logVenue}
                      onVenueChange={setLogVenue}
                      companions={logCompanions}
                      onCompanionsChange={setLogCompanions}
                    />
                    <div>
                      <Eyebrow as="label" htmlFor="viewing-notes" size="xl" tone="muted" font="sans" className="block mb-2 cursor-pointer">
                        <FileText className="inline w-3 h-3 mr-1" />
                        Notes
                      </Eyebrow>
                      <textarea
                        id="viewing-notes"
                        value={logNotes}
                        onChange={(e) => setLogNotes(e.target.value)}
                        placeholder="Your thoughts…"
                        rows={2}
                        className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-hidden focus:ring-2 focus:ring-amber/30"
                      />
                    </div>
                    <div className="flex gap-2">
                      <Button
                        className="flex-1 bg-amber hover:bg-amber-muted text-(--on-amber) font-sans font-medium"
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
                    <button type="button"
                      onClick={handleDelete}
                      className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs transition-opacity hover:opacity-80"
                      style={{ color: 'var(--ember)' }}
                      aria-label="Delete forever: title from library"
                    >
                      Delete forever
                    </button>
                    <button type="button"
                      onClick={() => setPendingDeleteTitle(false)}
                      className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs transition-opacity hover:opacity-80"
                      style={{ color: 'var(--paper-faint)' }}
                      aria-label="Cancel deleting title from library"
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              ) : (
                <div className="flex flex-wrap items-center gap-2">
                  <button type="button"
                    onClick={openRefreshMetadata}
                    className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-(--line) text-muted-foreground hover:text-amber hover:border-amber/40 hover:bg-amber/5 transition-all focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
                  >
                    <RefreshCw className="w-3.5 h-3.5" />
                    Refresh poster &amp; metadata
                  </button>
                  {title.type === 'movie' && (title.status === 'watched' || title.status === 'dropped') && (
                    <button type="button"
                      onClick={() => openOutingSchedule(title.id)}
                      className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-(--line) text-muted-foreground hover:text-amber hover:border-amber/40 hover:bg-amber/5 transition-all focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
                    >
                      <Ticket className="w-3.5 h-3.5" />
                      Plan a cinema trip
                    </button>
                  )}
                  {user && (
                    <button type="button"
                      onClick={() => setSendPanelOpen(true)}
                      className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-(--line) text-muted-foreground hover:text-amber hover:border-amber/40 hover:bg-amber/5 transition-all focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
                    >
                      <Send className="w-3.5 h-3.5" />
                      Send to a friend
                    </button>
                  )}
                  <button type="button"
                    onClick={() => setPendingDeleteTitle(true)}
                    className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-(--line) text-muted-foreground hover:text-ember hover:border-ember/30 hover:bg-ember/5 transition-all focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-ember/60"
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
            {!isSharedView && <ListsCard titleId={title.id} onOpenPicker={() => setIsAddToListOpen(true)} />}
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
