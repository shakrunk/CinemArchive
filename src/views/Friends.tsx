import { useState, useEffect } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { Users, UserPlus, Check, Trash2, Eye, Ban, ShieldOff, Inbox, X, Activity, Star, Loader2 } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { isSupabaseConfigured } from 'src/lib/auth'
import {
  findUserByEmail,
  sendFriendRequest,
  acceptFriendRequest,
  declineFriendRequest,
  blockFriend,
  unblockFriend,
  listFriendships,
  type FriendshipView,
} from 'src/lib/auth'
import {
  fetchRecommendations, markRecommendationRead, dismissRecommendation, type Recommendation,
  fetchFriendActivityFeed, type ActivityEvent,
} from 'src/lib/db'

// ─── Shared bits ──────────────────────────────────────────────────────────────

interface Message {
  type: 'success' | 'error'
  text: string
}

function MessageBanner({ message }: { message: Message | null }) {
  if (!message) return null
  return (
    <div
      className={cn(
        'p-3 rounded-lg text-xs font-sans leading-normal border',
        message.type === 'success'
          ? 'bg-amber/10 border-amber/30 text-amber'
          : 'bg-destructive/10 border-destructive/30 text-destructive'
      )}
    >
      {message.text}
    </div>
  )
}

/** Section shell: uniform heading/description/card framing (no anchor-scroll needed here — this is its own page, not a settings scroll list). */
function Section({
  title,
  Icon,
  description,
  children,
}: {
  title: string
  Icon: typeof Users
  description?: string
  children: React.ReactNode
}) {
  return (
    <section>
      <h2 className="font-sans text-xs uppercase tracking-widest text-muted-foreground flex items-center gap-1.5 mb-2">
        <Icon className="w-3.5 h-3.5 text-amber" />
        {title}
      </h2>
      {description && (
        <p className="font-sans text-xs text-muted-foreground leading-relaxed mb-3 max-w-[60ch]">
          {description}
        </p>
      )}
      <div
        className="rounded-xl border p-4 sm:p-5 space-y-4"
        style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
      >
        {children}
      </div>
    </section>
  )
}

// ─── Friends ──────────────────────────────────────────────────────────────────

