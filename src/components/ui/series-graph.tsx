import { cn } from 'src/lib/utils'
import { avgEpisodeRating } from 'src/store/episodeUtils'
import type { Season } from 'src/store/mockData'

// ─── Color scale: null→void, 1→ember, 3→moon, 5→amber-bright ────────────────

function ratingColor(avg: number | null): string {
  if (avg === null) return 'rgba(28, 20, 14, 0.55)'
  if (avg < 1.5) return 'rgba(215, 106, 73, 0.90)'   // ember (very low)
  if (avg < 2.5) return 'rgba(229, 142, 111, 0.75)'  // ember-soft (low)
  if (avg < 3.5) return 'rgba(143, 182, 203, 0.80)'  // moon (mid)
  if (avg < 4.5) return 'rgba(233, 178, 102, 0.90)'  // amber (good)
  return 'rgba(247, 205, 134, 1.00)'                 // amber-bright (excellent)
}

function ratingLabel(avg: number | null): string {
  if (avg === null) return 'Not rated'
  return `★ ${avg.toFixed(1)}`
}

// ─── Legend ──────────────────────────────────────────────────────────────────

const LEGEND = [
  { color: ratingColor(1), label: '1–2' },
  { color: ratingColor(2.5), label: '2–3' },
  { color: ratingColor(3), label: '3–4' },
  { color: ratingColor(4), label: '4–5' },
  { color: ratingColor(5), label: '5' },
]

// ─── SeriesGraph ─────────────────────────────────────────────────────────────

interface SeriesGraphProps {
  seasons: Season[]
  onCellClick?: (seasonNumber: number, episodeNumber: number) => void
  className?: string
}

export function SeriesGraph({ seasons, onCellClick, className }: SeriesGraphProps) {
  const maxEpisodes = Math.max(...seasons.map((s) => s.episodeCount), 1)

  return (
    <div className={cn('series-graph', className)}>
      {/* Column headers: episode numbers */}
      <div
        className="grid mb-1"
        style={{ gridTemplateColumns: `2.2rem repeat(${maxEpisodes}, 1fr)`, gap: '3px' }}
      >
        <div />
        {Array.from({ length: maxEpisodes }, (_, i) => (
          <div
            key={i}
            className="text-center font-mono leading-none"
            style={{ fontSize: '9px', color: 'var(--paper-faint)' }}
          >
            {i + 1}
          </div>
        ))}
      </div>

      {/* Rows: one per season */}
      {seasons.map((season) => (
        <div
          key={season.id}
          className="grid"
          style={{ gridTemplateColumns: `2.2rem repeat(${maxEpisodes}, 1fr)`, gap: '3px', marginBottom: '3px' }}
        >
          {/* Row label */}
          <div
            className="flex items-center font-mono"
            style={{ fontSize: '10px', color: 'var(--paper-faint)' }}
          >
            S{season.seasonNumber}
          </div>

          {/* Episode cells */}
          {Array.from({ length: maxEpisodes }, (_, i) => {
            const epNum = i + 1
            const ep = season.episodes?.find((e) => e.episodeNumber === epNum)
            const exists = epNum <= season.episodeCount
            const avg = ep ? avgEpisodeRating(ep) : null
            const watched = (ep?.watchEvents.length ?? 0) > 0

            if (!exists) {
              return <div key={i} style={{ aspectRatio: '1' }} />
            }

            return (
              <div
                key={i}
                role={onCellClick ? 'button' : undefined}
                tabIndex={onCellClick ? 0 : undefined}
                title={ep ? `S${season.seasonNumber}E${epNum}${ep.episodeName ? ` · ${ep.episodeName}` : ''} — ${ratingLabel(avg)}` : `S${season.seasonNumber}E${epNum}`}
                onClick={() => onCellClick?.(season.seasonNumber, epNum)}
                onKeyDown={(e) => {
                  if (onCellClick && (e.key === 'Enter' || e.key === ' ')) {
                    e.preventDefault()
                    onCellClick(season.seasonNumber, epNum)
                  }
                }}
                className={cn(
                  'series-graph__cell',
                  watched && 'series-graph__cell--watched',
                  onCellClick && 'cursor-pointer'
                )}
                style={{
                  background: ratingColor(avg),
                  aspectRatio: '1',
                  borderRadius: '3px',
                  transition: 'transform 0.15s, box-shadow 0.15s',
                  outline: 'none',
                }}
              />
            )
          })}
        </div>
      ))}

      {/* Legend */}
      <div className="flex items-center gap-3 mt-3 flex-wrap">
        <span className="font-mono" style={{ fontSize: '9px', color: 'var(--paper-faint)', letterSpacing: '0.1em' }}>
          RATING
        </span>
        {LEGEND.map((l) => (
          <div key={l.label} className="flex items-center gap-1">
            <div
              className="rounded-sm"
              style={{ width: 10, height: 10, background: l.color }}
            />
            <span className="font-mono" style={{ fontSize: '9px', color: 'var(--paper-faint)' }}>
              {l.label}
            </span>
          </div>
        ))}
        <div className="flex items-center gap-1">
          <div
            className="rounded-sm"
            style={{ width: 10, height: 10, background: ratingColor(null) }}
          />
          <span className="font-mono" style={{ fontSize: '9px', color: 'var(--paper-faint)' }}>
            unrated
          </span>
        </div>
      </div>
    </div>
  )
}
