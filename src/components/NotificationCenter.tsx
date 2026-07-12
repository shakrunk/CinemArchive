import { useRef, useState } from 'react'
import { Bell, UserPlus, UserCheck, Eye, Send, MessageCircle, Smile, X, Ticket, Clapperboard, CalendarPlus } from 'lucide-react'
import { useShallow } from 'zustand/react/shallow'
import { useAppStore } from 'src/store/useAppStore'
import { findPendingFollowUpOuting, parseOutingSharePayload, formatOutingShareSnapshotLine } from 'src/store/outings'
import { buildOutingIcsFromSharePayload, outingIcsFilename, downloadIcsFile } from 'src/lib/ics'
import { cn, fmtDateShort } from 'src/lib/utils'
import { LoadingRow, EmptyRow } from 'src/components/ui/loading-row'
import { useClickOutside } from 'src/lib/useClickOutside'
import type { AppView } from 'src/lib/navigation'
import type { AppNotificationItem, NotificationType } from 'src/lib/db'

interface TypeMeta {
  Icon: typeof Bell
  verb: (n: AppNotificationItem) => string
  // Overrides the default "{actor} {verb}" line — for self-notifications that
  // carry no actor (plan §4.7's outing_completed: "renders without the actor prefix").
  render?: (n: AppNotificationItem) => React.ReactNode
}

const TYPE_META: Record<NotificationType, TypeMeta> = {
  friend_request_received: { Icon: UserPlus, verb: () => 'sent you a friend request' },
  friend_request_accepted: { Icon: UserCheck, verb: () => 'accepted your friend request' },
  share_link_used: { Icon: Eye, verb: (n) => `viewed your shared link${n.payload.label ? ` "${n.payload.label}"` : ''}` },
  recommendation_received: { Icon: Send, verb: (n) => `sent you "${n.payload.title ?? 'a title'}"` },
  comment_received: { Icon: MessageCircle, verb: (n) => `commented on "${n.title ?? 'a title'}"` },
  reaction_received: { Icon: Smile, verb: (n) => `reacted ${n.payload.emoji ?? ''} to "${n.title ?? 'a title'}"` },
  invite_redeemed: { Icon: Ticket, verb: (n) => `redeemed your invite code${typeof n.payload.email === 'string' ? ` (${n.payload.email})` : ''}` },
  outing_completed: {
    Icon: Clapperboard,
    verb: () => 'just let out — how was it?',
    render: (n) => (
      <>
        <span className="font-medium">{n.title ?? 'Your movie'}</span> just let out — how was it?
      </>
    ),
  },
  outing_plans_shared: {
    Icon: Ticket,
    verb: (n) => `has tickets to "${n.title ?? 'a movie'}"`,
    render: (n) => {
      const payload = parseOutingSharePayload(n.payload)
      if (!payload) return <><span className="font-medium">{actorName(n)}</span> has tickets to share</>
      return (
        <>
          <span className="font-medium">{actorName(n)}</span> has tickets to{' '}
          <span className="font-medium">{payload.title}</span> — {formatOutingShareSnapshotLine(payload)}
        </>
      )
    },
  },
}

function actorName(n: AppNotificationItem): string {
  return n.actorDisplayName || n.actorUsername || 'Someone'
}

interface NotificationCenterProps {
  onNavigate: (view: AppView) => void
}

