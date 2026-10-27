import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchUserLibrary } from './db'

interface Query { select: string; ids?: string[]; referenced: string[] }
const server = vi.hoisted(() => ({ queries: [] as Query[], rows: [] as Record<string, unknown>[] }))

// A tiny PostgREST stand-in: core selects return credit-free rows, credit
// selects return only credit embeds, full selects everything, all honouring `id=in.(...)`.
vi.mock('./auth', () => ({
  supabase: {
    from: (table: string) => {
      if (table === 'cinema_outings') return { select: () => ({ eq: async () => ({ data: [], error: null }) }) }
      const query: Query = { select: '', referenced: [] }
      const builder = {
        select(select: string) { query.select = select; return builder },
        eq() { return builder },
        order(_column: string, options?: { referencedTable?: string }) { if (options?.referencedTable) query.referenced.push(options.referencedTable); return builder },
        limit(_count: number, options?: { referencedTable?: string }) { if (options?.referencedTable) query.referenced.push(options.referencedTable); return builder },
        in(_column: string, ids: string[]) { query.ids = ids; return builder },
        gt() { return builder },
        then(resolve: (value: unknown) => void) {
          server.queries.push(query)
          const credits = !query.select.includes('viewings'), full = query.select.includes('title_cast') && !credits
          const rows = server.rows.filter((row) => !query.ids || query.ids.includes(row.id as string)).map((row) => full ? row : credits
            ? { id: row.id, title_cast: row.title_cast, title_crew: row.title_crew,
              seasons: (row.seasons as Record<string, unknown>[]).map((season) => ({ id: season.id, season_cast: season.season_cast })),
              episodes: (row.episodes as Record<string, unknown>[]).map((episode) => ({ id: episode.id, episode_crew: episode.episode_crew })) }
            : { ...row, title_cast: undefined, title_crew: undefined,
              seasons: (row.seasons as Record<string, unknown>[]).map((season) => ({ ...season, season_cast: undefined })),
              episodes: (row.episodes as Record<string, unknown>[]).map((episode) => ({ ...episode, episode_crew: undefined })) })
          resolve({ data: rows, error: null })
        },
      }
      return builder
    },
  },
}))

function row(id: string): Record<string, unknown> {
  return {
    id, tmdb_id: 1, type: 'tv', title: id, year: 2020, status: 'watching', updated_at: '2026-10-01T00:00:00Z',
    title_cast: [{ tmdb_person_id: 1, name: `${id} lead`, cast_order: 0 }],
    title_crew: [{ tmdb_person_id: 2, name: `${id} creator`, job: 'Creator' }],
    seasons: [{ id: `${id}-s1`, season_number: 1, episode_count: 1, episodes_watched: 0, updated_at: '2026-10-01T00:00:00Z',
      season_cast: [{ tmdb_person_id: 3, name: `${id} guest`, cast_order: 0 }] }],
    viewings: [],
    episodes: [{ id: `${id}-e1`, season_number: 1, episode_number: 1, updated_at: '2026-10-01T00:00:00Z',
      episode_crew: [{ tmdb_person_id: 4, name: `${id} director`, job: 'Director' }],
      episode_watch_events: [], episode_ratings: [], episode_reviews: [] }],
  }
}

beforeEach(() => {
  server.queries = []
  server.rows = [row('a'), row('b')]
})

describe('fetchUserLibrary credit plans', () => {
  it('reads every credit in one select without a plan', async () => {
    const library = await fetchUserLibrary('owner')
    expect(library.creditsFor).toBeUndefined()
    expect(server.queries).toHaveLength(1)
    expect(library.titles.map((title) => title.cast?.[0]?.name)).toEqual(['a lead', 'b lead'])
  })

  it('reads credits only for the planned titles and reports which', async () => {
    const plan = vi.fn(() => ['b'])
    const library = await fetchUserLibrary('owner', plan)
    expect(plan).toHaveBeenCalledWith(expect.arrayContaining([expect.objectContaining({ id: 'a', cast: [] })]), expect.objectContaining({ 'titles:a': '2026-10-01T00:00:00Z', 'episodes:a-e1': '2026-10-01T00:00:00Z' }))
    expect(library.creditsFor).toEqual(['b'])
    const [core, credits] = server.queries
    // Embed modifiers are only legal on a select that embeds the credits.
    expect(core.referenced).toEqual([])
    expect(credits.ids).toEqual(['b'])
    const [a, b] = library.titles
    expect(a.cast).toEqual([])
    expect(b.cast?.[0]?.name).toBe('b lead')
    expect(b.crew?.[0]?.name).toBe('b creator')
    expect(b.seasons?.[0]?.cast?.[0]?.name).toBe('b guest')
    expect(b.seasons?.[0]?.episodes?.[0]).toMatchObject({ director: 'b director', crew: [{ name: 'b director', job: 'Director' }] })
    expect(library.rowRevisions?.['titles:a']).toBe('2026-10-01T00:00:00Z')
  })

  it('skips the credit request when no title needs credits', async () => {
    const library = await fetchUserLibrary('owner', () => [])
    expect(server.queries).toHaveLength(1)
    expect(library.creditsFor).toEqual([])
  })

  it('treats a plan of all as a full, authoritative credit read', async () => {
    const library = await fetchUserLibrary('owner', () => 'all')
    expect(library.creditsFor).toBeUndefined()
    expect(server.queries[1].ids).toBeUndefined()
    expect(library.titles.map((title) => title.seasons?.[0]?.cast?.[0]?.name)).toEqual(['a guest', 'b guest'])
  })
})
