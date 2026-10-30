import { useCallback, useEffect, useMemo, useState } from 'react'
import { BarChart3 } from 'lucide-react'
import { isSupabaseConfigured, listFriendships, type FriendshipView } from 'src/lib/auth'
import { isDevMockUser } from 'src/lib/devAuth'
import {
  canVote, castPollVotes, closeOutingPoll, createOutingPoll, isUpcoming, listOutingPolls, MAX_POLL_FRIENDS, MAX_POLL_OPTIONS,
  MAX_POLL_QUESTION, MAX_POLL_VENUE, MIN_POLL_OPTIONS, pollLeaders, pollProblem, type OutingPoll, type PollCandidate, type PollOption,
} from 'src/lib/outingPolls'
import { useAppStore } from 'src/store/useAppStore'
import type { Title } from 'src/store/mockData'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { Eyebrow } from 'src/components/ui/typography'

const errorMessage = (cause: unknown, fallback: string) => cause instanceof Error && cause.message ? cause.message : fallback
const friendName = (f: FriendshipView) => f.display_name || f.username || 'Unknown user'

function whenLabel(option: PollOption): string {
  return [
    new Date(option.showtime).toLocaleString(undefined, { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }),
    option.venue,
  ].filter(Boolean).join(' · ')
}

/** Entry point in the Up Next header (#203). Private owner view only, and only with a real account. */
export function OutingPollsButton() {
  const user = useAppStore((s) => s.user)
  const ownerView = useAppStore((s) => !s.isSharedView && s.viewerContext.kind === 'owner')
  const [open, setOpen] = useState(false)
  if (!isSupabaseConfigured || !user || isDevMockUser(user) || !ownerView) return null
  return <>
    <button
      type="button"
      onClick={() => setOpen(true)}
      className="mb-2 shrink-0 inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 font-mono text-xs text-amber/85 hover:text-amber transition-colors focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-amber"
      style={{ border: '1px solid var(--line)' }}
    >
      <BarChart3 className="w-3.5 h-3.5" aria-hidden="true" /> Polls
    </button>
    {open && <OutingPollsModal onClose={() => setOpen(false)} />}
  </>
}

function PollCard({ poll, now, busy, onVote, onClose, onSchedule }: {
  poll: OutingPoll; now: number; busy: boolean
  onVote: (optionIds: string[]) => void
  onClose: (optionId: string | null) => void
  onSchedule: (option: PollOption) => void
}) {
  const open = poll.status === 'open'
  const leaders = open ? pollLeaders(poll, now) : []
  const winner = poll.options.find((o) => o.id === poll.winnerOptionId) ?? null
  return (
    <li className="rounded-xl p-4 space-y-2" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
      <p className="font-serif text-base" style={{ color: 'var(--paper)' }}>{poll.question}</p>
      <p className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
        {poll.isOwner ? `Your poll · ${poll.members.length} invited` : `From ${poll.ownerName}`}
      </p>
      <ul className="space-y-2">
        {poll.options.map((option) => {
          const canTick = open && isUpcoming(option, now)
          return (
            <li key={option.id} className="flex items-start gap-2">
              {open && (
                <input type="checkbox" className="mt-1" checked={option.mine} disabled={!canTick || busy}
                  aria-label={`Works for me: ${option.title}`}
                  onChange={() => {
                    const ticked = new Set(poll.options.filter((o) => o.mine).map((o) => o.id))
                    if (!ticked.delete(option.id)) ticked.add(option.id)
                    onVote([...ticked])
                  }} />
              )}
              <div className="flex-1 min-w-0">
                <p className="font-sans text-sm" style={{ color: 'var(--paper)' }}>
                  {[option.title, option.year].filter(Boolean).join(' · ')}{option.id === poll.winnerOptionId && ' ✓ chosen'}
                </p>
                <p className="font-mono text-xs" style={{ color: 'var(--amber)' }}>{whenLabel(option)}</p>
                <p className="font-sans text-xs" style={{ color: 'var(--paper-faint)' }}>
                  {option.votes === 0 ? 'No votes yet' : `${option.votes} can make it: ${option.voters.join(', ')}`}
                </p>
              </div>
              {open && poll.isOwner && isUpcoming(option, now) && (
                <Button type="button" size="sm" variant="ghost" disabled={busy} aria-label={`Pick ${option.title} at ${whenLabel(option)}`}
                  onClick={() => onClose(option.id)}>Pick</Button>
              )}
            </li>
          )
        })}
      </ul>
      {open && leaders.length > 0 && <p className="font-mono text-xs" style={{ color: 'var(--amber)' }}>Leading: {leaders.map((o) => o.title).join(', ')}</p>}
      {open && !canVote(poll, now) && <p className="text-xs" style={{ color: 'var(--paper-faint)' }}>Every showtime has passed.</p>}
      {open && poll.isOwner && <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={() => onClose(null)}>Cancel poll</Button>}
      {poll.status === 'cancelled' && <p className="font-mono text-xs" style={{ color: 'var(--ember)' }}>Cancelled</p>}
      {poll.status === 'closed' && winner && <>
        <p className="font-mono text-xs" style={{ color: 'var(--amber)' }}>Chosen: {winner.title} · {whenLabel(winner)}</p>
        {poll.isOwner && <Button type="button" size="sm" variant="outline" onClick={() => onSchedule(winner)}>Schedule this</Button>}
      </>}
    </li>
  )
}

