// One-shot migration: MovieTracker v1 (movies.json `log`) → CinemArchive Title[].
// Regenerates the data portion of src/store/mockData.ts (interfaces are preserved).
// Run from the CinemArchive root:  node scripts/migrate-from-v1.mjs
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = resolve(__dirname, '..')
const SRC = resolve(root, '../MovieTracker/movies.json')
const OUT = resolve(root, 'src/store/mockData.ts')

const raw = JSON.parse(readFileSync(SRC, 'utf8'))
const log = raw.log ?? []

const STATUS_MAP = {
  watched: 'watched',
  watched_no_date: 'watched',
  want_to_watch: 'watchlist',
  watching: 'watching',
  on_hold: 'watching',
  abandoned: 'dropped',
}

function runtimeToMinutes(hms) {
  if (!hms || typeof hms !== 'string') return undefined
  const [h = '0', m = '0'] = hms.split(':')
  const mins = parseInt(h, 10) * 60 + parseInt(m, 10)
  return mins > 0 ? mins : undefined
}

// Fold v1 genre spellings onto the canonical labels used across the app, so
// filters and stats don't fragment (e.g. "Sci-Fi" vs "Science Fiction").
const GENRE_ALIASES = {
  'sci-fi': 'Science Fiction',
  scifi: 'Science Fiction',
  'science-fiction': 'Science Fiction',
}

function normalizeGenre(g) {
  return GENRE_ALIASES[g.toLowerCase()] ?? g
}

function splitList(s) {
  if (!s || typeof s !== 'string') return []
  return s.split(',').map((x) => x.trim()).filter(Boolean)
}

function genreList(s) {
  return splitList(s).map(normalizeGenre)
}

const titles = log.map((e, i) => {
  const id = `mt-${i + 1}`
  const type = e.Type === 'tv' ? 'tv' : 'movie'
  const dates = Array.isArray(e.WatchDate) ? [...e.WatchDate].filter(Boolean).sort() : []
  const year = e.ReleaseDate ? parseInt(String(e.ReleaseDate).slice(0, 4), 10) : 0
  const rating = typeof e.Rating === 'number' && e.Rating > 0 ? e.Rating : undefined
  const notes = e.Notes && String(e.Notes).trim() ? String(e.Notes).trim() : undefined
  const addedAt = dates[0] || (e.ReleaseDate ?? '2024-01-01')

  const viewings = dates.map((d, k) => ({
    id: `${id}-v${k + 1}`,
    titleId: id,
    date: d,
    ...(rating ? { rating } : {}),
  }))

  // Preserve a couple of v1-only fields as tags so the data isn't lost.
  const tags = []
  if (e.PriorWatch === true) tags.push('rewatch')

  const t = {
    id,
    tmdbId: 0,
    type,
    title: e.Title,
    year: Number.isFinite(year) ? year : 0,
    director: e.Director || undefined,
    genres: genreList(e.Genre),
    runtime: runtimeToMinutes(e.Runtime),
    network: e.Network || undefined,
    status: STATUS_MAP[e.Status] ?? 'watched',
    rating,
    notes,
    tags,
    addedAt,
    viewings,
  }

  // TV seasons (none in current v1 data, but handle generally)
  if (type === 'tv' && Array.isArray(e.Seasons) && e.Seasons.length) {
    t.seasons = e.Seasons.map((s, k) => ({
      id: `${id}-s${s.Season ?? k + 1}`,
      seasonNumber: s.Season ?? k + 1,
      episodeCount: s.Episodes ?? 0,
      episodesWatched: s.EpisodesWatched ?? 0,
    }))
  }

  // drop undefined keys for a clean literal
  return Object.fromEntries(Object.entries(t).filter(([, v]) => v !== undefined))
})

// ── Ledger stats derived from the migrated titles ──
function computeStats(list) {
  const movies = list.filter((t) => t.type === 'movie')
  const series = list.filter((t) => t.type === 'tv')
  const viewings = list.flatMap((t) => t.viewings ?? [])
  const rated = list.filter((t) => typeof t.rating === 'number')
  const avg = rated.length ? rated.reduce((a, t) => a + t.rating, 0) / rated.length : 0

  const tally = (arr) => {
    const m = new Map()
    for (const k of arr) m.set(k, (m.get(k) ?? 0) + 1)
    return [...m.entries()].map(([k, count]) => [k, count])
  }

  const genreCounts = tally(list.flatMap((t) => t.genres ?? []))
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)
    .map(([genre, count]) => ({ genre, count }))

  const dirCounts = tally(list.map((t) => t.director).filter(Boolean))
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([director, count]) => ({ director, count }))

  const ratingDistribution = []
  for (let r = 5; r >= 1; r -= 0.5) {
    ratingDistribution.push({ rating: r, count: list.filter((t) => t.rating === r).length })
  }

  const monthMap = new Map()
  for (const v of viewings) {
    const month = String(v.date).slice(0, 7)
    monthMap.set(month, (monthMap.get(month) ?? 0) + 1)
  }
  const viewingsByMonth = [...monthMap.entries()]
    .map(([month, count]) => ({ month, count }))
    .sort((a, b) => a.month.localeCompare(b.month))

  return {
    totalMovies: movies.length,
    totalSeries: series.length,
    totalViewings: viewings.length,
    avgRating: Math.round(avg * 10) / 10,
    totalMinutes: list.reduce((a, t) => a + (t.runtime ?? 0), 0),
    topGenres: genreCounts,
    topDirectors: dirCounts,
    ratingDistribution,
    viewingsByMonth,
  }
}

const stats = computeStats(titles)

// ── Emit the file: preserve interface header, replace data ──
const header = readFileSync(OUT, 'utf8').split('\n').slice(0, 55).join('\n')
const j = (v) => JSON.stringify(v, null, 2)

const body = `
// ─────────────────────────────────────────────────────────────────────────────
// Library data — migrated from MovieTracker v1 (movies.json) on 2026-06-16.
// Regenerate with: node scripts/migrate-from-v1.mjs
// ─────────────────────────────────────────────────────────────────────────────

export const mockTitles: Title[] = ${j(titles)}

export const mockLedgerStats: LedgerStats = ${j(stats)}
`

writeFileSync(OUT, header + '\n' + body, 'utf8')
console.log(`Migrated ${titles.length} titles → ${OUT}`)
console.log(`Stats: ${stats.totalMovies} movies, ${stats.totalSeries} series, ${stats.totalViewings} viewings, avg ${stats.avgRating}`)
