// Letterboxd CSV importer (KP-045 prototype). Letterboxd's data export is a zip
// of CSVs (watched.csv, ratings.csv, diary.csv, watchlist.csv) that all share
// the same core columns — Date,Name,Year,Letterboxd URI — with Rating and
// Watched Date appearing in the ratings/diary files. The CSVs carry no TMDB or
// IMDb ids, so each film is resolved by name+year through the existing TMDB
// search path (`searchMedia`) and hydrated with `fetchMediaDetails`, the same
// pipeline the Add-title workflow uses. Letterboxd rates on the same 0.5–5
// half-star scale as this app, so ratings copy over unchanged.
//
// The stages are split so future importers can reuse them (see
// docs/import-feasibility.md): parseLetterboxdCsv and pickBestMatch are pure
// and unit-tested; resolveLetterboxdRows owns the network pipeline.

import { searchMedia, fetchMediaDetails, type SearchResult } from './media'
import type { Title, WatchStatus } from '../store/mockData'

export interface LetterboxdRow {
  name: string
  year?: number
  /** Letterboxd half-star rating, 0.5–5 — same scale as `Title.rating`. */
  rating?: number
  /** YYYY-MM-DD. diary.csv's "Watched Date" when present, else the log "Date". */
  watchedDate?: string
}

// ─── CSV parsing ──────────────────────────────────────────────────────────────

/** Minimal RFC-4180 CSV reader: quoted fields, doubled-quote escapes, and
 *  commas/newlines inside quotes (Letterboxd titles contain all three). */
function parseCsv(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let inQuotes = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i++ } else inQuotes = false
      } else {
        field += ch
      }
    } else if (ch === '"') {
      inQuotes = true
    } else if (ch === ',') {
      row.push(field); field = ''
    } else if (ch === '\n') {
      row.push(field); field = ''
      rows.push(row); row = []
    } else if (ch !== '\r') {
      field += ch
    }
  }
  if (field.length > 0 || row.length > 0) { row.push(field); rows.push(row) }
  return rows
}

/** Parse any of Letterboxd's export CSVs into normalized rows. Throws when the
 *  file doesn't look like a Letterboxd export (no Name column). */
export function parseLetterboxdCsv(text: string): LetterboxdRow[] {
  const rows = parseCsv(text)
  if (rows.length === 0) throw new Error('The file is empty.')

  const header = rows[0].map((h) => h.trim().toLowerCase())
  const nameIdx = header.indexOf('name')
  if (nameIdx === -1) {
    throw new Error('Not a Letterboxd export — no "Name" column found.')
  }
  const yearIdx = header.indexOf('year')
  const ratingIdx = header.indexOf('rating')
  const watchedDateIdx = header.indexOf('watched date')
  const dateIdx = header.indexOf('date')

  const parsed: LetterboxdRow[] = []
  for (const cells of rows.slice(1)) {
    const name = cells[nameIdx]?.trim()
    if (!name) continue
    const year = yearIdx >= 0 ? parseInt(cells[yearIdx], 10) : NaN
    const rating = ratingIdx >= 0 ? parseFloat(cells[ratingIdx]) : NaN
    // diary.csv's "Date" is the log date; "Watched Date" is the real one.
    const watchedDate = (watchedDateIdx >= 0 ? cells[watchedDateIdx] : cells[dateIdx] ?? '')?.trim()
    parsed.push({
      name,
      year: Number.isFinite(year) ? year : undefined,
      rating: Number.isFinite(rating) && rating > 0 ? rating : undefined,
      watchedDate: /^\d{4}-\d{2}-\d{2}$/.test(watchedDate ?? '') ? watchedDate : undefined,
    })
  }
  return parsed
}

// ─── TMDB matching ────────────────────────────────────────────────────────────

/** Case/diacritic/punctuation-insensitive title comparison key. */
function normTitle(s: string): string {
  return s
    .toLowerCase()
    .normalize('NFKD')
    .replace(/\p{M}/gu, '') // strip the combining marks NFKD split off
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
}

/** Choose the TMDB movie candidate for a Letterboxd row: exact title and exact
 *  year dominate, ±1 year tolerates release-date edge cases (festival vs wide
 *  release), and TMDB's own relevance order breaks ties. With a year present
 *  but nothing matching title or year, returns undefined — surfacing an
 *  unmatched row beats silently importing a lookalike. */
export function pickBestMatch(candidates: SearchResult[], name: string, year?: number): SearchResult | undefined {
  const movies = candidates.filter((c) => c.type === 'movie')
  if (movies.length === 0) return undefined

  const target = normTitle(name)
  function score(c: SearchResult): number {
    let s = 0
    if (normTitle(c.title) === target) s += 4
    if (year != null) {
      if (c.year === year) s += 3
      else if (Math.abs(c.year - year) === 1) s += 1
    }
    return s
  }

  let best = movies[0]
  let bestScore = score(best)
  for (const c of movies.slice(1)) {
    const s = score(c)
    if (s > bestScore) { best = c; bestScore = s }
  }
  if (year != null && bestScore === 0) return undefined
  return best
}

// ─── Import pipeline ──────────────────────────────────────────────────────────

/** A film aggregated from possibly-many CSV rows (diary rewatches). */
interface GroupedFilm {
  name: string
  year?: number
  rating?: number
  watchedDates: string[]
}

