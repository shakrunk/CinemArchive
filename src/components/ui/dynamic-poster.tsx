import { useMemo } from 'react'
import { cn } from 'src/lib/utils'
import type { Title, WatchStatus } from 'src/store/mockData'

interface DynamicPosterProps {
  title: Title
  className?: string
  onClick?: () => void
  /** Render the full editorial face (title, meta, stars) inside the poster. */
  rich?: boolean
}

/* Moody, cinematic tints keyed off the title — never neon. */
const TINTS = [
  '#6b7480', // slate
  '#7a5c6e', // mauve
  '#5c6b5a', // olive
  '#8a6a4f', // sienna
  '#4f6675', // steel blue
  '#7a6048', // umber
  '#5e5a72', // dusk violet
  '#6f5450', // clay
  '#506b66', // teal-grey
  '#7c6b54', // sand
]

function hashString(str: string): number {
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = (hash << 5) - hash + str.charCodeAt(i)
    hash = hash & hash
  }
  return Math.abs(hash)
}

const STATUS_BADGE: Record<WatchStatus, { label: string; cls: string }> = {
  watched: { label: 'Seen', cls: 'bg-[rgba(233,178,102,0.18)] text-amber-bright' },
  watchlist: { label: 'Watchlist', cls: 'bg-[rgba(143,182,203,0.18)] text-moon' },
  watching: { label: 'Watching', cls: 'bg-[rgba(247,205,134,0.16)] text-amber' },
  dropped: { label: 'Dropped', cls: 'bg-[rgba(215,106,73,0.18)] text-ember-soft' },
}

function Stars({ rating }: { rating: number }) {
  return (
    <div className="flex gap-px text-amber drop-shadow-[0_1px_4px_rgba(233,178,102,0.5)]">
      {Array.from({ length: 5 }, (_, i) => {
        const filled = rating - i
        return (
          <span key={i} className="relative inline-block text-[13px] leading-none">
            <span className="text-white/20">★</span>
            {filled > 0 && (
              <span
                className="absolute inset-0 overflow-hidden text-amber"
                style={{ width: filled >= 1 ? '100%' : `${filled * 100}%` }}
              >
                ★
              </span>
            )}
          </span>
        )
      })}
    </div>
  )
}

export function DynamicPoster({ title, className, onClick, rich = false }: DynamicPosterProps) {
  const hasImage = Boolean(title.posterUrl)
  const tint = useMemo(() => TINTS[hashString(title.title) % TINTS.length], [title.title])
  const badge = STATUS_BADGE[title.status]

  function handleKeyDown(e: React.KeyboardEvent<HTMLDivElement>) {
    if (!onClick) return
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      onClick()
    }
  }

  return (
    <div
      className={cn('poster', hasImage && 'has-img', className)}
      style={{ ['--tint' as string]: tint }}
      onClick={onClick}
      onKeyDown={handleKeyDown}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
    >
      {hasImage && (
        <img className="poster__img" src={title.posterUrl} alt={title.title} loading="lazy" />
      )}

      <div className="poster__face">
        {/* top row: category + status */}
        <div className="relative z-[2] flex items-center justify-between">
          <span className="font-mono text-[10px] tracking-[0.12em] uppercase text-white/60">
            {title.type === 'tv' ? 'Series' : 'Film'}
          </span>
          <span
            className={cn(
              'inline-flex items-center px-2 py-[3px] rounded-full font-mono text-[9px] tracking-[0.1em] uppercase backdrop-blur-sm',
              badge.cls
            )}
          >
            {badge.label}
          </span>
        </div>

        {/* body */}
        {rich ? (
          <div className="relative z-[2] mt-auto">
            <h3 className="poster__title text-[clamp(18px,2vw,24px)]">{title.title}</h3>
            <div className="mt-2 flex items-center gap-2 font-mono text-[11px] text-white/70">
              <span>{title.year}</span>
              {title.runtime ? (
                <>
                  <span className="opacity-40">·</span>
                  <span>{title.runtime}m</span>
                </>
              ) : null}
            </div>
            {title.director && (
              <p className="mt-1 text-[12px] text-white/55 truncate">{title.director}</p>
            )}
            {title.rating ? (
              <div className="mt-2.5">
                <Stars rating={title.rating} />
              </div>
            ) : null}
          </div>
        ) : (
          /* compact face for the typographic (no-image) case */
          !hasImage && (
            <div className="relative z-[2] mt-auto">
              <h3 className="poster__title text-[clamp(16px,1.8vw,21px)]">{title.title}</h3>
              <div className="mt-1.5 font-mono text-[11px] text-white/60">{title.year}</div>
            </div>
          )
        )}
      </div>
    </div>
  )
}
