import { useCallback, useEffect, useState } from 'react'
import type { FriendshipView } from 'src/lib/auth'
import {
  inviteFriends, MAX_INVITEES, outingMembers, removeOutingMember, type OutingMember,
} from 'src/lib/sharedOutings'

const friendName = (f: FriendshipView) => f.display_name || f.username || 'Unknown user'

/**
 * "Invite to join" (#206): unlike sharing a one-time copy of the plans, an invitation puts the outing
 * on a friend's Up Next once they accept and keeps its time, venue and status current. Only accepted
 * friends can be invited; the server enforces it. The owner sees each friend's answer and can take an
 * invitation back.
 */
export function OutingInvites({ outingId, friends, enabled }: { outingId: string; friends: FriendshipView[]; enabled: boolean }) {
  const [members, setMembers] = useState<OutingMember[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string[]>([])

  useEffect(() => {
    let current = true
    outingMembers(outingId).then(
      (list) => { if (current) setMembers(list) },
      (cause) => { if (current) { setMembers([]); setError(cause instanceof Error ? cause.message : "Couldn't load your invitations.") } },
    )
    return () => { current = false }
  }, [outingId])

  const act = useCallback(async (friendId: string, work: () => Promise<OutingMember[]>) => {
    if (busy.includes(friendId)) return
    setBusy((ids) => [...ids, friendId]); setError(null)
    try { setMembers(await work()) }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Something went wrong. Try again.') }
    finally { setBusy((ids) => ids.filter((id) => id !== friendId)) }
  }, [busy])

  const active = (members ?? []).filter((m) => m.status !== 'declined').length
  return (
    <section aria-label="Invite to join" className="px-5 py-3 space-y-2" style={{ borderTop: '1px solid var(--line)' }}>
      <p className="font-mono uppercase tracking-widest text-[9px]" style={{ color: 'var(--paper-faint)', letterSpacing: '0.14em' }}>Invite to join</p>
      <p className="font-sans text-xs" style={{ color: 'var(--paper-faint)' }}>
        An invited friend sees this outing on their Up Next once they accept, and it stays up to date if you change the time or venue.
        They never see tickets, booking references or private notes.
      </p>
      {error && <p role="alert" className="text-xs" style={{ color: 'var(--ember)' }}>{error}</p>}
      <ul className="space-y-1">
        {friends.map((friend) => {
          const member = (members ?? []).find((m) => m.userId === friend.friend_user_id)
          const invited = !!member && member.status !== 'declined'
          const name = friendName(friend)
          const working = busy.includes(friend.friend_user_id)
          return (
            <li key={friend.friend_user_id} className="flex items-center gap-3 py-1">
              <span className="flex-1 min-w-0 truncate font-sans text-sm" style={{ color: 'var(--paper)' }}>
                {name}
                {member?.status === 'invited' && <span className="block text-xs" style={{ color: 'var(--amber)' }}>Invited · waiting for an answer</span>}
                {member?.status === 'accepted' && <span className="block text-xs" style={{ color: 'var(--amber)' }}>Going</span>}
                {member?.status === 'declined' && <span className="block text-xs" style={{ color: 'var(--paper-faint)' }}>Declined</span>}
              </span>
              {invited ? (
                <button type="button" disabled={working} aria-label={`Remove ${name} from this outing`}
                  onClick={() => void act(friend.friend_user_id, async () => {
                    await removeOutingMember(member.sharedOutingId, friend.friend_user_id)
                    return outingMembers(outingId)
                  })}
                  className="font-mono text-xs transition-colors hover:text-amber disabled:opacity-50" style={{ color: 'var(--paper-dim)' }}>
                  Remove
                </button>
              ) : (
                <button type="button" aria-label={`Invite ${name} to this outing`}
                  disabled={!enabled || members === null || working || active >= MAX_INVITEES}
                  onClick={() => void act(friend.friend_user_id, () => inviteFriends(outingId, [friend.friend_user_id]))}
                  className="font-mono text-xs transition-colors hover:text-amber disabled:opacity-50" style={{ color: 'var(--amber)' }}>
                  {working ? 'Inviting…' : member ? 'Invite again' : 'Invite'}
                </button>
              )}
            </li>
          )
        })}
      </ul>
      {active >= MAX_INVITEES && <p className="text-xs" style={{ color: 'var(--paper-faint)' }}>An outing can invite up to {MAX_INVITEES} friends.</p>}
    </section>
  )
}
