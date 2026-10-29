import { describe, expect, it } from 'vitest'
import type { Title } from '../../store/mockData'
import { assertSnapshot } from './validation'
import { emptySnapshot, mergeRefreshedSnapshot, staleCreditTitles, type OfflineSnapshot } from './snapshot'
import { stamp } from './fixtures.test-support'

const later = '2026-10-09T12:00:00.000Z'

function series(id: string, credited: boolean): Title {
  return {
    id, tmdbId: 1, type: 'tv', title: id, year: 2026, genres: [], status: 'watching', tags: [], addedAt: stamp, viewings: [],
    cast: credited ? [{ tmdbPersonId: 1, name: `${id} lead`, order: 0 }] : [],
    crew: credited ? [{ tmdbPersonId: 2, name: `${id} creator`, job: 'Creator' }] : [],
    seasons: [{ id: `${id}-s1`, seasonNumber: 1, episodeCount: 1, episodesWatched: 0,
      cast: credited ? [{ tmdbPersonId: 3, name: `${id} guest`, order: 0 }] : [],
      episodes: [{ id: `${id}-e1`, episodeNumber: 1, watchEvents: [], ratings: [], reviews: [],
        ...(credited
          ? { director: `${id} director`, writers: [`${id} writer`], crew: [{ tmdbPersonId: 4, name: `${id} director`, job: 'Director' }, { tmdbPersonId: 5, name: `${id} writer`, job: 'Writer' }] }
          : { writers: [] }) }],
    }],
  }
}

function revisions(ids: string[], at = stamp): Record<string, string> {
  return Object.fromEntries(ids.flatMap((id) => [[`titles:${id}`, at], [`seasons:${id}-s1`, at], [`episodes:${id}-e1`, at]]))
}

const cached: OfflineSnapshot = { ...emptySnapshot(), titles: [series('a', true), series('b', true)], rowRevisions: revisions(['a', 'b']) }

describe('credit carry-over', () => {
  it('keeps cached credits, including derived episode fields, for titles a partial read left out', () => {
    const incoming = { ...emptySnapshot(), titles: [{ ...series('a', false), status: 'watched' as const }, series('b', false)], creditsFor: [], rowRevisions: revisions(['a', 'b']) }
    const merged = mergeRefreshedSnapshot(cached, incoming)
    expect(merged.titles[0]).toEqual({ ...series('a', true), status: 'watched' })
    expect(merged.titles[1]).toEqual(series('b', true))
    expect(merged).not.toHaveProperty('creditsFor')
  })

  it('leaves episode fields absent, not undefined, when the cached episode has none', () => {
    const bare = { ...emptySnapshot(), titles: [series('a', false)], rowRevisions: revisions(['a']) }
    const merged = mergeRefreshedSnapshot(bare, { ...bare, creditsFor: [] })
    const episode = merged.titles[0].seasons![0].episodes![0]
    expect(episode).not.toHaveProperty('director')
    expect(episode).not.toHaveProperty('crew')
    expect(() => assertSnapshot(merged)).not.toThrow()
  })

  it('replaces credits for the titles that were read, even when they became empty', () => {
    const fresh = { ...series('b', true), cast: [{ tmdbPersonId: 9, name: 'recast', order: 0 }] }
    const merged = mergeRefreshedSnapshot(cached, { ...emptySnapshot(), titles: [series('a', false), fresh], creditsFor: ['a', 'b'] })
    expect(merged.titles[0]).toEqual(series('a', false))
    expect(merged.titles[1].cast).toEqual([{ tmdbPersonId: 9, name: 'recast', order: 0 }])
  })

  it('drops deleted titles with their credits and keeps new titles as read', () => {
    const merged = mergeRefreshedSnapshot(cached, { ...emptySnapshot(), titles: [series('c', false)], creditsFor: [] })
    expect(merged.titles).toEqual([series('c', false)])
  })

  it('treats a snapshot without the marker as authoritative for every credit', () => {
    expect(mergeRefreshedSnapshot(cached, { ...emptySnapshot(), titles: [series('a', false)] }).titles).toEqual([series('a', false)])
  })

  it('accepts the marker on a fetched snapshot', () => {
    expect(() => assertSnapshot({ ...emptySnapshot(), creditsFor: ['a'] })).not.toThrow()
  })
})

describe('staleCreditTitles', () => {
  const incoming = [series('a', false), series('b', false), series('c', false)]

  it('names new titles and titles read against different revisions', () => {
    expect(staleCreditTitles(cached, incoming, { ...revisions(['a', 'c']), ...revisions(['b'], later) })).toEqual(['b', 'c'])
  })

  it('treats a changed season or episode revision, or a new episode, as stale', () => {
    expect(staleCreditTitles(cached, incoming.slice(0, 2), { ...revisions(['a', 'b']), 'episodes:a-e1': later })).toEqual(['a'])
    const grown = { ...series('b', false), seasons: [{ ...series('b', false).seasons![0], episodes: [...series('b', false).seasons![0].episodes!, { id: 'b-e2', episodeNumber: 2, watchEvents: [], ratings: [], reviews: [] }] }] }
    expect(staleCreditTitles(cached, [series('a', false), grown], { ...revisions(['a', 'b']), 'episodes:b-e2': later })).toEqual(['b'])
  })

  it('adds titles a delivered command rewrote', () => {
    expect(staleCreditTitles(cached, incoming.slice(0, 2), revisions(['a', 'b']), ['a'])).toEqual(['a'])
  })
})
