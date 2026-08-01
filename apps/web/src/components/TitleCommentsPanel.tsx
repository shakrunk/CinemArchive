import { useEffect, useState, useRef, useMemo } from 'react'
import { Loader2, Send, Trash2 } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import {
  fetchTitleComments, addTitleComment, deleteTitleComment,
  fetchTitleReactions, setTitleReaction,
  REACTION_EMOJIS, type TitleComment, type TitleReaction, type ReactionEmoji,
} from 'src/lib/db'
import { fmtDateShort } from 'src/lib/utils'
import { Eyebrow } from 'src/components/ui/typography'

const BODY_MAX_LEN = 1000

function commentAuthorName(c: TitleComment): string {
  return c.authorDisplayName || c.authorUsername || 'Someone'
}

function reactionAuthorName(r: TitleReaction): string {
  return r.authorDisplayName || r.authorUsername || 'Someone'
}

// Friends-only (see the migration) — only rendered by TitleDetailDrawer when
// viewerContext.kind !== 'shared-link', so an anonymous share-link visitor
// never sees this section at all.
export function TitleCommentsPanel({ titleId }: { titleId: string }) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const user = useAppStore((s) => s.user)
  const pushNotification = useAppStore((s) => s.pushNotification)

  const [comments, setComments] = useState<TitleComment[]>([])
  const [reactions, setReactions] = useState<TitleReaction[]>([])
  const [loading, setLoading] = useState(true)
  const [body, setBody] = useState('')
  const [posting, setPosting] = useState(false)
  const [reacting, setReacting] = useState(false)

  async function load() {
    setLoading(true)
    try {
      const [c, r] = await Promise.all([fetchTitleComments(titleId), fetchTitleReactions(titleId)])
      setComments(c)
      setReactions(r)
    } catch (err) {
      console.error('Failed to load comments/reactions:', err)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    // Deferred to a macrotask so the initial setLoading(true) doesn't fire
    // synchronously within the effect body (react-hooks/set-state-in-effect).
    const t = setTimeout(() => load(), 0)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [titleId])

  async function handlePost(e: React.FormEvent) {
    e.preventDefault()
    const trimmed = body.trim()
    if (!trimmed) return
    setPosting(true)
    try {
      await addTitleComment(titleId, trimmed)
      setBody('')
      await load()
    } catch (err) {
      console.error('Failed to post comment:', err)
      pushNotification({ message: "Couldn't post that comment — check your connection.", retry: () => handlePost(e) })
    } finally {
      setPosting(false)
    }
  }

  async function handleDelete(id: string) {
    const prev = comments
    setComments((c) => c.filter((x) => x.id !== id))
    try {
      await deleteTitleComment(id)
    } catch (err) {
      console.error('Failed to delete comment:', err)
      setComments(prev)
      pushNotification({ message: "Couldn't delete that comment — check your connection." })
    }
  }

  const myReaction = useMemo(() => reactions.find((r) => r.authorId === user?.id)?.emoji ?? null, [reactions, user?.id])

  async function handleToggleReaction(emoji: ReactionEmoji) {
    if (reacting) return
    const next = myReaction === emoji ? null : emoji
    setReacting(true)
    const prevReactions = reactions
    setReactions((rs) => {
      const withoutMine = rs.filter((r) => r.authorId !== user?.id)
      return next ? [...withoutMine, { authorId: user!.id, authorDisplayName: null, authorUsername: null, emoji: next }] : withoutMine
    })
    try {
      await setTitleReaction(titleId, next)
    } catch (err) {
      console.error('Failed to set reaction:', err)
      setReactions(prevReactions)
      pushNotification({ message: "Couldn't save that reaction — check your connection." })
    } finally {
      setReacting(false)
    }
  }

  // ⚡ Bolt: Memoize reaction aggregations to prevent O(E*N) calculations on every keystroke
  const reactionStats = useMemo(() => {
    const stats = new Map<string, { count: number; names: string[] }>()
    for (const emoji of REACTION_EMOJIS) {
      stats.set(emoji, { count: 0, names: [] })
    }
    for (const r of reactions) {
      const stat = stats.get(r.emoji)
      if (stat) {
        stat.count++
        stat.names.push(reactionAuthorName(r))
      }
    }
    return stats
  }, [reactions])

  return (
    <div className="pt-2 border-t space-y-3" style={{ borderColor: 'var(--line)' }}>
      <Eyebrow as="h4" size="xl" tone="muted" font="sans">Comments &amp; Reactions</Eyebrow>

      {/* Reactions */}
      <div className="flex flex-wrap gap-1.5">
        {REACTION_EMOJIS.map((emoji) => {
          const stat = reactionStats.get(emoji)
          const count = stat?.count || 0
          const names = stat?.names.join(', ') || ''
          const mine = myReaction === emoji
          return (
            <button
              key={emoji}
              type="button"
              disabled={reacting}
              onClick={() => handleToggleReaction(emoji)}
              aria-label={`${mine ? 'Remove' : 'Add'} ${emoji} reaction`}
              title={names}
              className="flex items-center gap-1 rounded-full px-2.5 py-1 text-sm border transition-colors disabled:opacity-60 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
              style={{
                borderColor: mine ? 'var(--amber)' : 'var(--line)',
                background: mine ? 'rgb(var(--amber-rgb) / 0.12)' : 'var(--inset)',
              }}
            >
              <span>{emoji}</span>
              {count > 0 && (
                <span className="font-mono text-[10px]" style={{ color: 'var(--paper-faint)' }}>
                  {count}
                </span>
              )}
            </button>
          )
        })}
      </div>

      {/* Comment list */}
      {loading ? (
        <div className="text-center py-3 text-xs font-mono text-muted-foreground">Loading comments...</div>
      ) : comments.length === 0 ? (
        <div className="flex flex-col items-center gap-2 py-4">
          <p className="font-sans text-xs text-muted-foreground italic">No comments yet.</p>
          {user && (
            <button
              onClick={() => textareaRef.current?.focus()}
              className="text-xs font-medium text-amber hover:text-amber/80 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 px-2 py-1 rounded"
            >
              Be the first to comment
            </button>
          )}
        </div>
      ) : (
        <div className="space-y-2">
          {comments.map((c) => (
            <div key={c.id} className="rounded-lg p-2.5" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
              <div className="flex items-center justify-between gap-2">
                <span className="font-sans text-xs font-medium" style={{ color: 'var(--paper)' }}>
                  {commentAuthorName(c)}
                </span>
                {c.authorId === user?.id && (
                  <button
                    onClick={() => handleDelete(c.id)}
                    aria-label={`Delete comment: "${c.body.slice(0, 20)}${c.body.length > 20 ? '...' : ''}"`}
                    title="Delete comment"
                    className="text-muted-foreground hover:text-destructive transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded"
                  >
                    <Trash2 className="w-3 h-3" />
                  </button>
                )}
              </div>
              <p className="font-sans text-sm mt-1 whitespace-pre-wrap break-words" style={{ color: 'var(--paper-dim)' }}>
                {c.body}
              </p>
              <p className="font-mono mt-1" style={{ fontSize: '9px', color: 'var(--paper-faint)' }}>
                {fmtDateShort(c.createdAt)}
              </p>
            </div>
          ))}
        </div>
      )}

      {/* Add comment */}
      {user && (
        <form onSubmit={handlePost} className="flex gap-2">
          <textarea
            ref={textareaRef}
            value={body}
            onChange={(e) => setBody(e.target.value.slice(0, BODY_MAX_LEN))}
            placeholder="Add a comment…"
            aria-label="Add a comment"
            rows={1}
            maxLength={BODY_MAX_LEN}
            className="flex-1 rounded-md px-3 py-2 text-sm font-sans resize-none focus:outline-none"
            style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }}
          />
          <button
            type="submit"
            disabled={posting || !body.trim()}
            aria-label="Post comment"
            className="shrink-0 flex items-center justify-center w-9 h-9 rounded-md disabled:opacity-50 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
            style={{ background: 'var(--amber)', color: 'var(--on-amber)' }}
          >
            {posting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
          </button>
        </form>
      )}
    </div>
  )
}
