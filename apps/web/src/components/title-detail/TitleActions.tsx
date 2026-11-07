import { useAppStore } from 'src/store/useAppStore'
import { Trash2, RefreshCw, Send, Ticket } from 'lucide-react'
import type { Title } from 'src/store/mockData'

interface TitleActionsProps {
  title: Title
  confirmingDelete: boolean
  onConfirmingDeleteChange: (confirming: boolean) => void
  onDelete: () => void
  onSendToFriend: () => void
}

/** The drawer's footer row of owner actions, with an inline delete confirmation. */
export function TitleActions({ title, confirmingDelete, onConfirmingDeleteChange, onDelete, onSendToFriend }: TitleActionsProps) {
  const openRefreshMetadata = useAppStore((s) => s.openRefreshMetadata)
  const openOutingSchedule = useAppStore((s) => s.openOutingSchedule)
  const user = useAppStore((s) => s.user)

  return (
    <div
      className="pt-2 border-t"
      style={{ borderColor: 'var(--line)' }}
    >
      {confirmingDelete ? (
        <div className="flex items-center justify-between">
          <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
            Remove from library forever?
          </span>
          <div className="flex items-center gap-3">
            <button type="button"
              onClick={onDelete}
              className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs transition-opacity hover:opacity-80"
              style={{ color: 'var(--ember)' }}
              aria-label="Delete forever: title from library"
            >
              Delete forever
            </button>
            <button type="button"
              onClick={() => onConfirmingDeleteChange(false)}
              className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs transition-opacity hover:opacity-80"
              style={{ color: 'var(--paper-faint)' }}
              aria-label="Cancel deleting title from library"
            >
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          <button type="button"
            onClick={openRefreshMetadata}
            className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-(--line) text-muted-foreground hover:text-amber hover:border-amber/40 hover:bg-amber/5 transition-all focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            Refresh poster &amp; metadata
          </button>
          {title.type === 'movie' && (title.status === 'watched' || title.status === 'dropped') && (
            <button type="button"
              onClick={() => openOutingSchedule(title.id)}
              className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-(--line) text-muted-foreground hover:text-amber hover:border-amber/40 hover:bg-amber/5 transition-all focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
            >
              <Ticket className="w-3.5 h-3.5" />
              Plan a cinema trip
            </button>
          )}
          {user && (
            <button type="button"
              onClick={onSendToFriend}
              className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-(--line) text-muted-foreground hover:text-amber hover:border-amber/40 hover:bg-amber/5 transition-all focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
            >
              <Send className="w-3.5 h-3.5" />
              Send to a friend
            </button>
          )}
          <button type="button"
            onClick={() => onConfirmingDeleteChange(true)}
            className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-(--line) text-muted-foreground hover:text-ember hover:border-ember/30 hover:bg-ember/5 transition-all focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-ember/60"
          >
            <Trash2 className="w-3.5 h-3.5" />
            Remove from library
          </button>
        </div>
      )}
    </div>
  )
}
