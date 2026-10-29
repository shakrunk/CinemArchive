import { saveSucceeded } from 'src/lib/localSave'
import { useState, useEffect } from 'react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { StarRating } from 'src/components/ui/star-rating'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { PosterLightbox } from 'src/components/ui/poster-lightbox'
import { CardTitle, BodyText, MetaBadge, StatLabel } from 'src/components/ui/typography'
import { useAppStore, useSelectedTitle } from 'src/store/useAppStore'
import { CastCrewSection } from 'src/components/CastCrewSection'
import { PersonDetailPanel, type PersonDetailTarget } from 'src/components/PersonDetailPanel'
import { Calendar, Clock, Eye, Film, Tv, Plus, Trash2, RefreshCw, Send, Ticket } from 'lucide-react'
import { cn, fmtDate, fmtReleaseDate, fmtRuntime, languageName } from 'src/lib/utils'
import { SendRecommendationPanel } from 'src/components/SendRecommendationPanel'
import { AddToListSheet } from 'src/components/AddToListSheet'
import { TheaterInterestControl } from './TheaterInterestControl'
import { TitleCommentsPanel } from 'src/components/TitleCommentsPanel'
import SpiderWebOverlay from 'src/components/SpiderWebOverlay'
import { SpiderNoirModeSelector } from 'src/components/SpiderNoirModeSelector'
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
import { viewingTime } from './title-detail/viewingTime'
import { ViewingTimeline } from './title-detail/ViewingTimeline'
import { TVSeriesSection } from './title-detail/TVSeriesSection'
import { DrawerTagEditor } from './title-detail/DrawerTagEditor'
import { OutingBanner } from './title-detail/OutingBanner'
import { useTitleMedia } from './title-detail/useTitleMedia'
import { useEpisodeBackfill } from './title-detail/useEpisodeBackfill'
import { useSpiderNoirMode } from './title-detail/useSpiderNoirMode'
import { useLogViewingForm } from './title-detail/useLogViewingForm'
import { LogViewingForm } from './title-detail/LogViewingForm'

// ─── Main drawer ─────────────────────────────────────────────────────────────

export function TitleDetailDrawer() {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const isDetailDrawerOpen = useAppStore((s) => s.isDetailDrawerOpen)
  const closeDetailDrawer = useAppStore((s) => s.closeDetailDrawer)
  const updateTitle = useAppStore((s) => s.updateTitle)
  const removeTitle = useAppStore((s) => s.removeTitle)
  const removeViewing = useAppStore((s) => s.removeViewing)
  const openRefreshMetadata = useAppStore((s) => s.openRefreshMetadata)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const viewerContext = useAppStore((s) => s.viewerContext)
  const openOutingSchedule = useAppStore((s) => s.openOutingSchedule)
  const browseByStudio = useAppStore((s) => s.browseByStudio)
  const unlockTheme = useAppStore((s) => s.unlockTheme)
  const title = useSelectedTitle()
  const user = useAppStore((s) => s.user)

  const {
    isSpiderNoir, unlockedModes, earnedModes, manualMode, pinnedModeRaw, noirAnim, noirLogoUrl,
    handleModeSelect, handleTogglePin,
  } = useSpiderNoirMode(title, isDetailDrawerOpen)
  const isMatrix = title?.tmdbId === THE_MATRIX_TMDB_ID

  const [showMatrixModal, setShowMatrixModal] = useState(false)
  const [showMatrixRain, setShowMatrixRain] = useState(false)

  const [sendPanelOpen, setSendPanelOpen] = useState(false)
  const [isAddToListOpen, setIsAddToListOpen] = useState(false)

  // Taking the red pill unlocks the app-wide Matrix theme (Settings → Appearance).
  useEffect(() => {
    if (showMatrixRain) unlockTheme('matrix')
  }, [showMatrixRain, unlockTheme])

  const logForm = useLogViewingForm(title)
  const { showLogForm, setShowLogForm, logViewing } = logForm
  const [pendingDeleteTitle, setPendingDeleteTitle] = useState(false)
  const [posterLightboxOpen, setPosterLightboxOpen] = useState(false)
  const [activePerson, setActivePerson] = useState<PersonDetailTarget | null>(null)

  const { videos, watchProviders, logoUrl, heroBackdropUrl } = useTitleMedia(isDetailDrawerOpen, title?.tmdbId, title?.type)

  const displayLogoUrl = noirLogoUrl ?? logoUrl

  function onClose() {
    setPendingDeleteTitle(false)
    setPosterLightboxOpen(false)
    setActivePerson(null)
    closeDetailDrawer()
  }

  useEpisodeBackfill(title, isSharedView)

  if (!title) return null

  async function handleDelete() {
    if (!title) return
    if (await saveSucceeded(removeTitle(title.id))) closeDetailDrawer()
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
                  <LogViewingForm form={logForm} titleId={title.id} onSave={handleSaveViewing} />
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