interface Draft { titleId: string; date: string; time: string; venue: string }
const blankDraft = (): Draft => ({ titleId: '', date: '', time: '19:00', venue: '' })

const draftToCandidate = (draft: Draft): PollCandidate | null => {
  if (!draft.titleId || !draft.date || !draft.time) return null
  const at = new Date(`${draft.date}T${draft.time}`)
  return Number.isNaN(at.getTime()) ? null : { titleId: draft.titleId, showtime: at.toISOString(), venue: draft.venue.trim() || null }
}

function NewPollForm({ movies, onCancel, onCreated }: { movies: Title[]; onCancel: () => void; onCreated: () => void }) {
  const [question, setQuestion] = useState('')
  const [drafts, setDrafts] = useState<Draft[]>([blankDraft(), blankDraft()])
  const [friends, setFriends] = useState<FriendshipView[] | null>(null)
  const [chosen, setChosen] = useState<string[]>([])
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let current = true
    listFriendships().then(
      (rows) => { if (current) setFriends(rows.filter((f) => f.status === 'accepted')) },
      (cause) => { if (current) { setFriends([]); setError(errorMessage(cause, "Couldn't load your friends.")) } },
    )
    return () => { current = false }
  }, [])

  const candidates = drafts.map(draftToCandidate)
  const complete = candidates.every((c): c is PollCandidate => c !== null)
  const problem = !complete ? 'Choose a movie, a date and a time for every option.'
    : pollProblem(question, candidates, chosen.length, Date.now())
  const patch = (index: number, change: Partial<Draft>) => setDrafts((list) => list.map((d, i) => (i === index ? { ...d, ...change } : d)))

  async function send() {
    if (problem || sending) return
    setSending(true); setError(null)
    try { await createOutingPoll(question, candidates as PollCandidate[], chosen); onCreated() }
    catch (cause) { setError(errorMessage(cause, "Couldn't start the poll.")); setSending(false) }
  }

  return (
    <div className="space-y-4">
      <h2 className="font-serif text-xl font-light" style={{ color: 'var(--paper)' }}>New poll</h2>
      <Input aria-label="Question" value={question} maxLength={MAX_POLL_QUESTION} placeholder="Which one works? (optional)"
        onChange={(event) => setQuestion(event.target.value)} />
      {drafts.map((draft, index) => (
        <fieldset key={index} className="rounded-xl p-3 space-y-2" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <legend className="font-mono text-xs px-1" style={{ color: 'var(--paper-faint)' }}>Option {index + 1}</legend>
          <select aria-label={`Movie for option ${index + 1}`} value={draft.titleId} onChange={(event) => patch(index, { titleId: event.target.value })}
            className="w-full rounded-md bg-secondary/50 border border-border px-2 py-2 text-sm">
            <option value="">Choose a movie</option>
            {movies.map((movie) => <option key={movie.id} value={movie.id}>{[movie.title, movie.year].filter(Boolean).join(' · ')}</option>)}
          </select>
          <div className="flex gap-2">
            <Input type="date" aria-label={`Date for option ${index + 1}`} value={draft.date} onChange={(event) => patch(index, { date: event.target.value })} />
            <Input type="time" aria-label={`Time for option ${index + 1}`} value={draft.time} onChange={(event) => patch(index, { time: event.target.value })} />
          </div>
          <Input aria-label={`Theater for option ${index + 1}`} value={draft.venue} maxLength={MAX_POLL_VENUE} placeholder="Theater (optional)"
            onChange={(event) => patch(index, { venue: event.target.value })} />
          {drafts.length > MIN_POLL_OPTIONS && (
            <Button type="button" size="sm" variant="ghost" onClick={() => setDrafts((list) => list.filter((_, i) => i !== index))}>Remove option</Button>
          )}
        </fieldset>
      ))}
      {drafts.length < MAX_POLL_OPTIONS && <Button type="button" size="sm" variant="ghost" onClick={() => setDrafts((list) => [...list, blankDraft()])}>Add another option</Button>}
      <fieldset className="space-y-1">
        <legend className="font-sans text-sm" style={{ color: 'var(--paper)' }}>Ask</legend>
        {friends === null ? <p role="status" className="text-xs">Loading friends…</p>
          : friends.length === 0 ? <p className="text-xs">Add a friend to poll them.</p>
          : friends.map((friend) => (
            <label key={friend.friend_user_id} className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={chosen.includes(friend.friend_user_id)}
                disabled={!chosen.includes(friend.friend_user_id) && chosen.length >= MAX_POLL_FRIENDS}
                aria-label={`Ask ${friendName(friend)}`}
                onChange={() => setChosen((ids) => (ids.includes(friend.friend_user_id) ? ids.filter((id) => id !== friend.friend_user_id) : [...ids, friend.friend_user_id]))} />
              {friendName(friend)}
            </label>
          ))}
      </fieldset>
      {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
      {problem && (candidates.some(Boolean) || chosen.length > 0) && <p className="text-xs" style={{ color: 'var(--paper-faint)' }}>{problem}</p>}
      <div className="flex gap-2">
        <Button type="button" variant="ghost" disabled={sending} onClick={onCancel}>Back</Button>
        <Button type="button" disabled={sending || !!problem} onClick={() => void send()}>{sending ? 'Sending…' : 'Send poll'}</Button>
      </div>
    </div>
  )
}