export function NotificationCenter({ onNavigate }: NotificationCenterProps) {
  const {
    notificationInbox,
    unreadNotificationCount,
    loadNotificationInbox,
    markOneNotificationRead,
    markAllNotificationsSeen,
    deleteNotificationItem,
    openDetailDrawer,
    openPostShowSheet,
    openOutingSchedule,
    resolveSharedOutingTitle,
    outings,
  } = useAppStore(
    useShallow((s) => ({
      notificationInbox: s.notificationInbox,
      unreadNotificationCount: s.unreadNotificationCount,
      loadNotificationInbox: s.loadNotificationInbox,
      markOneNotificationRead: s.markOneNotificationRead,
      markAllNotificationsSeen: s.markAllNotificationsSeen,
      deleteNotificationItem: s.deleteNotificationItem,
      openDetailDrawer: s.openDetailDrawer,
      openPostShowSheet: s.openPostShowSheet,
      openOutingSchedule: s.openOutingSchedule,
      resolveSharedOutingTitle: s.resolveSharedOutingTitle,
      outings: s.outings,
    }))
  )

  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  useClickOutside(containerRef, () => setOpen(false), open, { escape: true })

  async function handleToggle() {
    const next = !open
    setOpen(next)
    if (next) {
      setLoading(notificationInbox.length === 0)
      await loadNotificationInbox()
      setLoading(false)
    }
  }

  async function handleItemClick(n: AppNotificationItem) {
    if (!n.readAt) void markOneNotificationRead(n.id)
    setOpen(false)
    if ((n.type === 'comment_received' || n.type === 'reaction_received') && n.titleId) {
      openDetailDrawer(n.titleId)
    } else if (n.type === 'outing_completed' && n.titleId) {
      openDetailDrawer(n.titleId)
      const outing = findPendingFollowUpOuting(outings, n.titleId, new Date())
      if (outing) openPostShowSheet(outing.id)
    } else if (n.type === 'friend_request_received' || n.type === 'friend_request_accepted' || n.type === 'recommendation_received') {
      onNavigate('friends')
    } else if (n.type === 'invite_redeemed') {
      onNavigate('profile')
    }
  }

  // "I've got tickets too" (plan §4.7/§4.10/rule §5.16): resolves the shared
  // title into the recipient's own library (adding it to the watchlist first
  // if it isn't there yet), then opens their own schedule sheet prefilled
  // with the sender's showtime/venue/format — seat is left for them to fill in.
  function handleGotTicketsToo(n: AppNotificationItem) {
    const payload = parseOutingSharePayload(n.payload)
    if (!payload) return
    if (!n.readAt) void markOneNotificationRead(n.id)
    setOpen(false)
    const titleId = resolveSharedOutingTitle(payload)
    openOutingSchedule(titleId, undefined, { showtime: payload.showtime, venue: payload.venue, format: payload.format })
  }

  // "Add to calendar" on a shared plan (plan §4.10) — built entirely from the
  // notification's snapshot payload, no access to the sender's outing needed.
  function handleAddSharedToCalendar(n: AppNotificationItem) {
    const payload = parseOutingSharePayload(n.payload)
    if (!payload) return
    if (!n.readAt) void markOneNotificationRead(n.id)
    const ics = buildOutingIcsFromSharePayload(payload)
    downloadIcsFile(outingIcsFilename(payload.title, payload.showtime), ics)
  }

  return (
    <div className="relative" ref={containerRef}>
      <button
        onClick={handleToggle}
        aria-label={unreadNotificationCount > 0 ? `Notifications — ${unreadNotificationCount} unread` : 'Notifications'}
        aria-expanded={open}
        className={cn(
          'icon-btn relative w-9 h-9 border rounded-md text-paper-dim hover:text-amber transition-colors flex items-center justify-center',
          open && '!bg-amber/15 border-amber/50 text-amber'
        )}
        style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
      >
        <Bell className="w-[17px] h-[17px]" />
        {unreadNotificationCount > 0 && (
          <span
            className="absolute -top-1 -right-1 min-w-[16px] h-4 px-1 rounded-full bg-amber text-[color:var(--on-amber)] text-[9px] font-mono font-bold flex items-center justify-center"
            aria-hidden="true"
          >
            {unreadNotificationCount > 9 ? '9+' : unreadNotificationCount}
          </span>
        )}
      </button>

      {open && (
        <div
          role="menu"
          aria-label="Notifications"
          className="absolute right-0 mt-2 w-80 max-w-[90vw] rounded-xl overflow-hidden z-[220] shadow-xl"
          style={{ background: 'rgb(var(--ink-1-rgb))', border: '1px solid var(--line)' }}
        >
          <div className="flex items-center justify-between px-4 py-3" style={{ borderBottom: '1px solid var(--line)' }}>
            <span className="font-sans text-xs uppercase tracking-widest text-muted-foreground">Notifications</span>
            {unreadNotificationCount > 0 && (
              <button
                onClick={() => markAllNotificationsSeen()}
                className="font-mono text-[10px] text-amber hover:opacity-80 transition-opacity"
              >
                Mark all read
              </button>
            )}
          </div>

          <div className="max-h-96 overflow-y-auto scrollbar-thin">
            {loading ? (
              <LoadingRow label="Loading..." className="py-6" />
            ) : notificationInbox.length === 0 ? (
              <EmptyRow label="Nothing yet." className="py-6" />
            ) : (
              notificationInbox.map((n) => {
                const meta = TYPE_META[n.type]
                const Icon = meta.Icon
                // Inline CTAs (plan §4.7/§4.10) only render once the payload
                // parses — a malformed/legacy payload just falls back to the
                // plain notification row.
                const sharePayload = n.type === 'outing_plans_shared' ? parseOutingSharePayload(n.payload) : null
                return (
                  <div
                    key={n.id}
                    className="group relative"
                    style={{ background: n.readAt ? 'transparent' : 'rgb(var(--amber-rgb) / 0.06)' }}
                  >
                    <div className="relative flex items-start">
                      <button
                        role="menuitem"
                        onClick={() => handleItemClick(n)}
                        className="w-full text-left flex items-start gap-2.5 pl-4 pr-9 py-3 transition-colors hover:bg-secondary/30"
                      >
                        <Icon className="w-4 h-4 mt-0.5 shrink-0 text-amber" />
                        <div className="min-w-0 flex-1">
                          <p className="font-sans text-xs text-paper leading-snug">
                            {meta.render ? meta.render(n) : (<><span className="font-medium">{actorName(n)}</span> {meta.verb(n)}</>)}
                          </p>
                          <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                            {fmtDateShort(n.createdAt)}
                          </p>
                        </div>
                        {!n.readAt && <span className="w-1.5 h-1.5 rounded-full bg-amber shrink-0 mt-1.5" aria-hidden="true" />}
                      </button>
                      <button
                        onClick={(e) => {
                          e.stopPropagation()
                          void deleteNotificationItem(n.id)
                        }}
                        aria-label="Dismiss notification"
                        className="absolute right-2 top-2.5 w-5 h-5 rounded flex items-center justify-center text-muted-foreground opacity-0 group-hover:opacity-100 hover:text-paper hover:bg-secondary/50 transition-opacity"
                      >
                        <X className="w-3 h-3" />
                      </button>
                    </div>
                    {sharePayload && (
                      <div className="flex gap-3 pl-[34px] pr-4 pb-2.5 -mt-1">
                        <button
                          onClick={() => handleGotTicketsToo(n)}
                          className="flex items-center gap-1 font-mono text-[10px] text-amber hover:opacity-80 transition-opacity"
                        >
                          <Ticket className="w-3 h-3" />
                          I've got tickets too
                        </button>
                        <button
                          onClick={() => handleAddSharedToCalendar(n)}
                          className="flex items-center gap-1 font-mono text-[10px] text-paper-faint hover:text-amber transition-colors"
                        >
                          <CalendarPlus className="w-3 h-3" />
                          Add to calendar
                        </button>
                      </div>
                    )}
                  </div>
                )
              })
            )}
          </div>
        </div>
      )}
    </div>
  )
}
