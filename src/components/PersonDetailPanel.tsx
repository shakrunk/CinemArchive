import { useEffect, useRef } from 'react'
import { X, Film, Tv } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import type { CastMember, CrewMember } from 'src/store/mockData'

export interface PersonDetailTarget {
  tmdbPersonId: number
  name: string
  profileUrl?: string
  character?: string
  job?: string
}

interface PersonDetailPanelProps {
  person: PersonDetailTarget
  onClose: () => void
}

export function PersonDetailPanel({ person, onClose }: PersonDetailPanelProps) {
  const titles = useAppStore((s) => s.titles)
  const browseByPerson = useAppStore((s) => s.browseByPerson)
  const closeButtonRef = useRef<HTMLButtonElement>(null)

  const onCloseRef = useRef(onClose)
  useEffect(() => { onCloseRef.current = onClose })

  useEffect(() => {
    const returnEl = document.activeElement as HTMLElement
    closeButtonRef.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onCloseRef.current() }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      returnEl?.focus()
    }
  }, [])

  const personTitles = titles.filter((t) => {
    if (t.cast?.some((m: CastMember) => m.tmdbPersonId === person.tmdbPersonId)) return true
    if (t.crew?.some((m: CrewMember) => m.tmdbPersonId === person.tmdbPersonId)) return true
    if (t.seasons?.some((s) => s.cast?.some((m: CastMember) => m.tmdbPersonId === person.tmdbPersonId))) return true
    return false
  })

  function handleBrowse() {
    onClose()
    browseByPerson({ id: person.tmdbPersonId, name: person.name })
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`${person.name} details`}
      className="fixed inset-0 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.82)', zIndex: 60 }}
      onClick={onClose}
    >
      <div
        className="relative w-full max-w-sm rounded-xl overflow-hidden"
        style={{ background: 'rgb(var(--ink-1-rgb))', border: '1px solid var(--line)' }}
        onClick={(e) => e.stopPropagation()}
      >
        <button
          ref={closeButtonRef}
          onClick={onClose}
          className="absolute top-3 right-3 flex items-center justify-center w-7 h-7 rounded-full transition-colors"
          style={{ color: 'var(--paper-faint)' }}
          aria-label={`Close ${person.name} details`}
          onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--paper)')}
          onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--paper-faint)')}
        >
          <X className="w-4 h-4" />
        </button>

        {/* Person identity */}
        <div className="flex gap-4 px-5 pt-5 pb-4">
          <div
            className="w-20 h-20 rounded-full overflow-hidden shrink-0 flex items-center justify-center"
            style={{ background: 'var(--inset-strong)', border: '1px solid var(--line)' }}
          >
            {person.profileUrl ? (
              <img src={person.profileUrl} alt={person.name} className="w-full h-full object-cover" />
            ) : (
              <span className="font-serif text-2xl" style={{ color: 'var(--paper-faint)' }}>
                {person.name.charAt(0).toUpperCase()}
              </span>
            )}
          </div>
          <div className="flex-1 min-w-0 pt-2">
            <div className="font-serif text-lg leading-tight" style={{ color: 'var(--paper)' }}>
              {person.name}
            </div>
            {person.character && (
              <div className="font-sans mt-1.5" style={{ fontSize: '12px', color: 'var(--paper-faint)' }}>
                as {person.character}
              </div>
            )}
            {person.job && !person.character && (
              <div className="font-mono mt-1.5" style={{ fontSize: '11px', color: 'var(--paper-faint)' }}>
                {person.job}
              </div>
            )}
          </div>
        </div>

        {/* Library titles */}
        {personTitles.length > 0 && (
          <div style={{ borderTop: '1px solid var(--line)' }}>
            <div
              className="px-5 pt-3 pb-1 font-mono"
              style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
            >
              In your library ({personTitles.length})
            </div>
            <div className="px-4 pb-3 max-h-48 overflow-y-auto scrollbar-thin">
              {personTitles.map((t) => (
                <div key={t.id} className="flex items-center gap-2 px-1 py-1.5">
                  {t.type === 'movie' ? (
                    <Film className="w-3 h-3 shrink-0" style={{ color: 'var(--paper-faint)' }} />
                  ) : (
                    <Tv className="w-3 h-3 shrink-0" style={{ color: 'var(--paper-faint)' }} />
                  )}
                  <span
                    className="font-sans flex-1 min-w-0 truncate"
                    style={{ fontSize: '13px', color: 'var(--paper-dim)' }}
                  >
                    {t.title}
                  </span>
                  <span
                    className="font-mono shrink-0"
                    style={{ fontSize: '10px', color: 'var(--paper-faint)' }}
                  >
                    {t.year}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Browse action */}
        <div className="px-5 py-4" style={{ borderTop: '1px solid var(--line)' }}>
          <button
            onClick={handleBrowse}
            className="w-full py-2 rounded-lg font-mono text-sm transition-colors"
            style={{
              background: 'rgba(233,178,102,0.08)',
              border: '1px solid rgba(233,178,102,0.2)',
              color: 'var(--amber)',
            }}
            onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(233,178,102,0.14)')}
            onMouseLeave={(e) => (e.currentTarget.style.background = 'rgba(233,178,102,0.08)')}
          >
            Browse library — {person.name}
          </button>
        </div>
      </div>
    </div>
  )
}
