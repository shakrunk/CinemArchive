import { useCallback, useEffect, useState } from 'react'
import { isSupabaseConfigured } from 'src/lib/auth'
import { isDevMockUser } from 'src/lib/devAuth'
import {
  canShareReaction, MAX_REACTION_NOTE, outingReactions, sharedOutingIdFor, shareOutingReaction, withdrawOutingReaction,
  type OutingReaction,
} from 'src/lib/sharedOutings'
import { useAppStore } from 'src/store/useAppStore'
import { Button } from 'src/components/ui/button'
import { StarRating } from 'src/components/ui/star-rating'
import { Eyebrow } from 'src/components/ui/typography'

/**
 * How it went, for the whole group (#207): everyone's rating and note on a shared outing, and the
 * caller's own to add, change or take back. Sharing is a deliberate button press and is separate from
 * the private viewing log, so `initialRating` and `initialNote` only prefill the fields.
 */
export function GroupReactions({ sharedOutingId, initialRating = null, initialNote = null }: {
  sharedOutingId: string
  initialRating?: number | null
  initialNote?: string | null
}) {
  const [reactions, setReactions] = useState<OutingReaction[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [rating, setRating] = useState<number>(initialRating ?? 0)
  const [note, setNote] = useState(initialNote ?? '')
  const [prefilled, setPrefilled] = useState(false)

  const load = useCallback(() => outingReactions(sharedOutingId), [sharedOutingId])

  useEffect(() => {
    let current = true
    load().then(
      (list) => { if (current) setReactions(list) },
      () => { if (current) { setReactions([]); setError("Couldn't load the group's reactions. Check your connection.") } },
    )
    return () => { current = false }
  }, [load])

  const mine = reactions?.find((r) => r.isMe) ?? null
  useEffect(() => {
    // Once, show what was already shared rather than the prefill from the private log.
    if (mine && !prefilled) { setPrefilled(true); setRating(mine.rating ?? 0); setNote(mine.note ?? '') }
  }, [mine, prefilled])

  async function run(fallback: string, work: () => Promise<void>) {
    if (busy) return
    setBusy(true); setError(null)
    try { await work(); setReactions(await load()) }
    catch (cause) { setError(cause instanceof Error && cause.message.length < 120 ? cause.message : fallback) }
    finally { setBusy(false) }
  }

  const others = (reactions ?? []).filter((r) => !r.isMe)
  const rated = rating > 0 ? rating : null
  return (
    <section aria-label="Group reactions" className="space-y-3">
      <Eyebrow as="p" size="xl" tone="muted" font="sans">How it went for the group</Eyebrow>
      {reactions === null ? <p role="status" className="text-sm text-muted-foreground">Loading…</p>
        : others.length === 0 ? <p className="text-xs text-muted-foreground">Nobody else has shared yet.</p>
        : <ul className="space-y-2">
          {others.map((reaction) => (
            <li key={reaction.userId}>
              <p className="font-sans text-sm text-foreground">
                {reaction.name}{reaction.rating !== null && <span className="font-mono text-amber"> · ★ {reaction.rating.toFixed(1)}</span>}
              </p>
              {reaction.note && <p className="font-sans text-sm text-muted-foreground">“{reaction.note}”</p>}
            </li>
          ))}
        </ul>}
      <div className="space-y-2">
        <p className="font-sans text-sm text-foreground">Your reaction</p>
        <p className="text-xs text-muted-foreground">Shared only with the people on this outing. It is separate from your own rating and notes.</p>
        <StarRating value={rating} onChange={setRating} size="lg" />
        <textarea
          aria-label="A note for the group" value={note} maxLength={MAX_REACTION_NOTE} rows={2} disabled={busy}
          onChange={(event) => setNote(event.target.value)}
          className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none"
        />
        {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
        <div className="flex flex-wrap gap-2">
          <Button type="button" size="sm" disabled={busy || reactions === null || !canShareReaction(rated, note)}
            onClick={() => void run("Couldn't share your reaction.", () => shareOutingReaction(sharedOutingId, rated, note))}>
            {busy ? 'Sharing…' : mine ? 'Update' : 'Share with the group'}
          </Button>
          {mine && <Button type="button" size="sm" variant="ghost" disabled={busy}
            onClick={() => void run("Couldn't take your reaction back.", async () => {
              await withdrawOutingReaction(sharedOutingId); setRating(0); setNote(''); setPrefilled(false)
            })}>Take mine back</Button>}
          {rating > 0 && <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={() => setRating(0)}>Clear rating</Button>}
        </div>
      </div>
    </section>
  )
}

/**
 * The panel for one of the owner's own outings, which may or may not have been shared: finds the
 * shared outing and shows nothing when there is none, in a shared or friend view, or without an account.
 */
export function OwnerGroupReactions({ outingId, initialRating, initialNote }: {
  outingId: string
  initialRating?: number | null
  initialNote?: string | null
}) {
  const user = useAppStore((s) => s.user)
  const ownerView = useAppStore((s) => !s.isSharedView && s.viewerContext.kind === 'owner')
  const enabled = isSupabaseConfigured && !!user && !isDevMockUser(user) && ownerView
  const [sharedId, setSharedId] = useState<string | null>(null)
  useEffect(() => {
    if (!enabled) return
    let current = true
    sharedOutingIdFor(outingId).then((id) => { if (current) setSharedId(id) }, () => { if (current) setSharedId(null) })
    return () => { current = false }
  }, [enabled, outingId])
  if (!enabled || !sharedId) return null
  return <GroupReactions sharedOutingId={sharedId} initialRating={initialRating} initialNote={initialNote} />
}
