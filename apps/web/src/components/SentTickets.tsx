import { useCallback, useEffect, useState } from 'react'
import { isSupabaseConfigured, listFriendships, type FriendshipView } from 'src/lib/auth'
import { isDevMockUser } from 'src/lib/devAuth'
import {
  downloadSentTicket, MAX_TICKET_RECIPIENTS, openReceivedTicket, orderedTickets, receivedTickets, revokeTicketGrant, sendTicket, ticketGrants,
  type OpenedTicket, type ReceivedTicket, type TicketGrant,
} from 'src/lib/ticketSending'
import { useAppStore } from 'src/store/useAppStore'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { Button } from 'src/components/ui/button'
import { Eyebrow } from 'src/components/ui/typography'

const errorMessage = (cause: unknown, fallback: string) => cause instanceof Error && cause.message ? cause.message : fallback
const friendName = (f: FriendshipView) => f.display_name || f.username || 'Unknown user'

/**
 * "Send to a friend" (#220), inside the private ticket viewer: hand the outing's current ticket (the
 * photo and its decoded barcode) to accepted friends. Each row shows whether that friend was sent it
 * and whether they have opened it, and a sent ticket can be taken back. Seats, booking references and
 * notes are not shared.
 */
export function SendTicketPanel({ outingId }: { outingId: string }) {
  const user = useAppStore((s) => s.user)
  const ownerView = useAppStore((s) => !s.isSharedView && s.viewerContext.kind === 'owner')
  const enabled = isSupabaseConfigured && !!user && !isDevMockUser(user) && ownerView
  const [friends, setFriends] = useState<FriendshipView[] | null>(null)
  const [grants, setGrants] = useState<TicketGrant[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string[]>([])

  useEffect(() => {
    if (!enabled) return
    let current = true
    Promise.all([listFriendships(), ticketGrants(outingId)]).then(
      ([rows, list]) => { if (current) { setFriends(rows.filter((f) => f.status === 'accepted')); setGrants(list) } },
      (cause) => { if (current) { setFriends([]); setError(errorMessage(cause, "Couldn't load your friends.")) } },
    )
    return () => { current = false }
  }, [enabled, outingId])

  const act = useCallback(async (friendId: string, fallback: string, work: () => Promise<TicketGrant[]>) => {
    if (busy.includes(friendId)) return
    setBusy((ids) => [...ids, friendId]); setError(null)
    try { setGrants(await work()) }
    catch (cause) { setError(errorMessage(cause, fallback)) }
    finally { setBusy((ids) => ids.filter((id) => id !== friendId)) }
  }, [busy])

  if (!enabled) return null
  return (
    <section aria-label="Send to a friend" className="space-y-2 border-t border-border pt-4 text-left">
      <Eyebrow as="p" size="lg" tone="muted">Send to a friend</Eyebrow>
      <p className="text-xs text-muted-foreground">
        A friend you send it to can open the ticket photo and its code in the app until you take it back. Seats, booking references and notes are not shared. Up to {MAX_TICKET_RECIPIENTS} friends.
      </p>
      {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
      {friends === null ? <p role="status" className="text-xs text-muted-foreground">Loading friends…</p>
        : friends.length === 0 ? <p className="text-xs text-muted-foreground">Add a friend to send them a ticket.</p>
        : <ul className="space-y-1">
          {friends.map((friend) => {
            const grant = grants.find((g) => g.userId === friend.friend_user_id)
            const name = friendName(friend)
            const working = busy.includes(friend.friend_user_id)
            return (
              <li key={friend.friend_user_id} className="flex items-center gap-3 py-1">
                <span className="flex-1 min-w-0 truncate text-sm">
                  {name}
                  {grant && <span className="block text-xs text-amber">{grant.openedAt ? 'Sent · opened' : 'Sent · not opened yet'}</span>}
                </span>
                {grant
                  ? <Button type="button" size="sm" variant="ghost" disabled={working} aria-label={`Take the ticket back from ${name}`}
                      onClick={() => void act(friend.friend_user_id, "Couldn't take the ticket back.", async () => { await revokeTicketGrant(grant.grantId); return ticketGrants(outingId) })}>Take back</Button>
                  : <Button type="button" size="sm" variant="outline" disabled={working || grants.length >= MAX_TICKET_RECIPIENTS} aria-label={`Send the ticket to ${name}`}
                      onClick={() => void act(friend.friend_user_id, "Couldn't send the ticket.", () => sendTicket(outingId, [friend.friend_user_id]))}>{working ? 'Sending…' : 'Send'}</Button>}
              </li>
            )
          })}
        </ul>}
    </section>
  )
}

function whenLabel(ticket: ReceivedTicket): string {
  return [
    new Date(ticket.showtime).toLocaleString(undefined, { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }),
    ticket.venue,
  ].filter(Boolean).join(' · ')
}

function ReceivedTicketViewer({ ticket, onClose }: { ticket: ReceivedTicket; onClose: () => void }) {
  const [opened, setOpened] = useState<OpenedTicket | null>(null)
  const [photo, setPhoto] = useState<string | null>(null)
  const [code, setCode] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let current = true
    const urls: string[] = []
    const url = (blob: Blob) => { const value = URL.createObjectURL(blob); urls.push(value); return value }
    ;(async () => {
      const details = await openReceivedTicket(ticket.grantId)
      if (!current) return
      setOpened(details)
      const blob = await downloadSentTicket(details)
      if (!current) return
      setPhoto(url(blob))
      try {
        const rendered = await (await import('src/lib/tickets/barcode')).renderTicketBarcode(blob, details.barcode)
        if (current && rendered) setCode(url(rendered))
      } catch { /* The photo remains the scanning fallback. */ }
    })().catch((cause) => { if (current) setError(errorMessage(cause, "Couldn't open this ticket. Check your connection.")) })
    return () => { current = false; urls.forEach((value) => URL.revokeObjectURL(value)) }
  }, [ticket.grantId])

  return (
    <CinemaModal open onClose={onClose} title={`Ticket · ${ticket.title}`} description="A ticket a friend sent you.">
      <div className="space-y-3 px-6 py-6 text-left overflow-y-auto">
        <h2 className="font-serif text-xl">{ticket.title}</h2>
        <p className="text-sm text-muted-foreground">From {ticket.senderName} · {whenLabel(ticket)}</p>
        {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        {!opened && !error && <p role="status" className="text-sm">Opening ticket…</p>}
        {photo && <img src={photo} alt="Ticket photo" className="mx-auto block h-auto w-full rounded-md" />}
        {code && <div className="rounded-md bg-white p-3"><img src={code} alt={`Ticket ${opened?.barcode?.format.replaceAll('_', ' ')} barcode`} className="mx-auto block h-auto w-full" /></div>}
        {opened && !opened.barcode && <p className="text-xs text-muted-foreground">No code was read from this ticket; use the photo.</p>}
      </div>
    </CinemaModal>
  )
}

/**
 * Tickets friends sent you (#220). Only for a signed-in owner: it renders nothing in a shared or
 * friend view, without an account, or when there is nothing to show and nothing went wrong.
 */
export function ReceivedTickets() {
  const user = useAppStore((s) => s.user)
  const ownerView = useAppStore((s) => !s.isSharedView && s.viewerContext.kind === 'owner')
  const enabled = isSupabaseConfigured && !!user && !isDevMockUser(user) && ownerView
  const [tickets, setTickets] = useState<ReceivedTicket[]>([])
  const [error, setError] = useState<string | null>(null)
  const [opening, setOpening] = useState<ReceivedTicket | null>(null)
  const [now] = useState(() => Date.now())

  const load = useCallback(() => receivedTickets().then(
    (list) => { setTickets(list); setError(null) },
    (cause) => setError(errorMessage(cause, "Couldn't load tickets from friends.")),
  ), [])
  useEffect(() => {
    if (!enabled) return
    const timer = setTimeout(() => void load(), 0)
    return () => clearTimeout(timer)
  }, [enabled, user?.id, load])

  if (!enabled) return null
  const shown = orderedTickets(tickets, now)
  if (shown.length === 0 && !error) return null
  return (
    <section aria-label="Tickets from friends" className="mb-6 space-y-2">
      <Eyebrow as="h2" size="lg">Tickets from friends</Eyebrow>
      {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
      <ul className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {shown.map((ticket) => (
          <li key={ticket.grantId} className="rounded-xl p-4 space-y-1.5" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
            <p className="font-serif text-base" style={{ color: 'var(--paper)' }}>{[ticket.title, ticket.year].filter(Boolean).join(' · ')}</p>
            <p className="font-mono text-xs" style={{ color: 'var(--amber)' }}>{whenLabel(ticket)}</p>
            <p className="font-mono text-xs" style={{ color: 'var(--amber)' }}>{ticket.senderName} sent you a ticket</p>
            <Button type="button" size="sm" variant="outline" aria-label={`Open the ticket to ${ticket.title}`} onClick={() => setOpening(ticket)}>
              {ticket.openedAt ? 'Open again' : 'Open ticket'}
            </Button>
          </li>
        ))}
      </ul>
      {opening && <ReceivedTicketViewer key={opening.grantId} ticket={opening} onClose={() => { setOpening(null); void load() }} />}
    </section>
  )
}
