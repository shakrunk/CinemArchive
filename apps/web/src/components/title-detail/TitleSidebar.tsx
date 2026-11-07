import { StatLabel } from 'src/components/ui/typography'
import { useAppStore } from 'src/store/useAppStore'
import { Calendar, Eye } from 'lucide-react'
import { fmtDate, fmtReleaseDate, languageName } from 'src/lib/utils'
import type { Title } from 'src/store/mockData'
import type { WatchProviders } from 'src/lib/media'
import { WatchProvidersSection } from 'src/components/ui/watch-providers'
import { ReviewBadges, ExternalLinks } from 'src/components/ui/media-badges'
import { SectionCard } from './SectionCard'
import { StatusCard } from './StatusCard'
import { ListsCard } from './ListsCard'
import { DetailRow } from './DetailRow'
import { viewingTime } from './viewingTime'

interface TitleSidebarProps {
  title: Title
  isSharedView: boolean
  watchProviders: WatchProviders | null
  onOpenListPicker: () => void
}

/** The drawer's right column: status, lists, stats, details, reception, links, and
 *  where to watch — supplementary metadata that flows independently of the main column. */
export function TitleSidebar({ title, isSharedView, watchProviders, onOpenListPicker }: TitleSidebarProps) {
  const updateTitle = useAppStore((s) => s.updateTitle)

  return (
    <aside className="space-y-5 min-w-0">
      <StatusCard
        status={title.status}
        isSharedView={isSharedView}
        onChange={(status) => updateTitle(title.id, { status })}
      />
      {!isSharedView && <ListsCard titleId={title.id} onOpenPicker={onOpenListPicker} />}
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
  )
}
