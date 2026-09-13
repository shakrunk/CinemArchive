import { useEffect, useRef, useState } from 'react'
import { ChevronDown, ChevronUp, Plus } from 'lucide-react'
import type { CastMember, CrewMember } from 'src/store/mockData'
import type { PersonDetailTarget } from 'src/components/PersonDetailPanel'
import { cn } from 'src/lib/utils'
import { Eyebrow } from 'src/components/ui/typography'

const CREW_DISPLAY: Array<{ jobs: string[]; label: string }> = [
  { jobs: ['Creator'],                                     label: 'Created by' },
  { jobs: ['Director'],                                    label: 'Dir.' },
  { jobs: ['Screenplay', 'Writer', 'Teleplay', 'Story'],   label: 'Written by' },
  { jobs: ['Producer'],                                    label: 'Prod.' },
  { jobs: ['Director of Photography'],                     label: 'D.O.P.' },
  { jobs: ['Original Music Composer'],                     label: 'Composer' },
]

function CastCard({
  member,
  onPersonClick,
}: {
  member: CastMember
  onPersonClick: (person: PersonDetailTarget) => void
}) {
  return (
    <button
      type="button"
      onClick={() => onPersonClick({ tmdbPersonId: member.tmdbPersonId, name: member.name, profileUrl: member.profileUrl, character: member.character })}
      aria-label={`View details for ${member.name}`}
      className="group shrink-0 w-[110px] overflow-hidden rounded-lg text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 transition-all"
      style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}
    >
      <div className="aspect-[2/3] overflow-hidden">
        {member.profileUrl ? (
          <img
            src={member.profileUrl}
            alt={member.name}
            className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center" style={{ background: 'var(--card)' }}>
            <span className="font-mono text-3xl" style={{ color: 'var(--paper-faint)' }}>
              {member.name.charAt(0).toUpperCase()}
            </span>
          </div>
        )}
      </div>
      <div className="p-2">
        <div
          className="font-sans font-semibold line-clamp-1 transition-colors group-hover:text-amber"
          style={{ fontSize: '12px', color: 'var(--paper)', lineHeight: 1.3 }}
          title={member.name}
        >
          {member.name}
        </div>
        <div
          className="font-mono line-clamp-1 mt-0.5"
          style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: member.character ? 0.6 : 0 }}
          title={member.character}
        >
          {member.character || ' '}
        </div>
        {member.episodeCount != null && (
          <div
            className="font-mono mt-0.5"
            style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: 0.7 }}
          >
            {member.episodeCount} ep{member.episodeCount !== 1 ? 's' : ''}
          </div>
        )}
      </div>
    </button>
  )
}

// Wrapping cast layout: as many members as fit one row show, with a "View All"
// tile in the final slot expanding the rest (no single-axis horizontal scroll).
// Both constants must track the w-[110px] on CastCard/the tile and the gap-2.5
// on the row below.
const CAST_CARD_WIDTH = 110
const CAST_ROW_GAP = 10
// Used until the row has been measured — matches the old fixed count, so the
// first paint is never emptier than it used to be.
const CAST_FALLBACK_COUNT = 5

