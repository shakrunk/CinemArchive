import { beforeEach, expect, it, vi } from 'vitest'
import { fetchMediaDetails, fetchSeasonDetails, MAX_STORED_CAST } from './media'

const { invoke } = vi.hoisted(() => ({ invoke: vi.fn() }))
vi.mock('./auth', () => ({ isSupabaseConfigured: true, supabase: { functions: { invoke } } }))
beforeEach(() => invoke.mockReset())

// Billed in reverse so the cap has to sort before it slices.
const castOf = (size: number) => Array.from({ length: size }, (_, i) => ({ id: size - i, name: `Actor ${size - i}`, order: size - i }))
const topBilled = Array.from({ length: MAX_STORED_CAST }, (_, i) => i + 1)

it('keeps only the top-billed members of a long TV aggregate cast', async () => {
  invoke.mockResolvedValue({ data: { name: 'Series', aggregate_credits: { cast: castOf(MAX_STORED_CAST + 20) } }, error: null })
  const details = await fetchMediaDetails({ tmdbId: 1, type: 'tv', title: 'Series', year: 2020, genres: [] })
  expect(details.result.cast?.map((member) => member.tmdbPersonId)).toEqual(topBilled)
})

it('caps season cast the same way', async () => {
  invoke.mockResolvedValue({ data: { episodes: [], credits: { cast: castOf(MAX_STORED_CAST + 5) } }, error: null })
  const { cast } = await fetchSeasonDetails(1, 1)
  expect(cast.map((member) => member.tmdbPersonId)).toEqual(topBilled)
})
