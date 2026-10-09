import { beforeEach, expect, it, vi } from 'vitest'
import { fetchDiscover } from './media'

const { invoke } = vi.hoisted(() => ({ invoke: vi.fn() }))
vi.mock('./auth', () => ({ isSupabaseConfigured: true, supabase: { functions: { invoke } } }))
beforeEach(() => invoke.mockReset())

it.each([1, 2, 3])('requests both genre endpoints on page %i and preserves movie/TV ID namespaces', async (page) => {
  invoke.mockImplementation(async (url: string) => ({ data: { results: [{ id: 42, title: 'Movie', name: 'Series' }] }, error: null, url }))
  const results = await fetchDiscover('all', 18, page)
  expect(invoke.mock.calls.map(([url]) => String(url))).toEqual([
    `media-proxy?action=discover&type=movie&page=${page}&genre=18`, `media-proxy?action=discover&type=tv&page=${page}&genre=18`,
  ])
  expect(results.map(({ type, tmdbId }) => [type, tmdbId])).toEqual([['movie', 42], ['tv', 42]])
})

it('continues when one feed is empty and rejects the whole page if either feed fails', async () => {
  invoke.mockResolvedValueOnce({ data: { results: [] }, error: null }).mockResolvedValueOnce({ data: { results: [{ id: 7, name: 'Still more TV' }] }, error: null })
  expect((await fetchDiscover('all', 18, 2)).map((result) => result.type)).toEqual(['tv'])
  invoke.mockResolvedValueOnce({ data: { results: [{ id: 8, title: 'Partial movie' }] }, error: null }).mockResolvedValueOnce({ data: null, error: new Error('TV unavailable') })
  await expect(fetchDiscover('all', 18, 3)).rejects.toThrow('TV unavailable')
})
