import { useEffect, useMemo, useRef, useState } from 'react'
import { useAppStore } from '../store/useAppStore'

export const LIBRARY_SYNC_TOAST_KEY = 'library-sync'
/** Online edits normally deliver within moments; only surface pending work that lingers. */
export const PENDING_GRACE_MS = 5000
const SYNCED_AUTO_CLOSE_MS = 4000

interface SyncSummary {
  message: string
  /** Needs the owner's review (failed/conflicting changes, damaged cache, sync outage). */
  attention: boolean
  count: number
}

function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? '' : 's'}`
}

function summarize(commands: number, stuck: number, quarantined: number, syncError: string | null): SyncSummary | null {
  if (quarantined) return { message: 'Damaged library data was preserved on this device. Review it before saving more changes.', attention: true, count: commands }
  if (stuck) return { message: `${plural(stuck, 'saved change')} could not sync and ${stuck === 1 ? 'needs' : 'need'} review.`, attention: true, count: commands }
  if (commands && syncError) return { message: `Library sync is unavailable. ${plural(commands, 'change')} saved on this device will sync later.`, attention: true, count: commands }
  if (commands) return { message: `${plural(commands, 'change')} saved on this device, waiting to sync.`, attention: false, count: commands }
  if (syncError) return { message: `Library sync is unavailable: ${syncError}`, attention: true, count: 0 }
  return null
}

function withdrawToast(id: string): void {
  // Programmatic removal: unlike dismissNotification, this must not count as the owner dismissing it.
  useAppStore.setState((s) => ({ notifications: s.notifications.filter((n) => n.id !== id) }))
}

/**
 * Surfaces unsynced local library work through the toast stack. Renders
 * nothing itself: it keeps one deduplicated toast (LIBRARY_SYNC_TOAST_KEY) in
 * step with the offline journal, with a "Review" action that opens
 * LibrarySyncDialog. A toast the owner dismisses stays dismissed until the
 * situation escalates (more pending changes, or a change needing review).
 */
export function LibrarySyncToast() {
  const shared = useAppStore((s) => s.isSharedView)
  const ownerId = useAppStore((s) => s.offlineStatus.ownerId)
  const commands = useAppStore((s) => s.offlineStatus.commands.length)
  const stuck = useAppStore((s) => s.offlineStatus.commands.filter((c) => c.state !== 'pending').length)
  const quarantined = useAppStore((s) => s.offlineStatus.quarantined.length)
  const syncError = useAppStore((s) => s.offlineSyncError)
  const toast = useAppStore((s) => s.notifications.find((n) => n.dedupeKey === LIBRARY_SYNC_TOAST_KEY))
  const summary = useMemo(() => shared ? null : summarize(commands, stuck, quarantined, syncError),
    [shared, commands, stuck, quarantined, syncError])

  const pendingOnly = !!summary && !summary.attention
  const [graceElapsed, setGraceElapsed] = useState(false)
  if (!pendingOnly && graceElapsed) setGraceElapsed(false)
  useEffect(() => {
    if (!pendingOnly) return
    const id = setTimeout(() => setGraceElapsed(true), PENDING_GRACE_MS)
    return () => clearTimeout(id)
  }, [pendingOnly])

  const shown = useRef<SyncSummary | null>(null)
  const dismissed = useRef<SyncSummary | null>(null)
  const owner = useRef(ownerId)

  useEffect(() => {
    const store = useAppStore.getState()
    const ownerChanged = owner.current !== ownerId
    owner.current = ownerId
    // Once a toast is up, it tracks pending-only states without waiting out the grace period again.
    const visible = summary && (summary.attention || graceElapsed || shown.current) ? summary : null

    if (!visible) {
      if (summary) return
      dismissed.current = null
      const wasShowing = !!shown.current && !!toast && !toast.autoClose
      shown.current = null
      if (wasShowing && !shared && !ownerChanged) {
        // Neutral wording: the queue also empties when the owner discards changes from the dialog.
        store.pushNotification({ dedupeKey: LIBRARY_SYNC_TOAST_KEY, kind: 'tip', autoClose: SYNCED_AUTO_CLOSE_MS,
          message: 'Nothing on this device is waiting to sync.' })
      } else if (toast && !toast.autoClose) {
        withdrawToast(toast.id)
      }
      return
    }

    shown.current = visible
    const previous = dismissed.current
    if (previous && !(visible.attention && !previous.attention) && visible.count <= previous.count) return
    dismissed.current = null
    if (toast && !toast.autoClose && toast.message === visible.message) return
    store.pushNotification({
      dedupeKey: LIBRARY_SYNC_TOAST_KEY,
      kind: visible.attention ? 'error' : 'tip',
      message: visible.message,
      action: { label: 'Review', run: () => useAppStore.getState().openLibrarySync() },
      onDismiss: () => { dismissed.current = shown.current },
    })
  }, [summary, graceElapsed, toast, shared, ownerId])

  return null
}