function CastGrid({
  cast,
  onPersonClick,
}: {
  cast: CastMember[]
  onPersonClick: (person: PersonDetailTarget) => void
}) {
  const [showAll, setShowAll] = useState(false)
  const rowRef = useRef<HTMLDivElement>(null)
  const [perRow, setPerRow] = useState(0)

  // The drawer mounts before the row has a width, so measure the element itself
  // rather than once on mount.
  useEffect(() => {
    const el = rowRef.current
    if (!el) return
    const measure = () => {
      const width = el.clientWidth
      if (width <= 0) return
      setPerRow(Math.max(1, Math.floor((width + CAST_ROW_GAP) / (CAST_CARD_WIDTH + CAST_ROW_GAP))))
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  // Fill the row: if the whole cast fits there is nothing to collapse, otherwise
  // keep the last slot for the "View All" tile so the row stays flush.
  const collapsedCount =
    perRow === 0
      ? CAST_FALLBACK_COUNT
      : cast.length <= perRow
        ? cast.length
        : Math.max(1, perRow - 1)
  const collapsible = cast.length > collapsedCount
  const visible = collapsible && !showAll ? cast.slice(0, collapsedCount) : cast

  return (
    <div ref={rowRef} className="flex flex-wrap gap-2.5">
      {visible.map((member) => (
        <CastCard key={member.tmdbPersonId} member={member} onPersonClick={onPersonClick} />
      ))}
      {collapsible && (
        <button
          type="button"
          onClick={() => setShowAll((v) => !v)}
          aria-expanded={showAll}
          className="shrink-0 w-[110px] rounded-lg flex flex-col items-center justify-center gap-1.5 border border-dashed transition-colors hover:border-amber/40 hover:bg-amber/5 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
          style={{ borderColor: 'var(--line)', minHeight: '120px' }}
        >
          {showAll ? (
            <ChevronUp className="w-4 h-4" style={{ color: 'var(--paper-faint)' }} />
          ) : (
            <Plus className="w-4 h-4" style={{ color: 'var(--paper-faint)' }} />
          )}
          <span className="font-mono text-xs" style={{ color: 'var(--paper-dim)' }}>
            {showAll ? 'Show less' : 'View All'}
          </span>
          {!showAll && (
            <span className="font-mono" style={{ fontSize: '10px', color: 'var(--paper-faint)' }}>
              +{cast.length - collapsedCount} more
            </span>
          )}
        </button>
      )}
    </div>
  )
}

interface CastCrewSectionProps {
  cast?: CastMember[]
  crew?: CrewMember[]
  studios?: string[]
  onPersonClick: (person: PersonDetailTarget) => void
  onStudioClick?: (studio: string) => void
}

export function CastCrewSection({ cast, crew, studios, onPersonClick, onStudioClick }: CastCrewSectionProps) {
  const [expanded, setExpanded] = useState(true)
  const hasCast = cast && cast.length > 0
  const hasCrew = crew && crew.length > 0
  const hasStudios = studios && studios.length > 0
  if (!hasCast && !hasCrew && !hasStudios) return null

  return (
    <div>
      <button
        type="button"
        onClick={() => setExpanded((e) => !e)}
        aria-expanded={expanded}
        className="flex items-center gap-2 mb-4 group focus:outline-none"
      >
        <Eyebrow as="h4" size="xl" tone="dim" font="sans" className="font-semibold group-hover:text-amber transition-colors">
          Cast &amp; Crew
        </Eyebrow>
        <ChevronDown
          className={cn('w-3.5 h-3.5 text-paper-faint transition-transform group-hover:text-amber', expanded ? 'rotate-180' : '')}
        />
      </button>
      {expanded && (
      <div className="space-y-4">
      {hasCast && (
        <div>
          <Eyebrow as="div" className="mb-2">
            Main Cast
          </Eyebrow>
          <CastGrid cast={cast} onPersonClick={onPersonClick} />
        </div>
      )}

      {(hasCrew || hasStudios) && (
        <div className="space-y-1.5">
          {hasCrew && CREW_DISPLAY.map(({ jobs, label }) => {
            const members = crew!.filter((c) => jobs.includes(c.job))
            if (members.length === 0) return null
            return (
              <div key={label} className="flex gap-3" style={{ fontSize: '12px' }}>
                <span
                  className="font-mono shrink-0 text-right"
                  style={{ width: '80px', color: 'var(--paper-faint)', fontSize: '10px', paddingTop: '1px' }}
                >
                  {label}
                </span>
                <span className="font-sans" style={{ color: 'var(--paper)' }}>
                  {members.map((m, i) => (
                    <span key={m.tmdbPersonId}>
                      {i > 0 && ' · '}
                      <button
                        type="button"
                        onClick={() => onPersonClick({ tmdbPersonId: m.tmdbPersonId, name: m.name, profileUrl: m.profileUrl, job: m.job })}
                        aria-label={`View details for ${m.name}`}
                        className="text-paper transition-colors hover:text-amber focus-visible:text-amber focus:outline-none"
                      >
                        {m.name}
                      </button>
                    </span>
                  ))}
                </span>
              </div>
            )
          })}
          {hasStudios && (
            <div className="flex gap-3" style={{ fontSize: '12px' }}>
              <span
                className="font-mono shrink-0 text-right"
                style={{ width: '80px', color: 'var(--paper-faint)', fontSize: '10px', paddingTop: '1px' }}
              >
                Studio
              </span>
              <span className="font-sans" style={{ color: 'var(--paper)' }}>
                {studios!.map((s, i) => (
                  <span key={s}>
                    {i > 0 && ', '}
                    {onStudioClick ? <button
                      type="button"
                      onClick={() => onStudioClick(s)}
                      aria-label={`Browse titles from ${s}`}
                      className="text-paper transition-colors hover:text-amber focus-visible:text-amber focus:outline-none"
                    >
                      {s}
                    </button> : s}
                  </span>
                ))}
              </span>
            </div>
          )}
        </div>
      )}
      </div>
      )}
    </div>
  )
}
