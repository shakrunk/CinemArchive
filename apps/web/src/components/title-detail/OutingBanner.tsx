import { useState } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { Ticket, Clapperboard } from 'lucide-react'
import { formatCompanions, findPendingFollowUpOuting } from 'src/store/outings'
import type { Title } from 'src/store/mockData'
import { ShareOutingPanel } from 'src/components/ShareOutingPanel'

export function OutingBanner({ title }: { title: Title }) {
  const outing = useAppStore((s) => s.outings.find((o) => o.titleId === title.id && o.status === 'scheduled'))
  // Follow-up ("how was it?") banner (plan §4.6) — only surfaced when there's
  // no scheduled outing to show instead; a rewatch mid-follow-up is a rare
  // enough overlap that the scheduled banner simply takes priority.
  const pendingFollowUp = useAppStore((s) =>
    outing ? null : findPendingFollowUpOuting(s.outings, title.id, new Date())
  )
  const openOutingSchedule = useAppStore((s) => s.openOutingSchedule)
  const openPostShowSheet = useAppStore((s) => s.openPostShowSheet)
  const cancelOuting = useAppStore((s) => s.cancelOuting)
  const [confirmingCancel, setConfirmingCancel] = useState(false)
  const [sharePanelOpen, setSharePanelOpen] = useState(false)

  if (!outing && pendingFollowUp) {
    return (
      <div className="px-4 sm:px-6 pt-4 pb-4">
        <button type="button"
          onClick={() => openPostShowSheet(pendingFollowUp.id)}
          className="w-full flex items-center gap-2 rounded-lg px-3 py-2.5 border border-amber/25 bg-amber/6 hover:bg-amber/10 transition-colors text-left focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
        >
          <Clapperboard className="w-3.5 h-3.5 text-amber shrink-0" aria-hidden="true" />
          <span className="font-mono text-xs text-amber">{title.title} just let out — how was it?</span>
        </button>
      </div>
    )
  }

  if (!outing) return null

  const showtime = new Date(outing.showtime)
  const now = new Date().getTime()
  const nowShowing = now >= showtime.getTime() && now < new Date(outing.endsAt).getTime()
  const dateLabel = showtime.toLocaleDateString('en-US', { weekday: 'long', month: 'short', day: 'numeric' })
  const timeLabel = showtime.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true })
  const companionLabel = formatCompanions(outing.companions)
  const summary = [nowShowing ? 'NOW SHOWING' : `${dateLabel} · ${timeLabel}`, outing.venue, companionLabel && `with ${companionLabel}`]
    .filter(Boolean)
    .join(' · ')

  return (
    <div className="px-4 sm:px-6 pt-4 pb-4">
      <div
        className="flex flex-wrap items-center gap-x-2 gap-y-1.5 rounded-lg px-3 py-2.5 border border-amber/25 bg-amber/6"
        aria-label={`Scheduled cinema outing: ${summary}`}
      >
        <Ticket className="w-3.5 h-3.5 text-amber shrink-0" aria-hidden="true" />
        <span className="font-mono text-xs text-amber">{summary}</span>
        {confirmingCancel ? (
          <span className="flex items-center gap-2 ml-auto">
            <span className="font-mono text-xs text-muted-foreground">Cancel these tickets?</span>
            <button type="button" onClick={() => cancelOuting(outing.id)} className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs" style={{ color: 'var(--ember)' }} aria-label="Yes, cancel these tickets">
              Yes
            </button>
            <button type="button" onClick={() => setConfirmingCancel(false)} className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs text-muted-foreground hover:text-foreground transition-colors" aria-label="No, keep these tickets">
              No
            </button>
          </span>
        ) : (
          <span className="flex items-center gap-3 ml-auto">
            <button type="button"
              onClick={() => setSharePanelOpen(true)}
              className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs text-amber/80 hover:text-amber transition-colors"
              aria-label="Share these tickets"
            >
              Share
            </button>
            <button type="button"
              onClick={() => openOutingSchedule(title.id, outing.id)}
              className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs text-amber/80 hover:text-amber transition-colors"
              aria-label="Edit these tickets"
            >
              Edit
            </button>
            <button type="button"
              onClick={() => setConfirmingCancel(true)}
              className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs text-muted-foreground hover:text-ember transition-colors"
              aria-label="Cancel these tickets"
            >
              Cancel
            </button>
          </span>
        )}
      </div>
      {sharePanelOpen && (
        <ShareOutingPanel outing={outing} title={title} onClose={() => setSharePanelOpen(false)} />
      )}
    </div>
  )
}
