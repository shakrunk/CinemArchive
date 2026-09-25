import { expect, it } from 'vitest'
import { toCachedTitle } from './libraryCache'
import type { Title } from './mockData'

const title: Title = {
  id: 't1', tmdbId: 1, type: 'tv', title: 'The Long Reel', year: 2026,
  genres: ['Drama'], tags: [], status: 'watching', addedAt: '2026-01-01', viewings: [],
  synopsis: 'Kept for the drawer',
  cast: [{ tmdbPersonId: 1, name: 'Lead', order: 0, character: 'Hero', profileUrl: 'https://img/1.jpg' }],
  crew: [{ tmdbPersonId: 2, name: 'Auteur', job: 'Director', department: 'Directing', profileUrl: 'https://img/2.jpg' }],
  seasons: [{
    id: 's1', seasonNumber: 1, episodeCount: 1, episodesWatched: 1,
    cast: [{ tmdbPersonId: 3, name: 'Guest', order: 1, episodeCount: 4, profileUrl: 'https://img/3.jpg' }],
    episodes: [{
      id: 'e1', episodeNumber: 1, episodeName: 'Pilot', airDate: '2026-01-01', runtime: 50, director: 'Auteur',
      synopsis: 'A long synopsis', stillUrl: 'https://img/still.jpg', writers: ['Writer'],
      crew: [{ tmdbPersonId: 4, name: 'Writer', job: 'Writer' }],
      watchEvents: [{ id: 'w1', watchedAt: '2026-01-02' }], ratings: [{ id: 'r1', rating: 4, ratedAt: '2026-01-02' }], reviews: [],
    }],
  }],
}

it('keeps user data, identity and search fields while dropping display-only metadata', () => {
  const cached = toCachedTitle(title)
  expect(cached).toMatchObject({ id: 't1', synopsis: 'Kept for the drawer', status: 'watching' })
  expect(cached.cast).toEqual([{ tmdbPersonId: 1, name: 'Lead', order: 0 }])
  expect(cached.crew).toEqual([{ tmdbPersonId: 2, name: 'Auteur', job: 'Director' }])
  expect(cached.seasons![0].cast).toEqual([{ tmdbPersonId: 3, name: 'Guest', order: 1 }])
  expect(cached.seasons![0].episodes![0]).toEqual({
    id: 'e1', episodeNumber: 1, episodeName: 'Pilot', airDate: '2026-01-01', runtime: 50, director: 'Auteur',
    watchEvents: [{ id: 'w1', watchedAt: '2026-01-02' }], ratings: [{ id: 'r1', rating: 4, ratedAt: '2026-01-02' }], reviews: [],
  })
  expect(title.seasons![0].episodes![0].synopsis).toBe('A long synopsis')
})

it('leaves movies without credits or seasons untouched', () => {
  const movie: Title = { ...title, type: 'movie', cast: undefined, crew: undefined, seasons: undefined }
  expect(toCachedTitle(movie)).toEqual(movie)
})
