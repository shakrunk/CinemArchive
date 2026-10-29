import { saveSucceeded } from 'src/lib/localSave'
import { useState, useEffect } from 'react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { PosterLightbox } from 'src/components/ui/poster-lightbox'
import { BodyText, MetaBadge } from 'src/components/ui/typography'
import { useAppStore, useSelectedTitle } from 'src/store/useAppStore'
import { CastCrewSection } from 'src/components/CastCrewSection'
import { PersonDetailPanel, type PersonDetailTarget } from 'src/components/PersonDetailPanel'
import { Plus, Ticket } from 'lucide-react'
import { cn } from 'src/lib/utils'
import { SendRecommendationPanel } from 'src/components/SendRecommendationPanel'
import { AddToListSheet } from 'src/components/AddToListSheet'
import { TheaterInterestControl } from './TheaterInterestControl'
import { TitleCommentsPanel } from 'src/components/TitleCommentsPanel'
import SpiderWebOverlay from 'src/components/SpiderWebOverlay'
import { MatrixPillModal } from 'src/components/MatrixPillModal'
import { MatrixDigitalRain } from 'src/components/MatrixDigitalRain'
import { TrailerRow } from 'src/components/ui/trailer-row'
import { FranchiseSection } from 'src/components/FranchiseSection'
import { SPIDER_NOIR_TMDB_ID, THE_MATRIX_TMDB_ID } from 'src/lib/easterEggThemes'
import { SectionCard } from './title-detail/SectionCard'
import { ViewingTimeline } from './title-detail/ViewingTimeline'
import { TVSeriesSection } from './title-detail/TVSeriesSection'
import { DrawerTagEditor } from './title-detail/DrawerTagEditor'
import { OutingBanner } from './title-detail/OutingBanner'
import { useTitleMedia } from './title-detail/useTitleMedia'
import { useEpisodeBackfill } from './title-detail/useEpisodeBackfill'
import { useSpiderNoirMode } from './title-detail/useSpiderNoirMode'
import { useLogViewingForm } from './title-detail/useLogViewingForm'
import { LogViewingForm } from './title-detail/LogViewingForm'
import { TitleHero } from './title-detail/TitleHero'
import { TitleActions } from './title-detail/TitleActions'
import { TitleSidebar } from './title-detail/TitleSidebar'

// ─── Main drawer ─────────────────────────────────────────────────────────────

export function TitleDetailDrawer() {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const isDetailDrawerOpen = useAppStore((s) => s.isDetailDrawerOpen)
  const closeDetailDrawer = useAppStore((s) => s.closeDetailDrawer)
  const updateTitle = useAppStore((s) => s.updateTitle)
  const removeTitle = useAppStore((s) => s.removeTitle)
  const removeViewing = useAppStore((s) => s.removeViewing)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const viewerContext = useAppStore((s) => s.viewerContext)
  const openOutingSchedule = useAppStore((s) => s.openOutingSchedule)
  const browseByStudio = useAppStore((s) => s.browseByStudio)
  const unlockTheme = useAppStore((s) => s.unlockTheme)
  const title = useSelectedTitle()

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
        <TitleHero
          title={title}
          backdropOverride={heroBackdropUrl}
          logoUrl={displayLogoUrl}
          isSharedView={isSharedView}
          noirSelector={isSpiderNoir ? {
            unlockedModes, earnedModes, selected: manualMode, pinned: pinnedModeRaw,
            onSelect: handleModeSelect, onTogglePin: handleTogglePin,
          } : null}
          showMatrixPill={isMatrix && (title.viewings.length > 0 || title.status === 'watched')}
          onRedPill={() => setShowMatrixRain(true)}
          onPosterClick={() => setPosterLightboxOpen(true)}
          onRate={(rating) => updateTitle(title.id, { rating })}
        />

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
            <TitleActions
              title={title}
              confirmingDelete={pendingDeleteTitle}
              onConfirmingDeleteChange={setPendingDeleteTitle}
              onDelete={handleDelete}
              onSendToFriend={() => setSendPanelOpen(true)}
            />
          )}
          </main>

          {/* Right column — supplementary metadata flows independently. */}
          <TitleSidebar
            title={title}
            isSharedView={isSharedView}
            watchProviders={watchProviders}
            onOpenListPicker={() => setIsAddToListOpen(true)}
          />
        </div>
      </div>
    </CinemaModal>
    </>
  )
}
