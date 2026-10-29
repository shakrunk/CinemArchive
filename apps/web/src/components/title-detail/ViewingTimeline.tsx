import { saveSucceeded } from 'src/lib/localSave'
import { useState, useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { TicketButton } from '../TicketViewer'
import { Plus, Trash2, Pencil } from 'lucide-react'
import { fmtDate } from 'src/lib/utils'
import { formatCompanions } from 'src/store/outings'
import type { Title, Viewing, CinemaOuting } from 'src/store/mockData'
import { ViewingEditForm } from './ViewingEditForm'
import { viewingTime } from './viewingTime'

// Ticket stub line (plan §4.6/§7.4/§13): "at AMC Georgetown · with Alex &
// Sam · IMAX" — format comes from the linked outing (viewings don't carry
// their own format column), so a manually-logged viewing degrades to just
// venue/companions. Degrades further to nothing when neither is present.
function TicketStubLine({ viewing, outing }: { viewing: Viewing; outing: CinemaOuting | undefined }) {
  const companionLabel = formatCompanions(viewing.companions ?? [])
  const segments = [
    viewing.venue && `at ${viewing.venue}`,
    companionLabel && `with ${companionLabel}`,
    outing?.format,
  ].filter((s): s is string => Boolean(s))
  if (segments.length === 0 && !outing) return null

  return (
    <div className="flex flex-wrap items-center gap-2 mt-1.5">
      <span className="ticket-perforation" aria-hidden="true" />
      <span className="font-mono text-xs text-amber/80">{segments.join(' · ')}</span>
      {outing && <TicketButton outingId={outing.id} />}
    </div>
  )
}

export function ViewingTimeline({
  title,
  onDeleteViewing,
  onLogViewing,
  isSharedView,
}: {
  title: Title
  onDeleteViewing?: (viewingId: string) => Promise<void>
  onLogViewing?: () => void
  isSharedView?: boolean
}) {
  const viewings = title.viewings
  const outings = useAppStore((s) => s.outings)
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null)
  const [editingId, setEditingId] = useState<string | null>(null)

  // ⚡ Bolt: Precompute an O(1) Map for outing lookups during the render loop
  const outingsMap = useMemo(() => new Map(outings.map((o) => [o.id, o])), [outings])

  if (viewings.length === 0) {
    return (
      <div className="text-center py-6 text-muted-foreground text-sm font-sans flex flex-col items-center gap-3">
        <div>No viewings logged yet</div>
        {!isSharedView && onLogViewing && (
          <button type="button"
            onClick={onLogViewing}
            aria-label="Log first viewing"
            className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber rounded focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
          >
            <Plus className="w-3.5 h-3.5" />
            Log first viewing
          </button>
        )}
      </div>
    )
  }

  return (
    <div className="relative pl-6">
      <div className="absolute left-2 top-0 bottom-0 w-px bg-border" />
      <div className="space-y-4">
        {viewings
          .slice()
          // undated (pre-platform) viewings sort as oldest → bottom of the timeline
          .sort((a, b) => viewingTime(b) - viewingTime(a))
          .map((v) => {
            const formattedDate = v.date ? fmtDate(v.date) : 'Before CinemArchive'
            const outing = v.outingId ? outingsMap.get(v.outingId) : undefined
            return (
            <div key={v.id} className="relative">
              <div className="absolute left-[-18px] top-1 w-3 h-3 rounded-full bg-amber/70 border-2 border-void" />
              <div className="bg-secondary/50 rounded-lg p-3">
                {pendingDeleteId === v.id ? (
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
                      Remove this viewing?
                    </span>
                    <div className="flex items-center gap-3">
                      <button type="button"
                        onClick={async () => {
                          if (onDeleteViewing && !await saveSucceeded(onDeleteViewing(v.id))) return
                          setPendingDeleteId(null)
                        }}
                        className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs transition-opacity hover:opacity-80"
                        style={{ color: 'var(--ember)' }}
                        aria-label={`Delete forever: viewing from ${formattedDate}`}
                      >
                        Delete forever
                      </button>
                      <button type="button"
                        onClick={() => setPendingDeleteId(null)}
                        className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs transition-opacity hover:opacity-80"
                        style={{ color: 'var(--paper-faint)' }}
                        aria-label={`Cancel deleting viewing from ${formattedDate}`}
                      >
                        Cancel
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="flex items-center justify-between mb-1">
                      <span className="font-mono text-xs text-amber">
                        {v.date
                          ? fmtDate(v.date)
                          : <span className="italic">Before CinemArchive</span>}
                      </span>
                      <div className="flex items-center gap-2">
                        {v.rating && (
                          <span className="font-mono text-xs text-amber">★ {v.rating}</span>
                        )}
                        {!isSharedView && (
                          <button type="button"
                            onClick={() => setEditingId(editingId === v.id ? null : v.id)}
                            style={{ color: 'var(--paper-faint)', opacity: 0.45 }}
                            onMouseEnter={(e) => (e.currentTarget.style.opacity = '1')}
                            onMouseLeave={(e) => (e.currentTarget.style.opacity = '0.45')}
                            aria-label={`Edit viewing from ${formattedDate}`}
                            aria-expanded={editingId === v.id}
                          >
                            <Pencil className="w-3 h-3" />
                          </button>
                        )}
                        {!isSharedView && onDeleteViewing && (
                          <button type="button"
                            onClick={() => setPendingDeleteId(v.id)}
                            style={{ color: 'var(--paper-faint)', opacity: 0.45 }}
                            onMouseEnter={(e) => (e.currentTarget.style.opacity = '1')}
                            onMouseLeave={(e) => (e.currentTarget.style.opacity = '0.45')}
                            aria-label={`Delete viewing from ${formattedDate}`}
                          >
                            <Trash2 className="w-3 h-3" />
                          </button>
                        )}
                      </div>
                    </div>
                    <TicketStubLine viewing={v} outing={outing} />
                    {v.notes && (
                      <p className="text-xs text-muted-foreground font-sans italic leading-relaxed mt-1.5">
                        "{v.notes}"
                      </p>
                    )}
                    {editingId === v.id && (
                      <ViewingEditForm
                        title={title}
                        viewing={v}
                        outing={outing}
                        onClose={() => setEditingId(null)}
                      />
                    )}
                  </>
                )}
              </div>
            </div>
          )})}
      </div>
    </div>
  )
}
