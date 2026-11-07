import { useState } from 'react'
import { Film, Tv, Check, Plus, Info } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import {
  type SearchResult,
} from 'src/lib/media'
import { cn } from 'src/lib/utils'

interface DiscoverCardProps {
  result: SearchResult
  isOwned: boolean
  style?: React.CSSProperties
  className?: string
  isSharedView: boolean
  onAdd: (result: SearchResult) => void
  onSelect: (result: SearchResult) => void
}

export function DiscoverCard({ result, isOwned, isSharedView, onAdd, onSelect, style, className }: DiscoverCardProps) {
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
      <div className="relative aspect-2/3 overflow-hidden film-frame__window transition-transform duration-200 group-hover:scale-[1.015]">
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
        <div className="absolute inset-0 bg-linear-to-t from-[#080503] via-[#080503]/55 to-transparent" />
        {/* Stronger scrim on hover, for contrast under the action row */}
        <div className="absolute inset-0 bg-linear-to-t from-[#080503] via-[#080503]/85 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-200" />
        <div className="absolute inset-0 flex flex-col justify-end p-2.5">
          <p className="font-serif text-[13px] font-semibold text-[rgb(var(--ivory))] leading-snug line-clamp-2 mb-0.5">
            {result.title}
          </p>
          {result.type === 'tv' && result.seasonCount ? (
            <p className="font-mono text-[10px] text-white/60">{result.seasonCount}S</p>
          ) : null}
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
                <div className="flex items-center gap-0 group-hover:gap-1.5 transition-all duration-300 delay-1500 pt-2">
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
                    className="relative group/detail shrink-0 w-0 group-hover:w-[30px] opacity-0 group-hover:opacity-100 transition-all duration-300 delay-1500"
                  >
                    {/* Tooltip — always visible once the wrapper fades in */}
                    <div className="absolute bottom-full right-0 mb-1.5 pointer-events-none z-10">
                      <div
                        className="relative px-2 py-1 rounded shadow-lg"
                        style={{ background: 'rgb(var(--scrim-ink))', border: '1px solid rgb(var(--amber-rgb) / 0.35)' }}
                      >
                        <p className="font-mono text-[9px] text-white/60 whitespace-nowrap">Click for more details</p>
                        <div
                          className="absolute top-full right-2.5"
                          style={{
                            width: 0, height: 0,
                            borderLeft: '4px solid transparent',
                            borderRight: '4px solid transparent',
                            borderTop: '4px solid rgb(var(--amber-rgb) / 0.35)',
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
            <Check className="w-[11px] h-[11px] text-(--on-amber)" strokeWidth={3} />
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
