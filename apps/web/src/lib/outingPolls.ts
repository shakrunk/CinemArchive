import { isSupabaseConfigured, supabase } from './auth'

// Outing polls (#203): put a few movie and time candidates to accepted friends, collect their
// votes, then close on a winner the owner turns into a real outing. Mirrors Android's OutingPoll.kt /
// OutingPollsRepository.kt; the contract is docs/android-contracts/outing-polls.md. Online-only: polls
// are server records shared between people, with no local copy.

export type PollStatus = 'open' | 'closed' | 'cancelled'

export interface PollOption {
  id: string
  position: number
  tmdbId: number
  title: string
  year: number | null
  posterUrl: string | null
  showtime: string
  venue: string | null
  votes: number
  /** True when the signed-in user ticked this option. */
  mine: boolean
  voters: string[]
}

export interface OutingPoll {
  id: string
  question: string
  status: PollStatus
  isOwner: boolean
  ownerName: string
  createdAt: string
  winnerOptionId: string | null
  members: { userId: string; name: string }[]
  options: PollOption[]
}

/** What the owner offers when starting a poll: one of their own movies at a future time. */
export interface PollCandidate {
  titleId: string
  showtime: string
  venue: string | null
}

export const MIN_POLL_OPTIONS = 2
export const MAX_POLL_OPTIONS = 4
export const MAX_POLL_FRIENDS = 8
export const MAX_POLL_QUESTION = 100
export const MAX_POLL_VENUE = 100

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const isUuid = (value: unknown): value is string => typeof value === 'string' && UUID_RE.test(value)
const text = (value: unknown): string | null => (typeof value === 'string' && value.trim() ? value : null)
const record = (value: unknown): Record<string, unknown> | null =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null

/** A vote or a win only makes sense for a showtime that has not passed. */
export const isUpcoming = (option: Pick<PollOption, 'showtime'>, now: number) => Date.parse(option.showtime) > now

export const canVote = (poll: OutingPoll, now: number) => poll.status === 'open' && poll.options.some((o) => isUpcoming(o, now))

/** The option(s) with the most ticks, among those still ahead; none when nobody has voted. */
export function pollLeaders(poll: OutingPoll, now: number): PollOption[] {
  const upcoming = poll.options.filter((o) => isUpcoming(o, now))
  const best = Math.max(0, ...upcoming.map((o) => o.votes))
  return best === 0 ? [] : upcoming.filter((o) => o.votes === best)
}

/** Checks a poll before it is sent, so the server's refusal is the exception, not the explanation. */
export function pollProblem(question: string, candidates: PollCandidate[], friendCount: number, now: number): string | null {
  if (question.trim().length > MAX_POLL_QUESTION) return `Keep the question to ${MAX_POLL_QUESTION} characters.`
  if (candidates.length < MIN_POLL_OPTIONS || candidates.length > MAX_POLL_OPTIONS) return `Offer ${MIN_POLL_OPTIONS} to ${MAX_POLL_OPTIONS} options.`
  if (candidates.some((c) => !(Date.parse(c.showtime) > now))) return 'Every showtime must be in the future.'
  if (candidates.some((c) => (c.venue?.trim().length ?? 0) > MAX_POLL_VENUE)) return `Keep each venue to ${MAX_POLL_VENUE} characters.`
  if (new Set(candidates.map((c) => `${c.titleId}@${Date.parse(c.showtime)}`)).size !== candidates.length) return 'Two options are the same.'
  if (friendCount < 1 || friendCount > MAX_POLL_FRIENDS) return `Choose 1 to ${MAX_POLL_FRIENDS} friends.`
  return null
}

function parseOption(raw: unknown): PollOption | null {
  const row = record(raw)
  if (!row || !isUuid(row.id) || typeof row.showtime !== 'string' || Number.isNaN(Date.parse(row.showtime))) return null
  const title = text(row.title)
  if (!title) return null
  return {
    id: row.id, position: Number(row.position) || 0, tmdbId: Number(row.tmdb_id) || 0, title,
    year: typeof row.year === 'number' && row.year > 0 ? row.year : null,
    posterUrl: text(row.poster_url), showtime: row.showtime, venue: text(row.venue),
    votes: Number(row.votes) || 0, mine: row.mine === true,
    voters: Array.isArray(row.voters) ? row.voters.filter((v): v is string => typeof v === 'string' && !!v.trim()) : [],
  }
}

