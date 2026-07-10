import { useRef, useState } from 'react'
import { Bell, UserPlus, UserCheck, Eye, Send, MessageCircle, Smile, X, Ticket } from 'lucide-react'
import { useShallow } from 'zustand/react/shallow'
import { useAppStore } from 'src/store/useAppStore'
import { cn, fmtDateShort } from 'src/lib/utils'
import { LoadingRow, EmptyRow } from 'src/components/ui/loading-row'
import { useClickOutside } from 'src/lib/useClickOutside'
import type { AppView } from 'src/lib/navigation'
import type { AppNotificationItem, NotificationType } from 'src/lib/db'

const TYPE_META: Record<NotificationType, { Icon: typeof Bell; verb: (n: AppNotificationItem) => string }> = {
  friend_request_received: { Icon: UserPlus, verb: () => 'sent you a friend request' },
  friend_request_accepted: { Icon: UserCheck, verb: () => 'accepted your friend request' },
  share_link_used: { Icon: Eye, verb: (n) => `viewed your shared link${n.payload.label ? ` "${n.payload.label}"` : ''}` },
  recommendation_received: { Icon: Send, verb: (n) => `sent you "${n.payload.title ?? 'a title'}"` },
  comment_received: { Icon: MessageCircle, verb: (n) => `commented on "${n.title ?? 'a title'}"` },
  reaction_received: { Icon: Smile, verb: (n) => `reacted ${n.payload.emoji ?? ''} to "${n.title ?? 'a title'}"` },
  invite_redeemed: { Icon: Ticket, verb: (n) => `redeemed your invite code${typeof n.payload.email === 'string' ? ` (${n.payload.email})` : ''}` },
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
  } = useAppStore(
    useShallow((s) => ({
      notificationInbox: s.notificationInbox,
      unreadNotificationCount: s.unreadNotificationCount,
      loadNotificationInbox: s.loadNotificationInbox,
      markOneNotificationRead: s.markOneNotificationRead,
      markAllNotificationsSeen: s.markAllNotificationsSeen,
      deleteNotificationItem: s.deleteNotificationItem,
      openDetailDrawer: s.openDetailDrawer,
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
    } else if (n.type === 'friend_request_received' || n.type === 'friend_request_accepted' || n.type === 'recommendation_received') {
      onNavigate('friends')
    } else if (n.type === 'invite_redeemed') {
      onNavigate('profile')
    }
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
                return (
                  <div
                    key={n.id}
                    className="group relative flex items-start"
                    style={{ background: n.readAt ? 'transparent' : 'rgb(var(--amber-rgb) / 0.06)' }}
                  >
                    <button
                      role="menuitem"
                      onClick={() => handleItemClick(n)}
                      className="w-full text-left flex items-start gap-2.5 pl-4 pr-9 py-3 transition-colors hover:bg-secondary/30"
                    >
                      <Icon className="w-4 h-4 mt-0.5 shrink-0 text-amber" />
                      <div className="min-w-0 flex-1">
                        <p className="font-sans text-xs text-paper leading-snug">
                          <span className="font-medium">{actorName(n)}</span> {meta.verb(n)}
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
                )
              })
            )}
          </div>
        </div>
      )}
    </div>
  )
}
