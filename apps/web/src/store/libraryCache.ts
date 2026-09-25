import type { Title } from './mockData'

/** Slim copy of a title for the browser's offline cache (localStorage, ~5M
 * chars per origin). Signed-in sessions refetch the full library from
 * Supabase on load, so the cache only needs enough to paint the library,
 * search, filters and Ledger before that lands. Display-only metadata —
 * episode synopses, stills and crew, credit portraits and character names —
 * is dropped; with it, large TV libraries exceeded the storage quota.
 */
export function toCachedTitle(title: Title): Title {
  return {
    ...title,
    cast: title.cast?.map(({ tmdbPersonId, name, order }) => ({ tmdbPersonId, name, order })),
    crew: title.crew?.map(({ tmdbPersonId, name, job }) => ({ tmdbPersonId, name, job })),
    seasons: title.seasons?.map((season) => ({
      ...season,
      cast: season.cast?.map(({ tmdbPersonId, name, order }) => ({ tmdbPersonId, name, order })),
      episodes: season.episodes?.map(({ synopsis: _synopsis, stillUrl: _stillUrl, crew: _crew, writers: _writers, ...episode }) => episode),
    })),
  }
}
