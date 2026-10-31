import type { SearchResult } from 'src/lib/media'
import { DiscoverCarousel } from './DiscoverCarousel'
import { CarouselPauseButton } from './CarouselPauseButton'
import { TasteDropdown } from './TasteDropdown'
import type { RecommendationRail } from './useRecommendationRails'

interface RecommendationRailSectionProps {
  heading: string
  rail: RecommendationRail
  ariaLabel: string
  emptyMessage: string
  /** Sticky auto-scroll pause, owned by the view so it survives the rail unmounting. */
  paused: boolean
  onTogglePause: () => void
  ownedCatalogKeys: ReadonlySet<string>
  isSharedView: boolean
  onAdd: (result: SearchResult) => void
  onSelect: (result: SearchResult) => void
}

/** A taste-based carousel row below the main Discover strip: heading, basis picker,
 *  pause toggle, and the carousel (or its loading/empty state). */
export function RecommendationRailSection({
  heading, rail, ariaLabel, emptyMessage, paused, onTogglePause, ownedCatalogKeys, isSharedView, onAdd, onSelect,
}: RecommendationRailSectionProps) {
  return (
    <div className="mt-10">
      <div className="flex flex-wrap items-center gap-2.5 mb-3">
        <h2 className="font-serif text-lg font-semibold text-paper">{heading}</h2>
        <TasteDropdown
          options={rail.options}
          value={rail.value}
          onChange={rail.onChange}
          ariaLabel={ariaLabel}
        />
        {!rail.loading && rail.results.length > 0 && (
          <div className="ml-auto">
            <CarouselPauseButton paused={paused} onToggle={onTogglePause} />
          </div>
        )}
      </div>
      {rail.loading ? (
        <div className="flex gap-3 overflow-hidden">
          {Array.from({ length: 6 }, (_, i) => (
            <div key={i} className="shrink-0 w-[38vw] sm:w-[170px] md:w-[185px] aspect-2/3 rounded-lg animate-pulse" style={{ background: 'var(--inset)' }} />
          ))}
        </div>
      ) : rail.results.length > 0 ? (
        <DiscoverCarousel
          results={rail.results}
          ownedCatalogKeys={ownedCatalogKeys}
          isSharedView={isSharedView}
          onAdd={onAdd}
          onSelect={onSelect}
          delays={rail.delays}
          paused={paused}
        />
      ) : (
        <p className="font-mono text-xs text-paper-faint py-6">
          {emptyMessage}
        </p>
      )}
    </div>
  )
}
