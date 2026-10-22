import type { CinemaOuting, Title } from './mockData'
import { localTodayYmd } from './episodeUtils'

// ─── Calendar: upcoming releases, episode airings and cinema outings ─────────
//
// Pure derivation over the library already in the store — no extra fetches.
// Every date is a local calendar day (YYYY-MM-DD), the format TMDB uses for
// `air_date`/`release_date`, so they compare correctly as strings. Never parse
// one with `new Date('YYYY-MM-DD')` (a UTC midnight, a day early west of UTC).

export type CalendarEvent =
  | {
      kind: 'release'
      id: string
      date: string
      title: Title
    }
  | {
      kind: 'premiere'
      id: string
      date: string
      title: Title
    }
  | {
      kind: 'episodes'
      id: string
      date: string
      title: Title
      seasonNumber: number
      /** Ascending. More than one when a season drops several episodes at once. */
      episodeNumbers: number[]
      /** Name of the episode, only when exactly one airs that day. */
      episodeName?: string
    }
  | {
      kind: 'outing'
      id: string
      date: string
      title: Title
      outing: CinemaOuting
    }

export interface CalendarDay {
  date: string
  events: CalendarEvent[]
}

const KIND_ORDER: Record<CalendarEvent['kind'], number> = { outing: 0, premiere: 1, release: 2, episodes: 3 }

/**
 * Every upcoming event on or after `today`, sorted by date.
 *
 * - **Movie releases:** watchlist movies with a release date.
 * - **Episodes:** every TV title except dropped ones (a finished show getting a
 *   new season is exactly what this is for). Episodes airing the same day in
 *   the same season are folded into one event so a binge drop doesn't flood
 *   the day. Episodes without an air date are skipped — TMDB lists undated
 *   placeholders that aren't scheduled. Specials (season 0) are included.
 * - **Premieres:** a watchlist series' first-air date, unless an episode of it
 *   already airs that day (which then carries the event instead).
 * - **Outings:** scheduled cinema outings, by the showtime's local day. Pass
 *   an empty list outside the owner's own view: outings are never shared.
 */
export function computeCalendarEvents(titles: Title[], outings: CinemaOuting[], today: string): CalendarEvent[] {
  const events: CalendarEvent[] = []
  const titlesById = new Map(titles.map((t) => [t.id, t]))

  for (const title of titles) {
    if (title.type === 'movie') {
      if (title.status === 'watchlist' && title.releaseDate && title.releaseDate >= today) {
        events.push({ kind: 'release', id: `release:${title.id}`, date: title.releaseDate, title })
      }
      continue
    }

    if (title.status === 'dropped') continue
    const episodeDays = new Set<string>()
    for (const season of title.seasons ?? []) {
      const byDay = new Map<string, { numbers: number[]; name?: string }>()
      for (const ep of season.episodes ?? []) {
        if (!ep.airDate || ep.airDate < today) continue
        const group = byDay.get(ep.airDate)
        if (group) group.numbers.push(ep.episodeNumber)
        else byDay.set(ep.airDate, { numbers: [ep.episodeNumber], name: ep.episodeName })
      }
      for (const [date, group] of byDay) {
        episodeDays.add(date)
        const episodeNumbers = group.numbers.sort((a, b) => a - b)
        events.push({
          kind: 'episodes',
          id: `episodes:${title.id}:${season.seasonNumber}:${date}`,
          date,
          title,
          seasonNumber: season.seasonNumber,
          episodeNumbers,
          episodeName: episodeNumbers.length === 1 ? group.name : undefined,
        })
      }
    }

    if (
      title.status === 'watchlist' &&
      title.releaseDate &&
      title.releaseDate >= today &&
      !episodeDays.has(title.releaseDate)
    ) {
      events.push({ kind: 'premiere', id: `premiere:${title.id}`, date: title.releaseDate, title })
    }
  }

  for (const outing of outings) {
    if (outing.status !== 'scheduled') continue
    const title = titlesById.get(outing.titleId)
    if (!title) continue
    const date = localTodayYmd(new Date(outing.showtime))
    if (date < today) continue
    events.push({ kind: 'outing', id: `outing:${outing.id}`, date, title, outing })
  }

  return events.sort((a, b) => {
    if (a.date !== b.date) return a.date < b.date ? -1 : 1
    if (a.kind === 'outing' && b.kind === 'outing') return a.outing.showtime < b.outing.showtime ? -1 : 1
    if (a.kind !== b.kind) return KIND_ORDER[a.kind] - KIND_ORDER[b.kind]
    return a.title.title.localeCompare(b.title.title)
  })
}

/** Groups already-sorted events into consecutive days. */
export function groupEventsByDay(events: CalendarEvent[]): CalendarDay[] {
  const days: CalendarDay[] = []
  for (const event of events) {
    const last = days[days.length - 1]
    if (last && last.date === event.date) last.events.push(event)
    else days.push({ date: event.date, events: [event] })
  }
  return days
}

/** "E3" / "E1–E8" / "E1, E3" — compact episode range for an `episodes` event. */
export function formatEpisodeRange(numbers: number[]): string {
  if (numbers.length === 0) return ''
  const contiguous = numbers.every((n, i) => i === 0 || n === numbers[i - 1] + 1)
  if (numbers.length === 1) return `E${numbers[0]}`
  if (contiguous) return `E${numbers[0]}–E${numbers[numbers.length - 1]}`
  return numbers.map((n) => `E${n}`).join(', ')
}

/** Local YYYY-MM-DD for a calendar cell. `month` is 0-based, as in `Date`. */
export function ymd(year: number, month: number, day: number): string {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

/**
 * The cells of a month grid starting on Sunday: leading `null`s pad the first
 * week, then one YYYY-MM-DD per day, then trailing `null`s fill the last week.
 */
export function monthGrid(year: number, month: number): (string | null)[] {
  const leading = new Date(year, month, 1).getDay()
  const daysInMonth = new Date(year, month + 1, 0).getDate()
  const cells: (string | null)[] = Array.from({ length: leading }, () => null)
  for (let d = 1; d <= daysInMonth; d++) cells.push(ymd(year, month, d))
  while (cells.length % 7 !== 0) cells.push(null)
  return cells
}
