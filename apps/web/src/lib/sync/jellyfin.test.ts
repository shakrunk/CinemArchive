import { describe, expect, it } from 'vitest'
import { mapJellyfinItems, mapJellyfinPlays, normalizeJellyfinUrl } from './jellyfin'
import { queuePlays, resolvePlay, type JellyfinState } from './jellyfinPending'
import { planEpisodeLog } from './jellyfinConfirm'
import type { Title } from '../../store/mockData'

const SINCE = '2026-10-24T00:00:00.000Z'

describe('mapJellyfinPlays', () => {
  const series = new Map([['s1', { tvdb: 371980, tmdb: 95396 }]])

  it('keeps only plays after the cursor, oldest first, with ids', () => {
    const out = mapJellyfinPlays(
      [
        { Id: 'm1', Type: 'Movie', Name: 'Heat', ProductionYear: 1995, ProviderIds: { Tmdb: '949', Imdb: 'tt0113277' }, UserData: { Played: true, LastPlayedDate: '2026-10-24T20:00:00Z' } },
        { Id: 'm2', Type: 'Movie', Name: 'Old', UserData: { Played: true, LastPlayedDate: '2026-10-01T20:00:00Z' } },
        { Id: 'm3', Type: 'Movie', Name: 'Unplayed', UserData: { Played: false } },
        { Id: 'e1', Type: 'Episode', Name: 'Hello', SeriesId: 's1', SeriesName: 'Severance', ParentIndexNumber: 1, IndexNumber: 1, UserData: { Played: true, LastPlayedDate: '2026-10-24T10:00:00Z' } },
      ],
      series,
      SINCE
    )
    expect(out.map((p) => p.title)).toEqual(['Severance', 'Heat'])
    expect(out[0]).toMatchObject({ kind: 'episode', seasonNumber: 1, episodeNumber: 1, date: '2026-10-24', ids: { tmdb: 95396 } })
    expect(out[1]).toMatchObject({ kind: 'movie', key: 'm1@2026-10-24T20:00:00Z', ids: { tmdb: 949, imdb: 'tt0113277' } })
  })

  it('skips episodes without season/episode numbers and a play exactly at the cursor', () => {
    const out = mapJellyfinPlays(
      [
        { Id: 'e2', Type: 'Episode', Name: 'Loose', UserData: { Played: true, LastPlayedDate: '2026-10-25T10:00:00Z' } },
        { Id: 'm4', Type: 'Movie', Name: 'Edge', UserData: { Played: true, LastPlayedDate: SINCE } },
      ],
      series,
      SINCE
    )
    expect(out).toEqual([])
  })
})

describe('mapJellyfinItems', () => {
  it('maps played and partly watched items', () => {
    const out = mapJellyfinItems([
      { Id: 'a', Type: 'Movie', Name: 'Heat', ProviderIds: { Tmdb: '949' }, UserData: { Played: true, LastPlayedDate: '2024-05-01T10:00:00Z' } },
      { Id: 'b', Type: 'Series', Name: 'Slow Horses', RecursiveItemCount: 30, UserData: { UnplayedItemCount: 24 } },
      { Id: 'c', Type: 'Movie', Name: 'Nope', UserData: { Played: false } },
    ])
    expect(out).toHaveLength(2)
    expect(out[0]).toMatchObject({ provider: 'jellyfin', watchedDates: ['2024-05-01'] })
    expect(out[1]).toMatchObject({ status: 'watching' })
  })
})

describe('normalizeJellyfinUrl', () => {
  it('adds https and trims slashes, keeping a base path', () => {
    expect(normalizeJellyfinUrl('media.example.com/')).toBe('https://media.example.com')
    expect(normalizeJellyfinUrl('http://nas.local:8096/jellyfin/')).toBe('http://nas.local:8096/jellyfin')
  })
  it('rejects empty input', () => {
    expect(() => normalizeJellyfinUrl(' ')).toThrow()
  })
})

describe('pending queue', () => {
  const play = (key: string, playedAt: string) => ({ key, kind: 'movie' as const, title: key, ids: {}, date: playedAt.slice(0, 10), playedAt })
  const base: JellyfinState = { session: null, cursor: SINCE, pending: [] }

  it('queues new plays once and advances the cursor', () => {
    const a = play('a', '2026-10-24T11:00:00.000Z')
    const once = queuePlays(base, [a])
    const twice = queuePlays(once, [a])
    expect(twice.pending).toHaveLength(1)
    expect(twice.cursor).toBe(a.playedAt)
  })

  it('removes a play only when it is resolved', () => {
    const queued = queuePlays(base, [play('a', '2026-10-24T11:00:00.000Z'), play('b', '2026-10-24T12:00:00.000Z')])
    expect(resolvePlay(queued, 'a').pending.map((p) => p.key)).toEqual(['b'])
  })
})

describe('planEpisodeLog', () => {
  const ep = (watchedAt?: string) => ({ id: 'e', episodeNumber: 2, watchEvents: watchedAt ? [{ id: 'w', watchedAt }] : [], ratings: [], reviews: [] })
  const title = (watchedAt?: string) =>
    ({ seasons: [{ id: 's', seasonNumber: 1, episodeCount: 1, episodesWatched: 0, episodes: [ep(watchedAt)] }] }) as unknown as Title

  it('logs a known unwatched episode', () => expect(planEpisodeLog(title(), 1, 2, '2026-10-24')).toBe('log'))
  it('treats the same day as a duplicate', () => expect(planEpisodeLog(title('2026-10-24'), 1, 2, '2026-10-24')).toBe('duplicate'))
  it('reports a missing episode', () => expect(planEpisodeLog(title(), 1, 9, '2026-10-24')).toBe('missing'))
})