function FriendsSection() {
  const user = useAppStore((s) => s.user)
  const loadFriendLibrary = useAppStore((s) => s.loadFriendLibrary)

  const [friendEmail, setFriendEmail] = useState('')
  const [sending, setSending] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  const [friendships, setFriendships] = useState<FriendshipView[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    void loadFriendships()
  }, [])

  async function loadFriendships() {
    setLoading(true)
    try {
      const list = await listFriendships()
      setFriendships(list)
    } catch (err) {
      console.error('Failed to load friendships:', err)
    } finally {
      setLoading(false)
    }
  }

  async function handleSendRequest(e: React.FormEvent) {
    e.preventDefault()
    if (!friendEmail.trim()) return
    setSending(true)
    setMessage(null)
    try {
      const found = await findUserByEmail(friendEmail)
      if (!found) {
        setMessage({ type: 'error', text: 'No user found with that email.' })
        return
      }
      await sendFriendRequest(found.user_id)
      setFriendEmail('')
      setMessage({ type: 'success', text: 'Friend request sent.' })
      await loadFriendships()
    } catch (err: any) {
      console.error(err)
      setMessage({ type: 'error', text: err.message || 'Failed to send friend request.' })
    } finally {
      setSending(false)
    }
  }

  async function handleAccept(requesterId: string) {
    try {
      await acceptFriendRequest(requesterId)
      await loadFriendships()
    } catch (err) {
      console.error('Failed to accept friend request:', err)
    }
  }

  async function handleDecline(requesterId: string) {
    try {
      await declineFriendRequest(requesterId)
      await loadFriendships()
    } catch (err) {
      console.error('Failed to decline friend request:', err)
    }
  }

  async function handleBlock(targetId: string) {
    if (!confirm('Block this user? They will no longer be able to send you friend requests or view your library.')) return
    try {
      await blockFriend(targetId)
      await loadFriendships()
    } catch (err) {
      console.error('Failed to block user:', err)
    }
  }

  async function handleUnblock(targetId: string) {
    try {
      await unblockFriend(targetId)
      await loadFriendships()
    } catch (err) {
      console.error('Failed to unblock user:', err)
    }
  }

  return (
    <Section
      title="Friends"
      Icon={Users}
      description="Add a friend by email to share your ledger and send them recommendations."
    >
      <form onSubmit={handleSendRequest} className="flex gap-2 max-w-md">
        <Input
          aria-label="Friend's email"
          type="email"
          placeholder="friend@example.com"
          value={friendEmail}
          onChange={(e) => setFriendEmail(e.target.value)}
          className="bg-secondary/50 border-border text-xs"
        />
        <Button
          type="submit"
          disabled={sending}
          className="bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] shrink-0"
          aria-label="Send friend request"
        >
          {sending ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <UserPlus className="w-3.5 h-3.5" />}
        </Button>
      </form>

      <MessageBanner message={message} />

      <div className="space-y-2">
        {loading ? (
          <div className="text-center py-4 text-xs font-mono text-muted-foreground">Loading friends...</div>
        ) : friendships.length === 0 ? (
          <div className="text-center py-4 text-xs font-sans text-muted-foreground italic">No friends yet.</div>
        ) : (
          friendships.map((f) => (
            <div key={f.friend_user_id} className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="font-sans text-xs text-paper font-medium truncate">
                  {f.display_name || f.username || 'Unknown user'}
                </p>
                <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                  {f.status === 'pending' && f.requested_by === user?.id && 'Request sent'}
                  {f.status === 'pending' && f.requested_by !== user?.id && 'Wants to be friends'}
                  {f.status === 'accepted' && 'Friends'}
                  {f.status === 'blocked' && (f.blocked_by === user?.id ? 'Blocked' : 'Unavailable')}
                </p>
              </div>
              <div className="flex gap-1 shrink-0">
                {f.status === 'pending' && f.requested_by !== user?.id && (
                  <>
                    <Button
                      size="sm"
                      onClick={() => handleAccept(f.friend_user_id)}
                      className="bg-secondary hover:bg-amber/20 hover:text-amber text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="Accept"
                      aria-label="Accept friend request"
                    >
                      <Check className="w-3.5 h-3.5" />
                    </Button>
                    <Button
                      size="sm"
                      onClick={() => handleDecline(f.friend_user_id)}
                      className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="Decline"
                      aria-label="Decline friend request"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </Button>
                  </>
                )}
                {f.status === 'accepted' && (
                  <>
                    <Button
                      size="sm"
                      onClick={() => void loadFriendLibrary(f.friend_user_id, f.display_name || f.username || 'Friend')}
                      className="bg-secondary hover:bg-amber/20 hover:text-amber text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="View Library"
                      aria-label={`View ${f.display_name || f.username || 'friend'}'s library`}
                    >
                      <Eye className="w-3.5 h-3.5" />
                    </Button>
                    <Button
                      size="sm"
                      onClick={() => handleBlock(f.friend_user_id)}
                      className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="Block"
                      aria-label="Block user"
                    >
                      <Ban className="w-3.5 h-3.5" />
                    </Button>
                  </>
                )}
                {f.status === 'blocked' && f.blocked_by === user?.id && (
                  <Button
                    size="sm"
                    onClick={() => handleUnblock(f.friend_user_id)}
                    className="bg-secondary hover:bg-amber/20 hover:text-amber text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                    title="Unblock"
                    aria-label="Unblock user"
                  >
                    <ShieldOff className="w-3.5 h-3.5" />
                  </Button>
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </Section>
  )
}

// ─── Recommendations inbox ────────────────────────────────────────────────────

function InboxSection() {
  const { titles, openDetailDrawer, openAddTitlePreselected } = useAppStore(
    useShallow((s) => ({
      titles: s.titles,
      openDetailDrawer: s.openDetailDrawer,
      openAddTitlePreselected: s.openAddTitlePreselected,
    }))
  )
  const [recommendations, setRecommendations] = useState<Recommendation[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    void load()
  }, [])

  async function load() {
    setLoading(true)
    try {
      const list = await fetchRecommendations()
      setRecommendations(list)
    } catch (err) {
      console.error('Failed to load recommendations:', err)
    } finally {
      setLoading(false)
    }
  }

  async function handleOpen(rec: Recommendation) {
    if (rec.status === 'unread') {
      setRecommendations((prev) => prev.map((r) => (r.id === rec.id ? { ...r, status: 'read' } : r)))
      try {
        await markRecommendationRead(rec.id)
      } catch (err) {
        console.error('Failed to mark recommendation read:', err)
      }
    }

    const owned = titles.find((t) => t.tmdbId === rec.tmdbId && t.type === rec.type)
    if (owned) {
      openDetailDrawer(owned.id)
    } else {
      openAddTitlePreselected({
        tmdbId: rec.tmdbId,
        type: rec.type,
        title: rec.title,
        year: rec.year ?? 0,
        posterUrl: rec.posterUrl ?? undefined,
        genres: [],
      })
    }
  }

  async function handleDismiss(id: string) {
    setRecommendations((prev) => prev.filter((r) => r.id !== id))
    try {
      await dismissRecommendation(id)
    } catch (err) {
      console.error('Failed to dismiss recommendation:', err)
      await load()
    }
  }

  const unreadCount = recommendations.filter((r) => r.status === 'unread').length

  return (
    <Section
      title="Recommendations"
      Icon={Inbox}
      description={unreadCount > 0 ? `${unreadCount} new recommendation${unreadCount !== 1 ? 's' : ''} from friends.` : 'Titles friends have sent your way.'}
    >
      <div className="space-y-2">
        {loading ? (
          <div className="text-center py-4 text-xs font-mono text-muted-foreground">Loading recommendations...</div>
        ) : recommendations.length === 0 ? (
          <div className="text-center py-4 text-xs font-sans text-muted-foreground italic">Nothing sent your way yet.</div>
        ) : (
          recommendations.map((r) => {
            const owned = titles.some((t) => t.tmdbId === r.tmdbId && t.type === r.type)
            return (
              <div
                key={r.id}
                role="button"
                tabIndex={0}
                onClick={() => handleOpen(r)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    handleOpen(r)
                  }
                }}
                className="bg-secondary/20 hover:bg-secondary/40 rounded-lg p-3 border border-border flex items-center gap-3 cursor-pointer transition-colors focus:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
              >
                {r.posterUrl && (
                  <img src={r.posterUrl} alt="" className="w-8 h-12 object-cover rounded shrink-0" />
                )}
                <div className="min-w-0 flex-1">
                  <p className="font-sans text-xs text-paper font-medium truncate flex items-center gap-1.5">
                    {r.status === 'unread' && <span className="w-1.5 h-1.5 rounded-full bg-amber shrink-0" />}
                    {r.title}
                    {r.year ? ` (${r.year})` : ''}
                  </p>
                  <p className="font-mono text-[9px] text-muted-foreground mt-0.5 truncate">
                    from {r.senderDisplayName || r.senderUsername || 'a friend'}
                    {' · '}
                    {owned ? 'in your library' : 'tap to add'}
                  </p>
                  {r.note && (
                    <p className="font-sans text-[11px] text-muted-foreground italic mt-1 line-clamp-2">
                      "{r.note}"
                    </p>
                  )}
                </div>
                <Button
                  size="sm"
                  onClick={(e) => {
                    e.stopPropagation()
                    handleDismiss(r.id)
                  }}
                  className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center shrink-0"
                  title="Dismiss"
                  aria-label="Dismiss recommendation"
                >
                  <X className="w-3.5 h-3.5" />
                </Button>
              </div>
            )
          })
        )}
      </div>
    </Section>
  )
}

// ─── Friend activity ──────────────────────────────────────────────────────────

function ActivitySection() {
  const markActivityFeedSeen = useAppStore((s) => s.markActivityFeedSeen)
  const [feed, setFeed] = useState<ActivityEvent[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    fetchFriendActivityFeed()
      .then((f) => {
        if (cancelled) return
        setFeed(f)
        markActivityFeedSeen()
      })
      .catch((err) => console.error('Failed to load friend activity feed:', err))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <Section title="Friend Activity" Icon={Activity} description="What your friends have been adding and watching lately.">
      <div className="space-y-2">
        {loading ? (
          <div className="text-center py-4 text-xs font-mono text-muted-foreground">Loading activity...</div>
        ) : feed.length === 0 ? (
          <div className="text-center py-4 text-xs font-sans text-muted-foreground italic">No friend activity yet.</div>
        ) : (
          feed.map((e) => (
            <div
              key={`${e.type}:${e.titleId}:${e.eventAt}`}
              className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center gap-3"
            >
              {e.posterUrl && <img src={e.posterUrl} alt="" className="w-8 h-12 object-cover rounded shrink-0" />}
              <div className="min-w-0 flex-1">
                <p className="font-sans text-xs text-paper truncate">
                  <span className="font-medium">{e.friendDisplayName || e.friendUsername || 'A friend'}</span>{' '}
                  {e.type === 'title_added' ? 'added' : 'watched'}{' '}
                  <span className="font-medium">
                    {e.title}
                    {e.year ? ` (${e.year})` : ''}
                  </span>
                  {e.type === 'viewing_logged' && e.rating != null && (
                    <span className="inline-flex items-center gap-0.5 ml-1.5 text-amber">
                      <Star className="w-3 h-3 fill-current" />
                      {e.rating}
                    </span>
                  )}
                </p>
                <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                  {new Date(e.eventAt).toLocaleDateString()}
                </p>
              </div>
            </div>
          ))
        )}
      </div>
    </Section>
  )
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export function Friends() {
  const user = useAppStore((s) => s.user)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const authed = Boolean(user) && isSupabaseConfigured && !isSharedView

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <div className="mb-[clamp(24px,3.5vw,40px)]">
        <p className="kicker">
          <span className="dot" /> the screening circle
        </p>
        <h1 className="display-title text-[clamp(36px,6.5vw,72px)] mt-3.5">
          Friends &amp; <em>Activity.</em>
        </h1>
        <p className="mt-4 max-w-[60ch] text-[clamp(15px,1.6vw,18px)] text-paper-dim">
          Who gets a seat in your screening room, what they've sent you, and what they've been watching.
        </p>
      </div>

      {authed ? (
        <div className="max-w-[70ch] space-y-10 pb-16">
          <FriendsSection />
          <InboxSection />
          <ActivitySection />
        </div>
      ) : (
        <p className="font-sans text-xs text-muted-foreground">
          Sign in to add friends, send recommendations, and see what they've been watching.
        </p>
      )}
    </div>
  )
}
