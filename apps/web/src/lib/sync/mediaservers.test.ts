import { describe, expect, it } from 'vitest'
import { mapPlexItems } from './plex'
import { mapEmbyItems, normalizeEmbyUrl } from './emby'

describe('mapPlexItems', () => {
  it('imports watched/rated items with guids and skips untouched ones', () => {
    const out = mapPlexItems([
      { ratingKey: '1', type: 'movie', title: 'Heat', year: 1995, userRating: 8, viewCount: 2, lastViewedAt: 1714600000, Guid: [{ id: 'tmdb://949' }, { id: 'imdb://tt0113277' }] },
      { ratingKey: '2', type: 'movie', title: 'Unwatched', Guid: [] },
      { ratingKey: '3', type: 'show', title: 'Severance', viewCount: 1, Guid: [{ id: 'tvdb://371980' }] },
    ])
    expect(out).toHaveLength(2)
    expect(out[0]).toMatchObject({ rating: 4, status: 'watched', ids: { tmdb: 949, imdb: 'tt0113277' }, watchedDates: ['2024-05-01'] })
    expect(out[1]).toMatchObject({ type: 'tv', watchedDates: [], ids: { tvdb: 371980 } })
  })
})

describe('mapEmbyItems', () => {
  it('maps ProviderIds and played state', () => {
    const out = mapEmbyItems([
      { Id: 'a', Type: 'Movie', Name: 'Heat', ProductionYear: 1995, ProviderIds: { Tmdb: '949', Imdb: 'tt0113277' }, UserData: { Played: true, LastPlayedDate: '2024-05-01T10:00:00Z' } },
      { Id: 'b', Type: 'Movie', Name: 'Nope', UserData: { Played: false } },
    ])
    expect(out).toHaveLength(1)
    expect(out[0]).toMatchObject({ externalId: 'a', ids: { tmdb: 949, imdb: 'tt0113277' }, watchedDates: ['2024-05-01'] })
  })
})

describe('normalizeEmbyUrl', () => {
  it('adds https, trims slashes and /emby', () => {
    expect(normalizeEmbyUrl('media.example.com/')).toBe('https://media.example.com')
    expect(normalizeEmbyUrl('https://x.test:8920/emby')).toBe('https://x.test:8920')
  })
  it('rejects empty input', () => {
    expect(() => normalizeEmbyUrl('  ')).toThrow()
  })
})
