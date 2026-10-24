import { useState, useEffect, useMemo } from 'react'
import { Check, Film, Plus } from 'lucide-react'
import { fetchCollectionParts, type SearchResult } from 'src/lib/media'
import { cn } from 'src/lib/utils'
import { SubsectionLabel } from 'src/components/ui/typography'
import { useAppStore } from 'src/store/useAppStore'
import type { Title } from 'src/store/mockData'

// ─── Franchise section — other movies in the collection + watch progress ─────
// KP-027 (franchise strip) / KP-028 (watched X/Y progress). Parts come from
// TMDB's /collection/{id} via the media-proxy; library membership and watched
// state are resolved client-side against the store's titles.

export function FranchiseSection({
  collectionId,
  collectionName,
  currentTmdbId,
  isSharedView,
  onNavigate,
}: {
  collectionId: number
  collectionName?: string
  currentTmdbId: number
  isSharedView: boolean
  /** Called just before opening another title, so a hosting modal can close first. */
  onNavigate?: () => void
}) {
  const titles = useAppStore((s) => s.titles)
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const openAddTitlePreselected = useAppStore((s) => s.openAddTitlePreselected)
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
        <SubsectionLabel className="mb-0">
          {franchiseLabel} <span className="normal-case tracking-normal text-muted-foreground font-normal">· franchise</span>
        </SubsectionLabel>
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
            <div key={i} className="shrink-0 w-[84px] aspect-2/3 rounded-lg animate-pulse" style={{ background: 'var(--inset)' }} />
          ))}
        </div>
      ) : (
        <div className="flex gap-2.5 overflow-x-auto pb-1 scrollbar-none">
          {parts.map((p) => {
            const libTitle = p.tmdbId != null ? libraryByTmdbId.get(p.tmdbId) : undefined
            const isCurrent = p.tmdbId === currentTmdbId
            const isWatched = libTitle?.status === 'watched'
            return (
              <button type="button"
                key={p.tmdbId}
                onClick={() => {
                  if (isCurrent) return
                  onNavigate?.()
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
                  'shrink-0 w-[84px] text-left group focus-visible:outline-hidden',
                  isCurrent ? 'cursor-default' : 'cursor-pointer'
                )}
              >
                <div
                  className={cn(
                    'relative aspect-2/3 rounded-lg overflow-hidden border transition-transform',
                    !isCurrent && 'group-hover:scale-[1.03] group-focus-visible:ring-1 group-focus-visible:ring-amber/60',
                    isCurrent ? 'border-amber/60' : 'border-(--line)'
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
                      <Check className="w-2.5 h-2.5 text-(--on-amber)" strokeWidth={3} />
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
