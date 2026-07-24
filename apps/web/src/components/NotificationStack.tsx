import { useState, useEffect, useRef } from 'react'
import { X, RefreshCw, AlertTriangle, Info } from 'lucide-react'
import { useAppStore, type AppNotification } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'

export function NotificationStack() {
  const notifications = useAppStore((s) => s.notifications)
  const dismissNotification = useAppStore((s) => s.dismissNotification)

  // Always render the live region so it exists in the DOM before the first
  // notification enters — screen readers silently drop announcements when the
  // region mounts at the same time as its content.
  return (
    <div
      aria-live="assertive"
      aria-atomic="false"
      className="fixed bottom-20 sm:bottom-6 right-4 sm:right-6 z-[220] flex flex-col gap-2 w-full max-w-xs pointer-events-none"
    >
      {notifications.map((n) => (
        <NotificationCard key={n.id} notification={n} onDismiss={() => dismissNotification(n.id)} />
      ))}
    </div>
  )
}

function NotificationCard({
  notification,
  onDismiss,
}: {
  notification: AppNotification
  onDismiss: () => void
}) {
  const [retrying, setRetrying] = useState(false)
  const [retryFailed, setRetryFailed] = useState(false)
  const isTip = notification.kind === 'tip'

  // Auto-dismiss tips. Use a ref so the effect never re-runs on onDismiss identity changes.
  const onDismissRef = useRef(onDismiss)
  useEffect(() => {
    onDismissRef.current = onDismiss
  }, [onDismiss])

  useEffect(() => {
    if (!notification.autoClose) return
    const id = setTimeout(() => onDismissRef.current(), notification.autoClose)
    return () => clearTimeout(id)
  }, [notification.autoClose])

  async function handleRetry() {
    if (!notification.retry) return
    setRetrying(true)
    setRetryFailed(false)
    try {
      await notification.retry()
      onDismiss()
    } catch {
      setRetryFailed(true)
    } finally {
      setRetrying(false)
    }
  }

  return (
    <div className={cn(
      'pointer-events-auto bg-void rounded-lg p-3 shadow-xl flex items-start gap-3 border',
      isTip ? 'border-paper-faint/20' : 'border-amber/40',
    )}>
      {isTip
        ? <Info className="w-4 h-4 text-paper-faint shrink-0 mt-0.5" />
        : <AlertTriangle className="w-4 h-4 text-amber shrink-0 mt-0.5" />
      }
      <div className="flex-1 min-w-0">
        <p className="font-sans text-xs text-paper leading-normal">{notification.message}</p>
        {retryFailed && (
          <p className="font-sans text-[10px] text-muted-foreground mt-1">Still failing — try again later.</p>
        )}
        {notification.retry && (
          <button
            onClick={handleRetry}
            disabled={retrying}
            className="mt-2 font-sans text-[10px] uppercase tracking-widest text-amber hover:text-amber/70 flex items-center gap-1 disabled:opacity-50 transition-opacity focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm"
          >
            <RefreshCw className={cn('w-3 h-3', retrying && 'animate-spin')} />
            {retrying ? 'Retrying…' : 'Retry'}
          </button>
        )}
      </div>
      <button
        onClick={onDismiss}
        className="text-muted-foreground hover:text-foreground shrink-0 transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm"
        aria-label="Dismiss"
      >
        <X className="w-3.5 h-3.5" />
      </button>
    </div>
  )
}
