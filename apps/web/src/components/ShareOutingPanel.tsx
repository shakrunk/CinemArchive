import { useEffect, useState } from 'react'
import { Send, Check, Loader2, Search, RefreshCw, Share2, Download, Ticket } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { useModalFocusAndEscape } from 'src/lib/useModalFocusAndEscape'
import { ModalBackdrop } from 'src/components/ui/modal-backdrop'
import { ModalCloseButton } from 'src/components/ui/modal-close-button'
import { listFriendships, type FriendshipView } from 'src/lib/auth'
import { formatCompanions } from 'src/store/outings'
import { buildOutingIcs, outingIcsFilename, downloadIcsFile, formatOutingShareSnippet, shareOutingSnippet } from 'src/lib/ics'
import type { CinemaOuting, Title } from 'src/store/mockData'

// One panel for both plan-sharing channels (plan §4.10): the in-app friend
// picker (modeled on SendRecommendationPanel) plus the out-of-app copy/
// system-share snippet + .ics already built in Phase C. Reused from every
// entry point — marquee overflow, drawer banner, schedule-form confirmation.

interface ShareOutingPanelProps {
  outing: CinemaOuting
  title: Title
  onClose: () => void
}

type ShareState = 'idle' | 'sending' | 'sent' | 'error'

function friendName(f: FriendshipView): string {
  return f.display_name || f.username || 'Unknown user'
}

