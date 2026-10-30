import { useCallback, useEffect, useState } from 'react'
import { isSupabaseConfigured } from 'src/lib/auth'
import { isDevMockUser } from 'src/lib/devAuth'
import {
  canRespond, goingOf, invitationsOf, listSharedOutings, respondToInvite, type SharedOuting,
} from 'src/lib/sharedOutings'
import { useAppStore } from 'src/store/useAppStore'
import { Button } from 'src/components/ui/button'
import { Eyebrow } from 'src/components/ui/typography'

function when(outing: SharedOuting): string {
  return [
    new Date(outing.showtime).toLocaleString(undefined, { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }),
    outing.venue,
    outing.format && outing.format !== 'Standard' ? outing.format : null,
  ].filter(Boolean).join(' · ')
}

function SharedCard({ outing, children }: { outing: SharedOuting; children: React.ReactNode }) {
  return (
    <li className="rounded-xl p-4 space-y-1.5" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
      <p className="font-serif text-base" style={{ color: 'var(--paper)' }}>{[outing.title, outing.year].filter(Boolean).join(' · ')}</p>
      <p className="font-mono text-xs" style={{ color: 'var(--amber)' }}>{when(outing)}</p>
      {outing.companions.length > 0 && <p className="font-sans text-xs" style={{ color: 'var(--paper-faint)' }}>With {outing.companions.join(', ')}</p>}
      {children}
    </li>
  )
}

/**
 * Invitations from friends and outings they are taking you to (#206). Only for a signed-in owner:
 * it renders nothing in a shared or friend view, without an account, or when there is nothing to
 * show and nothing went wrong.
 */
export function SharedWithYou() {
  const user = useAppStore((s) => s.user)
  const ownerView = useAppStore((s) => !s.isSharedView && s.viewerContext.kind === 'owner')
  const enabled = isSupabaseConfigured && !!user && !isDevMockUser(user) && ownerView
  const [outings, setOutings] = useState<SharedOuting[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string[]>([])
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (!enabled) return
    let current = true
    listSharedOutings().then(
      (list) => { if (current) { setOutings(list); setError(null) } },
      (cause) => { if (current) setError(cause instanceof Error ? cause.message : "Couldn't load invitations from friends.") },
    )
    const tick = window.setInterval(() => setNow(Date.now()), 60_000)
    return () => { current = false; window.clearInterval(tick) }
  }, [enabled, user?.id])

  const respond = useCallback(async (id: string, accept: boolean) => {
    if (busy.includes(id)) return
    setBusy((ids) => [...ids, id]); setError(null)
    try {
      await respondToInvite(id, accept)
      setOutings(await listSharedOutings())
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not send your answer. Try again.')
    } finally { setBusy((ids) => ids.filter((value) => value !== id)) }
  }, [busy])

  if (!enabled) return null
  const invitations = invitationsOf(outings, now)
  const going = goingOf(outings)
  if (invitations.length === 0 && going.length === 0 && !error) return null
  return (
    <section aria-label="Shared with you" className="mb-6 space-y-2">
      <Eyebrow as="h2" size="lg">Shared with you</Eyebrow>
      {error && <p role="alert" className="text-xs" style={{ color: 'var(--ember)' }}>{error}</p>}
      <ul className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {invitations.map((outing) => (
          <SharedCard key={outing.id} outing={outing}>
            <p className="font-mono text-xs" style={{ color: 'var(--amber)' }}>{outing.ownerName} invited you</p>
            <div className="flex gap-2 pt-1">
              <Button type="button" size="sm" disabled={busy.includes(outing.id)} aria-label={`Accept invitation to ${outing.title}`}
                onClick={() => void respond(outing.id, true)}>I'm in</Button>
              <Button type="button" size="sm" variant="outline" disabled={busy.includes(outing.id)} aria-label={`Decline invitation to ${outing.title}`}
                onClick={() => void respond(outing.id, false)}>Can't make it</Button>
            </div>
          </SharedCard>
        ))}
        {going.map((outing) => (
          <SharedCard key={outing.id} outing={outing}>
            {outing.status === 'cancelled'
              ? <p className="font-mono text-xs" style={{ color: 'var(--ember)' }}>Cancelled by {outing.ownerName}</p>
              : <>
                <p className="font-mono text-xs" style={{ color: 'var(--amber)' }}>You're going with {outing.ownerName}</p>
                {canRespond(outing, now) && (
                  <Button type="button" size="sm" variant="ghost" disabled={busy.includes(outing.id)} aria-label={`Leave ${outing.title}`}
                    onClick={() => void respond(outing.id, false)}>Can't make it after all</Button>
                )}
              </>}
          </SharedCard>
        ))}
      </ul>
    </section>
  )
}