/** Polls or options with an unexpected shape are skipped, never shown. */
export function parseOutingPolls(rows: unknown): OutingPoll[] {
  if (!Array.isArray(rows)) return []
  return rows.flatMap((raw): OutingPoll[] => {
    const row = record(raw)
    if (!row || !isUuid(row.id)) return []
    const status = row.status
    if (status !== 'open' && status !== 'closed' && status !== 'cancelled') return []
    const question = text(row.question)
    const rawOptions = Array.isArray(row.options) ? row.options : []
    const options = rawOptions.map(parseOption).filter((o): o is PollOption => o !== null)
    if (!question || options.length === 0 || options.length !== rawOptions.length) return []
    const members = (Array.isArray(row.members) ? row.members : []).flatMap((m): { userId: string; name: string }[] => {
      const member = record(m)
      return member && isUuid(member.user_id) ? [{ userId: member.user_id, name: text(member.name) ?? 'A friend' }] : []
    })
    return [{
      id: row.id, question, status, isOwner: row.is_owner === true, ownerName: text(row.owner_name) ?? 'A friend',
      createdAt: typeof row.created_at === 'string' ? row.created_at : '', members, options,
      winnerOptionId: isUuid(row.winner_option_id) ? row.winner_option_id : null,
    }]
  })
}

function connection() {
  if (!isSupabaseConfigured || !supabase) throw new Error('Sign in to poll your friends.')
  return supabase
}

function failure(error: { message?: string } | null, fallback: string): Error {
  return new Error(error?.message && error.message.length < 160 ? error.message : fallback)
}

/** Polls the user owns or was asked into, from the last 30 days, newest first. */
export async function listOutingPolls(): Promise<OutingPoll[]> {
  const { data, error } = await connection().rpc('get_outing_polls')
  if (error) throw failure(error, "Couldn't load your polls.")
  return parseOutingPolls(data)
}

/** Starts a poll and returns its id. The server re-checks everything, including that the friends are accepted. */
export async function createOutingPoll(question: string, candidates: PollCandidate[], friendIds: string[]): Promise<string> {
  const friends = [...new Set(friendIds)]
  const problem = pollProblem(question, candidates, friends.length, Date.now())
  if (problem) throw new Error(problem)
  if (!candidates.every((c) => isUuid(c.titleId)) || !friends.every(isUuid)) throw new Error('Invalid id')
  const { data, error } = await connection().rpc('create_outing_poll', {
    p_question: question.trim(),
    p_options: candidates.map((c) => ({ title_id: c.titleId, showtime: new Date(c.showtime).toISOString(), venue: c.venue?.trim() || null })),
    p_friend_ids: friends,
  })
  if (error) throw failure(error, 'Could not start the poll.')
  const id = record(data)?.poll_id
  if (!isUuid(id)) throw new Error('Could not start the poll.')
  return id
}

/** Replaces the caller's ticks on a poll with exactly these options (none clears them). */
export async function castPollVotes(pollId: string, optionIds: string[]): Promise<void> {
  const ids = [...new Set(optionIds)]
  if (!isUuid(pollId) || !ids.every(isUuid)) throw new Error('Invalid id')
  const { error } = await connection().rpc('cast_poll_votes', { p_poll_id: pollId, p_option_ids: ids })
  if (error) throw failure(error, 'Could not save your votes.')
}

/** Owner only. Closes on a winning option, or cancels the poll when none is given. */
export async function closeOutingPoll(pollId: string, optionId: string | null): Promise<void> {
  if (!isUuid(pollId) || (optionId !== null && !isUuid(optionId))) throw new Error('Invalid id')
  const { error } = await connection().rpc('close_outing_poll', { p_poll_id: pollId, p_option_id: optionId })
  if (error) throw failure(error, 'Could not close the poll.')
}
