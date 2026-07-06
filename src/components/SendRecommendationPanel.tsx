import { useEffect, useRef, useState } from 'react'
import { X, Send, Check, Loader2, Search, RefreshCw } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { listFriendships, type FriendshipView } from 'src/lib/auth'
import { sendRecommendation, fetchSentRecommendationStatus } from 'src/lib/db'
import type { Title } from 'src/store/mockData'

const NOTE_MAX_LEN = 280

interface SendRecommendationPanelProps {
  title: Title
  onClose: () => void
}

type SendState = 'idle' | 'sending' | 'sent' | 'error'

function friendName(f: FriendshipView): string {
  return f.display_name || f.username || 'Unknown user'
}

export function SendRecommendationPanel({ title, onClose }: SendRecommendationPanelProps) {
  const pushNotification = useAppStore((s) => s.pushNotification)
  const closeButtonRef = useRef<HTMLButtonElement>(null)

  const [friends, setFriends] = useState<FriendshipView[]>([])
  const [loadingFriends, setLoadingFriends] = useState(true)
  const [friendsError, setFriendsError] = useState(false)
  const [search, setSearch] = useState('')
  const [note, setNote] = useState('')
  const [sendState, setSendState] = useState<Record<string, SendState>>({})

  const onCloseRef = useRef(onClose)
  useEffect(() => { onCloseRef.current = onClose })

  useEffect(() => {
    const returnEl = document.activeElement as HTMLElement
    closeButtonRef.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onCloseRef.current() }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      returnEl?.focus()
    }
  }, [])

  async function loadFriends() {
    setLoadingFriends(true)
    setFriendsError(false)
    try {
      const [friendList, sentTo] = await Promise.all([
        listFriendships(),
        fetchSentRecommendationStatus(title.tmdbId, title.type),
      ])
      setFriends(friendList.filter((f) => f.status === 'accepted'))
      setSendState((prev) => {
        const next = { ...prev }
        for (const friendId of Object.keys(sentTo)) {
          if (!next[friendId]) next[friendId] = 'sent'
        }
        return next
      })
    } catch (err) {
      console.error('Failed to load friends for recommendation:', err)
      setFriendsError(true)
    } finally {
      setLoadingFriends(false)
    }
  }

  useEffect(() => {
    // Deferred to a macrotask so the initial setLoadingFriends(true) doesn't
    // fire synchronously within the effect body (react-hooks/set-state-in-effect).
    const t = setTimeout(() => loadFriends(), 0)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title.tmdbId, title.type])

  async function handleSend(friend: FriendshipView) {
    if (sendState[friend.friend_user_id] === 'sending') return
    const name = friendName(friend)
    setSendState((s) => ({ ...s, [friend.friend_user_id]: 'sending' }))
    try {
      await sendRecommendation(friend.friend_user_id, title, note)
      setSendState((s) => ({ ...s, [friend.friend_user_id]: 'sent' }))
      pushNotification({ message: `Sent "${title.title}" to ${name}.`, kind: 'tip', autoClose: 4000 })
    } catch (err) {
      console.error('Failed to send recommendation:', err)
      setSendState((s) => ({ ...s, [friend.friend_user_id]: 'error' }))
      pushNotification({
        message: `Couldn't send "${title.title}" to ${name} — check your connection.`,
        retry: () => handleSend(friend),
      })
    }
  }

  const filteredFriends = friends.filter((f) => {
    if (!search.trim()) return true
    const q = search.trim().toLowerCase()
    return friendName(f).toLowerCase().includes(q) || (f.username ?? '').toLowerCase().includes(q)
  })

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Send "${title.title}" to a friend`}
      className="fixed inset-0 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.82)', zIndex: 215 }}
      onClick={onClose}
    >
      <div
        className="relative w-full max-w-sm rounded-xl overflow-hidden flex flex-col"
        style={{ background: 'rgb(var(--ink-1-rgb))', border: '1px solid var(--line)', maxHeight: '85vh' }}
        onClick={(e) => e.stopPropagation()}
      >
        <button
          ref={closeButtonRef}
          onClick={onClose}
          className="absolute top-3 right-3 flex items-center justify-center w-7 h-7 rounded-full transition-colors z-10"
          style={{ color: 'var(--paper-faint)' }}
          aria-label="Close send to a friend"
          onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--paper)')}
          onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--paper-faint)')}
        >
          <X className="w-4 h-4" />
        </button>

        {/* Header: title being recommended */}
        <div className="flex gap-3 px-5 pt-5 pb-4 shrink-0">
          <div
            className="w-11 h-16 rounded overflow-hidden shrink-0 flex items-center justify-center"
            style={{ background: 'var(--inset-strong)', border: '1px solid var(--line)' }}
          >
            {title.posterUrl ? (
              <img src={title.posterUrl} alt="" className="w-full h-full object-cover" />
            ) : (
              <Send className="w-4 h-4" style={{ color: 'var(--paper-faint)' }} />
            )}
          </div>
          <div className="flex-1 min-w-0 pt-1">
            <div
              className="font-mono uppercase tracking-widest"
              style={{ fontSize: '9px', color: 'var(--paper-faint)', letterSpacing: '0.14em' }}
            >
              Send to a friend
            </div>
            <div className="font-serif text-base leading-snug mt-0.5 truncate" style={{ color: 'var(--paper)' }}>
              {title.title}
            </div>
          </div>
        </div>

        {/* Optional note */}
        <div className="px-5 pb-3 shrink-0">
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value.slice(0, NOTE_MAX_LEN))}
            placeholder="Add a note (optional)…"
            aria-label="Note to include with this recommendation"
            rows={2}
            maxLength={NOTE_MAX_LEN}
            className="w-full rounded-md px-3 py-2 text-sm font-sans resize-none focus:outline-none"
            style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }}
          />
          <div className="text-right font-mono mt-1" style={{ fontSize: '9px', color: 'var(--paper-faint)' }}>
            {note.length}/{NOTE_MAX_LEN}
          </div>
        </div>

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
              You don't have any friends yet. Add one from your Profile to start sharing recommendations.
            </div>
          ) : filteredFriends.length === 0 ? (
            <div className="py-6 px-5 text-center font-sans text-xs italic" style={{ color: 'var(--paper-faint)' }}>
              No friends match "{search}".
            </div>
          ) : (
            <div className="py-1.5">
              {filteredFriends.map((f) => {
                const state = sendState[f.friend_user_id] ?? 'idle'
                const name = friendName(f)
                return (
                  <button
                    key={f.friend_user_id}
                    type="button"
                    onClick={() => handleSend(f)}
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
                        Sent
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
    </div>
  )
}