function OutingPollsModal({ onClose }: { onClose: () => void }) {
  const titles = useAppStore((s) => s.titles)
  const openOutingSchedule = useAppStore((s) => s.openOutingSchedule)
  const movies = useMemo(() => titles.filter((t) => t.type === 'movie').sort((a, b) => a.title.localeCompare(b.title)), [titles])
  const [polls, setPolls] = useState<OutingPoll[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [busy, setBusy] = useState<string[]>([])
  const [now] = useState(() => Date.now())

  const refresh = useCallback(async () => {
    try { setPolls(await listOutingPolls()); setError(null) }
    catch (cause) { setPolls((current) => current ?? []); setError(errorMessage(cause, "Couldn't load your polls. Check your connection.")) }
  }, [])
  useEffect(() => {
    const timer = setTimeout(() => void refresh(), 0)
    return () => clearTimeout(timer)
  }, [refresh])

  async function act(pollId: string, fallback: string, work: () => Promise<void>) {
    if (busy.includes(pollId)) return
    setBusy((ids) => [...ids, pollId]); setError(null)
    try { await work(); setPolls(await listOutingPolls()) }
    catch (cause) { setError(errorMessage(cause, fallback)) }
    finally { setBusy((ids) => ids.filter((id) => id !== pollId)) }
  }

  // The winner becomes a real outing through the usual schedule sheet, with the friends who ticked it filled in.
  function schedule(option: PollOption) {
    const owned = movies.find((movie) => movie.tmdbId === option.tmdbId)
    if (!owned) { setError('That movie is no longer in your library.'); return }
    onClose()
    openOutingSchedule(owned.id, undefined, { showtime: option.showtime, venue: option.venue ?? undefined, format: undefined, companions: option.voters })
  }

  return (
    <CinemaModal open onClose={onClose} title="Polls with friends" description="Offer a few movies and times; friends tick every one that works for them.">
      <div className="space-y-4 px-6 py-6 text-left overflow-y-auto scrollbar-thin">
        {creating ? (
          <NewPollForm movies={movies} onCancel={() => setCreating(false)} onCreated={() => { setCreating(false); void refresh() }} />
        ) : <>
          <div className="flex items-center justify-between gap-3">
            <h2 className="font-serif text-xl font-light" style={{ color: 'var(--paper)' }}>Polls with friends</h2>
            <Button type="button" size="sm" onClick={() => setCreating(true)}>New poll</Button>
          </div>
          <Eyebrow as="p" size="lg" tone="muted">Offer a few movies and times; friends tick every one that works for them.</Eyebrow>
          {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
          {polls === null ? <p role="status" className="text-sm text-muted-foreground">Loading polls…</p>
            : polls.length === 0 ? <p className="text-sm text-muted-foreground">No polls yet.</p>
            : <ul className="space-y-3">
              {polls.map((poll) => (
                <PollCard key={poll.id} poll={poll} now={now} busy={busy.includes(poll.id)}
                  onVote={(ids) => void act(poll.id, "Couldn't save your votes.", () => castPollVotes(poll.id, ids))}
                  onClose={(optionId) => void act(poll.id, "Couldn't close the poll.", () => closeOutingPoll(poll.id, optionId))}
                  onSchedule={schedule} />
              ))}
            </ul>}
        </>}
      </div>
    </CinemaModal>
  )
}
