import { PlayCircle } from 'lucide-react'
import { useUpNextShows, useAppStore } from 'src/store/useAppStore'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import type { UpNextEntry } from 'src/store/upNext'

function EmptyState({ onBrowseLibrary }: { onBrowseLibrary: () => void }) {
  return (
    <div className="text-center py-24 px-5 text-paper-faint">
      <PlayCircle className="w-14 h-14 mx-auto mb-5 text-amber-deep opacity-50" />
      <p className="font-serif text-2xl text-paper-dim font-light">Nothing in progress.</p>
      <p className="font-sans text-sm mt-2 opacity-70">
        Start a series and set it to “Watching” to see your next episode here.
      </p>
      <button
        onClick={onBrowseLibrary}
        className="mt-6 inline-flex items-center gap-2 rounded-md px-4 py-2 text-sm font-sans border border-amber/30 text-amber hover:bg-amber/10 transition-colors"
      >
        Browse the Library
      </button>
    </div>
  )
}

function UpNextCard({ entry }: { entry: UpNextEntry }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const { title, season, episode, watchedCount, totalCount } = entry
  const pct = totalCount > 0 ? Math.round((watchedCount / totalCount) * 100) : 0
  const epName = episode.episodeName ?? `Episode ${episode.episodeNumber}`

  return (
    <div
      className="flex gap-4 rounded-xl p-3 sm:p-4"
      style={{ border: '1px solid var(--line)', background: 'linear-gradient(180deg, var(--ink-1), rgba(17,13,11,0.4))' }}
    >
      <button onClick={() => openDetailDrawer(title.id)} className="w-16 sm:w-20 shrink-0" aria-label={`Open ${title.title}`}>
        <DynamicPoster title={title} />
      </button>
      <div className="flex-1 min-w-0 flex flex-col">
        <button onClick={() => openDetailDrawer(title.id)} className="text-left">
          <h3 className="font-serif text-lg sm:text-xl font-medium text-paper truncate" style={{ fontVariationSettings: '"opsz" 30' }}>
            {title.title}
          </h3>
        </button>
        <p className="font-mono text-xs text-amber mt-0.5">S{season.seasonNumber} E{episode.episodeNumber} · Next</p>
        <p className="font-sans text-sm text-paper-dim truncate">{epName}</p>
        <div className="mt-auto pt-3">
          <div className="flex items-center gap-2">
            <div className="flex-1 h-1.5 rounded-full overflow-hidden" style={{ background: 'rgba(255,255,255,0.06)' }}>
              <div className="h-full rounded-full bg-amber transition-all" style={{ width: `${pct}%` }} />
            </div>
            <span className="font-mono text-[11px] text-paper-faint shrink-0">{watchedCount}/{totalCount}</span>
          </div>
        </div>
      </div>
    </div>
  )
}

export function UpNext({ onBrowseLibrary }: { onBrowseLibrary: () => void }) {
  const shows = useUpNextShows()

  return (
    <div className="max-w-[1100px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <header className="mb-6">
        <p className="kicker"><span className="dot" /> continue watching</p>
        <h1 className="display-title text-[clamp(32px,6vw,56px)] mt-3">Up Next</h1>
      </header>
      {shows.length === 0 ? (
        <EmptyState onBrowseLibrary={onBrowseLibrary} />
      ) : (
        <div className="space-y-3">
          {shows.map((entry) => (
            <UpNextCard key={entry.title.id} entry={entry} />
          ))}
        </div>
      )}
    </div>
  )
}