/** Collapse rows to one film each: diary exports repeat a film per rewatch, so
 *  watch dates accumulate and the rating from the most recent watch wins (an
 *  undated rating row — ratings.csv is the *current* rating — always wins). */
export function groupRows(rows: LetterboxdRow[]): GroupedFilm[] {
  const byKey = new Map<string, GroupedFilm & { ratingDate?: string }>()
  for (const r of rows) {
    const key = `${normTitle(r.name)}:${r.year ?? ''}`
    let film = byKey.get(key)
    if (!film) {
      film = { name: r.name, year: r.year, watchedDates: [] }
      byKey.set(key, film)
    }
    if (r.rating != null) {
      const incoming = r.watchedDate ?? '9999-99-99'
      if (film.ratingDate == null || incoming >= film.ratingDate) {
        film.rating = r.rating
        film.ratingDate = incoming
      }
    }
    if (r.watchedDate) film.watchedDates.push(r.watchedDate)
  }
  return Array.from(byKey.values(), ({ ratingDate: _ratingDate, ...film }) => {
    film.watchedDates.sort()
    return film
  })
}

function buildTitle(detailed: SearchResult, film: GroupedFilm, status: WatchStatus): Title {
  const id = crypto.randomUUID()
  const lastDate = film.watchedDates[film.watchedDates.length - 1]
  return {
    id,
    tmdbId: detailed.tmdbId,
    type: detailed.type,
    title: detailed.title,
    year: detailed.year,
    releaseDate: detailed.releaseDate,
    originalLanguage: detailed.originalLanguage,
    contentRating: detailed.contentRating,
    imdbId: detailed.imdbId,
    rtUrl: detailed.rtUrl,
    director: detailed.director,
    genres: detailed.genres,
    posterUrl: detailed.posterUrl,
    backdropUrl: detailed.backdropUrl,
    synopsis: detailed.synopsis,
    runtime: detailed.runtime,
    network: detailed.network,
    status,
    rating: film.rating,
    tags: [],
    addedAt: new Date().toISOString().slice(0, 10),
    // One viewing per dated diary/watched row; the rating rides the most
    // recent viewing only (older rewatches' ratings aren't in the export).
    viewings: status === 'watched'
      ? film.watchedDates.map((date) => ({
          id: crypto.randomUUID(),
          titleId: id,
          date,
          rating: date === lastDate ? film.rating : undefined,
        }))
      : [],
    imdbRating: detailed.imdbRating,
    rtScore: detailed.rtScore,
    metacriticScore: detailed.metacriticScore,
    awardsCount: detailed.awardsCount,
    bechdelOutcome: detailed.bechdelOutcome,
    bechdelScore: detailed.bechdelScore,
    cast: detailed.cast,
    crew: detailed.crew,
    studios: detailed.studios,
    collectionId: detailed.collectionId,
    collectionName: detailed.collectionName,
  }
}

/** Small concurrency pool — enough parallelism to overlap proxy latency
 *  without hammering the Edge Function on a large history. */
async function mapPool<T>(items: T[], limit: number, fn: (item: T) => Promise<void>): Promise<void> {
  let next = 0
  async function worker() {
    while (next < items.length) {
      const item = items[next++]
      await fn(item)
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker))
}

export interface LetterboxdImportOutcome {
  imported: Title[]
  /** "Name (Year)" strings that couldn't be confidently resolved on TMDB. */
  unmatched: string[]
  /** Rows skipped because the film is already in the library (or earlier in this batch). */
  duplicates: number
}

export async function resolveLetterboxdRows(
  rows: LetterboxdRow[],
  opts: {
    status: WatchStatus
    isDuplicate: (tmdbId: number) => boolean
    onProgress?: (done: number, total: number) => void
    isCancelled?: () => boolean
  }
): Promise<LetterboxdImportOutcome> {
  const films = groupRows(rows)
  const outcome: LetterboxdImportOutcome = { imported: [], unmatched: [], duplicates: 0 }
  const seenTmdbIds = new Set<number>()
  let done = 0

  await mapPool(films, 3, async (film) => {
    if (opts.isCancelled?.()) return
    const label = film.year ? `${film.name} (${film.year})` : film.name
    try {
      const match = pickBestMatch(await searchMedia(film.name), film.name, film.year)
      if (!match) {
        outcome.unmatched.push(label)
        return
      }
      if (opts.isDuplicate(match.tmdbId) || seenTmdbIds.has(match.tmdbId)) {
        outcome.duplicates++
        return
      }
      const { result: detailed } = await fetchMediaDetails(match)
      // Re-check after the awaits: a concurrent worker may have claimed the
      // same tmdbId meanwhile. The check+add pair below runs synchronously.
      if (seenTmdbIds.has(detailed.tmdbId)) {
        outcome.duplicates++
        return
      }
      seenTmdbIds.add(detailed.tmdbId)
      outcome.imported.push(buildTitle(detailed, film, opts.status))
    } catch (err) {
      console.error(`Letterboxd import: failed to resolve "${label}":`, err)
      outcome.unmatched.push(label)
    } finally {
      done++
      opts.onProgress?.(done, films.length)
    }
  })

  return outcome
}