export function ShareOutingPanel({ outing, title, onClose }: ShareOutingPanelProps) {
  const shareOutingPlans = useAppStore((s) => s.shareOutingPlans)
  const pushNotification = useAppStore((s) => s.pushNotification)
  const closeButtonRef = useModalFocusAndEscape<HTMLButtonElement>(onClose)

  const [friends, setFriends] = useState<FriendshipView[]>([])
  const [loadingFriends, setLoadingFriends] = useState(true)
  const [friendsError, setFriendsError] = useState(false)
  const [search, setSearch] = useState('')
  const [shareState, setShareState] = useState<Record<string, ShareState>>({})
  const [copying, setCopying] = useState(false)

  async function loadFriends() {
    setLoadingFriends(true)
    setFriendsError(false)
    try {
      const friendList = await listFriendships()
      setFriends(friendList.filter((f) => f.status === 'accepted'))
    } catch (err) {
      console.error('Failed to load friends for outing share:', err)
      setFriendsError(true)
    } finally {
      setLoadingFriends(false)
    }
  }

  useEffect(() => {
    // Deferred to a macrotask so the initial setLoadingFriends(true) doesn't
    // fire synchronously within the effect body (react-hooks/set-state-in-effect) —
    // same idiom as SendRecommendationPanel's friend fetch.
    const t = setTimeout(() => loadFriends(), 0)
    return () => clearTimeout(t)
  }, [])

  async function handleShareToFriend(friend: FriendshipView) {
    if (shareState[friend.friend_user_id] === 'sending') return
    const name = friendName(friend)
    setShareState((s) => ({ ...s, [friend.friend_user_id]: 'sending' }))
    try {
      await shareOutingPlans(outing.id, [friend.friend_user_id])
      setShareState((s) => ({ ...s, [friend.friend_user_id]: 'sent' }))
      pushNotification({ message: `Shared your plans with ${name}.`, kind: 'tip', autoClose: 4000 })
    } catch {
      // shareOutingPlans already pushed its own error toast — just reflect
      // the per-row failure state here.
      setShareState((s) => ({ ...s, [friend.friend_user_id]: 'error' }))
    }
  }

  async function handleCopyOrShare() {
    setCopying(true)
    try {
      const snippet = formatOutingShareSnippet(title.title, outing.showtime, outing.venue, outing.format, outing.seat)
      const outcome = await shareOutingSnippet(snippet)
      if (outcome === 'copied') {
        pushNotification({ message: "Copied — paste it wherever you're texting your friends.", kind: 'tip', autoClose: 4000 })
      }
    } catch (err) {
      console.error('Failed to share outing plans out-of-app:', err)
      pushNotification({ message: "Couldn't share your plans — check your connection." })
    } finally {
      setCopying(false)
    }
  }

  function handleDownloadIcs() {
    const ics = buildOutingIcs(outing, title.title)
    downloadIcsFile(outingIcsFilename(title.title, outing.showtime), ics)
  }

  const showtime = new Date(outing.showtime)
  const dateLabel = showtime.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })
  const timeLabel = showtime.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true })
  const companionLabel = formatCompanions(outing.companions)
  const summary = [`${dateLabel} · ${timeLabel}`, outing.venue, outing.format].filter(Boolean).join(' · ')

  const filteredFriends = friends.filter((f) => {
    if (!search.trim()) return true
    const q = search.trim().toLowerCase()
    return friendName(f).toLowerCase().includes(q) || (f.username ?? '').toLowerCase().includes(q)
  })

  return (
    <ModalBackdrop onClose={onClose} ariaLabel={`Share your "${title.title}" plans`}>
      <div
        className="relative w-full max-w-sm rounded-xl overflow-hidden flex flex-col"
        style={{ background: 'rgb(var(--ink-1-rgb))', border: '1px solid var(--line)', maxHeight: '85vh' }}
        onClick={(e) => e.stopPropagation()}
      >
        <ModalCloseButton
          ref={closeButtonRef}
          onClick={onClose}
          ariaLabel="Close share plans"
          className="top-3 right-3"
        />

        {/* Header: the outing being shared */}
        <div className="flex gap-3 px-5 pt-5 pb-4 shrink-0">
          <div
            className="w-11 h-16 rounded overflow-hidden shrink-0 flex items-center justify-center"
            style={{ background: 'var(--inset-strong)', border: '1px solid var(--line)' }}
          >
            {title.posterUrl ? (
              <img src={title.posterUrl} alt="" className="w-full h-full object-cover" />
            ) : (
              <Ticket className="w-4 h-4" style={{ color: 'var(--paper-faint)' }} />
            )}
          </div>
          <div className="flex-1 min-w-0 pt-1">
            <div
              className="font-mono uppercase tracking-widest"
              style={{ fontSize: '9px', color: 'var(--paper-faint)', letterSpacing: '0.14em' }}
            >
              Share your plans
            </div>
            <div className="font-serif text-base leading-snug mt-0.5 truncate" style={{ color: 'var(--paper)' }}>
              {title.title}
            </div>
            <div className="font-mono text-[11px] mt-0.5 truncate" style={{ color: 'var(--amber)' }}>
              {summary}
              {companionLabel && ` · with ${companionLabel}`}
            </div>
          </div>
        </div>

        {/* Out-of-app: copy/system-share snippet + .ics (plan §4.10) */}
        <div className="px-5 pb-4 shrink-0 flex gap-2">
          <button
            onClick={handleCopyOrShare}
            disabled={copying}
            className="flex-1 flex items-center justify-center gap-1.5 rounded-md py-2 font-mono text-xs transition-colors hover:text-amber disabled:opacity-60"
            style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }}
          >
            {copying ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Share2 className="w-3.5 h-3.5" />}
            Copy / share text
          </button>
          <button
            onClick={handleDownloadIcs}
            className="flex-1 flex items-center justify-center gap-1.5 rounded-md py-2 font-mono text-xs transition-colors hover:text-amber"
            style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }}
          >
            <Download className="w-3.5 h-3.5" />
            Add to calendar
          </button>
        </div>

        <p
          className="px-5 pb-2 font-mono uppercase tracking-widest shrink-0"
          style={{ fontSize: '9px', color: 'var(--paper-faint)', letterSpacing: '0.14em' }}
        >
          Or share in-app
        </p>

        {/* Friend search */}
        {friends.length > 5 && (
          <div className="px-5 pb-2 shrink-0">
            <div className="relative">
              <Search
                className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5"
                style={{ color: 'var(--paper-faint)' }}
              />
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Filter friends…"
                aria-label="Filter friends by name"
                className="w-full rounded-md pl-8 pr-3 py-1.5 text-sm font-sans focus:outline-none"
                style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }}
              />
            </div>
          </div>
        )}

        {/* Friend list */}
        <div style={{ borderTop: '1px solid var(--line)' }} className="overflow-y-auto scrollbar-thin flex-1 min-h-0">
          {loadingFriends ? (
            <div className="flex items-center justify-center gap-2 py-6 font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
              Loading friends…
            </div>
          ) : friendsError ? (
            <div className="flex flex-col items-center gap-2 py-6 px-5 text-center">
              <p className="font-sans text-xs" style={{ color: 'var(--paper-faint)' }}>
                Couldn't load your friends.
              </p>
              <button
                onClick={loadFriends}
                className="flex items-center gap-1.5 font-mono text-xs transition-colors"
                style={{ color: 'var(--amber)' }}
              >
                <RefreshCw className="w-3 h-3" />
                Retry
              </button>
            </div>
          ) : friends.length === 0 ? (
            <div className="py-6 px-5 text-center font-sans text-xs italic" style={{ color: 'var(--paper-faint)' }}>
              You don't have any friends yet — the out-of-app share above still works.
            </div>
          ) : filteredFriends.length === 0 ? (
            <div className="py-6 px-5 text-center font-sans text-xs italic" style={{ color: 'var(--paper-faint)' }}>
              No friends match "{search}".
            </div>
          ) : (
            <div className="py-1.5">
              {filteredFriends.map((f) => {
                const state = shareState[f.friend_user_id] ?? 'idle'
                const name = friendName(f)
                return (
                  <button
                    key={f.friend_user_id}
                    type="button"
                    onClick={() => handleShareToFriend(f)}
                    disabled={state === 'sending'}
                    className="w-full flex items-center gap-3 px-5 py-2.5 text-left transition-colors focus:outline-none focus-visible:bg-[var(--wash)] disabled:cursor-default"
                    style={{ background: 'transparent' }}
                    onMouseEnter={(e) => { if (state !== 'sending') e.currentTarget.style.background = 'var(--wash)' }}
                    onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
                  >
                    <div
                      className="w-8 h-8 rounded-full flex items-center justify-center shrink-0 font-serif text-sm"
                      style={{ background: 'var(--inset-strong)', border: '1px solid var(--line)', color: 'var(--paper-faint)' }}
                    >
                      {name.charAt(0).toUpperCase()}
                    </div>
                    <span className="flex-1 min-w-0 truncate font-sans text-sm" style={{ color: 'var(--paper)' }}>
                      {name}
                    </span>
                    {state === 'sending' && (
                      <Loader2 className="w-4 h-4 shrink-0 animate-spin" style={{ color: 'var(--paper-faint)' }} />
                    )}
                    {state === 'sent' && (
                      <span className="flex items-center gap-1 font-mono shrink-0" style={{ fontSize: '10px', color: 'var(--amber)' }}>
                        <Check className="w-3.5 h-3.5" />
                        Shared
                      </span>
                    )}
                    {state === 'error' && (
                      <span className="font-mono shrink-0" style={{ fontSize: '10px', color: 'var(--ember)' }}>
                        Failed — tap to retry
                      </span>
                    )}
                    {state === 'idle' && (
                      <Send className="w-3.5 h-3.5 shrink-0" style={{ color: 'var(--paper-faint)' }} />
                    )}
                  </button>
                )
              })}
            </div>
          )}
        </div>
      </div>
    </ModalBackdrop>
  )
}
