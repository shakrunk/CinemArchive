import { describe, expect, it } from 'vitest'
import { parseImportFile } from './export-import'
import type { Episode } from '../store/mockData'

function importFile(payload: unknown): File {
  return new File([JSON.stringify(payload)], 'export.json', { type: 'application/json' })
}

const baseTitle = {
  id: 'old-title-1',
  tmdbId: 603,
  type: 'movie',
  title: 'The Matrix',
  year: 1999,
  genres: [],
  tags: [],
  status: 'watched',
  addedAt: '2026-01-01',
}

describe('parseImportFile', () => {
  it('rejects a file that is not valid JSON', async () => {
    const file = new File(['not json'], 'export.json', { type: 'application/json' })
    await expect(parseImportFile(file)).rejects.toThrow('Invalid file')
  })

  it('rejects an envelope missing the titles array', async () => {
    const file = importFile({ version: 1, exportedAt: '2026-01-01' })
    await expect(parseImportFile(file)).rejects.toThrow('expected a "titles" array')
  })

  it('rejects an entry missing required fields', async () => {
    const file = importFile({ titles: [{ title: 'No TMDB ID' }] })
    await expect(parseImportFile(file)).rejects.toThrow('missing required fields')
  })

  it('tolerates an older export with no outings key', async () => {
    const file = importFile({ titles: [{ ...baseTitle, viewings: [] }] })
    const { titles, outings } = await parseImportFile(file)
    expect(titles).toHaveLength(1)
    expect(outings).toEqual([])
  })

  it('regenerates title and viewing IDs, remapping viewings.titleId to the new title', async () => {
    const file = importFile({
      titles: [
        {
          ...baseTitle,
          viewings: [{ id: 'old-viewing-1', titleId: 'old-title-1', date: '2026-01-01' }],
        },
      ],
    })
    const { titles } = await parseImportFile(file)
    expect(titles[0].id).not.toBe('old-title-1')
    expect(titles[0].viewings[0].id).not.toBe('old-viewing-1')
    expect(titles[0].viewings[0].titleId).toBe(titles[0].id)
  })

  it('remaps an outing.titleId to the regenerated title ID', async () => {
    const file = importFile({
      titles: [{ ...baseTitle, viewings: [] }],
      outings: [
        {
          id: 'old-outing-1',
          titleId: 'old-title-1',
          showtime: '2026-07-17T19:30:00.000Z',
          previewsMinutes: 20,
          runtimeMinutes: 136,
          endsAt: '2026-07-17T22:06:00.000Z',
          companions: [],
          status: 'scheduled',
          createdAt: '2026-07-16T00:00:00.000Z',
        },
      ],
    })
    const { titles, outings } = await parseImportFile(file)
    expect(outings).toHaveLength(1)
    expect(outings[0].id).not.toBe('old-outing-1')
    expect(outings[0].titleId).toBe(titles[0].id)
  })

  it('survives ID regeneration on both sides of the outing⇄viewing link', async () => {
    const file = importFile({
      titles: [
        {
          ...baseTitle,
          viewings: [
            { id: 'old-viewing-1', titleId: 'old-title-1', date: '2026-07-17', outingId: 'old-outing-1' },
          ],
        },
      ],
      outings: [
        {
          id: 'old-outing-1',
          titleId: 'old-title-1',
          showtime: '2026-07-17T19:30:00.000Z',
          previewsMinutes: 20,
          runtimeMinutes: 136,
          endsAt: '2026-07-17T22:06:00.000Z',
          companions: [{ name: 'Alex' }],
          status: 'completed',
          completedViewingId: 'old-viewing-1',
          createdAt: '2026-07-16T00:00:00.000Z',
        },
      ],
    })
    const { titles, outings } = await parseImportFile(file)
    const viewing = titles[0].viewings[0]
    const outing = outings[0]

    // Both back-references now point at each other's *regenerated* IDs.
    expect(outing.completedViewingId).toBe(viewing.id)
    expect(viewing.outingId).toBe(outing.id)
  })

  it('leaves a viewing with no outingId untouched by the remap pass', async () => {
    const file = importFile({
      titles: [
        { ...baseTitle, viewings: [{ id: 'old-viewing-1', titleId: 'old-title-1', date: '2026-01-01' }] },
      ],
    })
    const { titles } = await parseImportFile(file)
    expect(titles[0].viewings[0].outingId).toBeUndefined()
  })

  const episode: Episode = {
    id: 'episode', episodeNumber: 9, episodeName: 'A special',
    crew: [{ tmdbPersonId: 42, name: 'Writer', job: 'Writer' }],
    watchEvents: [
      { id: 'watch-before', notes: 'Before joining', colorMode: 'bw' },
      { id: 'watch-after', watchedAt: '2026-10-01', notes: 'Rewatch', colorMode: 'color' },
    ],
    ratings: [{ id: 'rating', rating: 4.5, ratedAt: '2026-10-02T12:00:00Z' }],
    reviews: [{ id: 'review', reviewText: 'Keep my review', reviewedAt: '2026-10-03T12:00:00Z', colorMode: 'bw' }],
  }
  const season = { id: 'season', seasonNumber: 0, episodeCount: 1, episodesWatched: 1, episodes: [episode] }
  const series = { ...baseTitle, type: 'tv', seasons: [season], viewings: [] }

  it('imports a fresh identity for every nested history row without changing its contents', async () => {
    const file = importFile({ version: 1, titles: [series] })
    const first = (await parseImportFile(file)).titles[0]
    const second = (await parseImportFile(file)).titles[0]
    const nested = first.seasons![0].episodes![0]
    expect(first.seasons![0].id).not.toBe(season.id)
    expect(nested.id).not.toBe(episode.id)
    expect(nested).toEqual({
      ...episode, id: expect.any(String),
      watchEvents: episode.watchEvents.map((row) => ({ ...row, id: expect.any(String) })),
      ratings: episode.ratings.map((row) => ({ ...row, id: expect.any(String) })),
      reviews: episode.reviews.map((row) => ({ ...row, id: expect.any(String) })),
    })
    const historyIds = (value: typeof nested) => [value.id, ...value.watchEvents.map((row) => row.id),
      ...value.ratings.map((row) => row.id), ...value.reviews.map((row) => row.id)]
    const imported = historyIds(nested)
    const original = historyIds(episode)
    const again = historyIds(second.seasons![0].episodes![0])
    expect(new Set(imported).size).toBe(imported.length)
    expect(imported.some((id) => original.includes(id) || again.includes(id))).toBe(false)
    expect(nested.watchEvents[0].watchedAt).toBeUndefined()
    expect(JSON.parse(await file.text()).titles[0]).toEqual(series)
  })

  it.each(['episode', 'episode watch', 'episode rating', 'episode review'])(
    'rejects duplicate %s identities across seasons before admitting an import', async (kind) => {
      const other = JSON.parse(JSON.stringify(episode))
      other.id = 'other-episode'
      other.watchEvents.forEach((row: { id: string }) => { row.id += '-other' })
      other.ratings[0].id += '-other'
      other.reviews[0].id += '-other'
      if (kind === 'episode') other.id = episode.id
      if (kind === 'episode watch') other.watchEvents[1].id = episode.watchEvents[0].id
      if (kind === 'episode rating') other.ratings[0].id = episode.ratings[0].id
      if (kind === 'episode review') other.reviews[0].id = episode.reviews[0].id
      await expect(parseImportFile(importFile({ titles: [{ ...series,
        seasons: [season, { ...season, id: 'other-season', seasonNumber: 1, episodes: [other] }],
      }] }))).rejects.toThrow(`Duplicate ${kind} identity`)
    },
  )

  it('rejects duplicate top-level identities that would make outing links ambiguous', async () => {
    await expect(parseImportFile(importFile({ titles: [baseTitle, { ...baseTitle, tmdbId: 604 }] })))
      .rejects.toThrow('Duplicate title identity')
    await expect(parseImportFile(importFile({ titles: [{ ...baseTitle,
      viewings: [{ id: 'same' }, { id: 'same' }],
    }] }))).rejects.toThrow('Duplicate viewing identity')
  })

  it('retains coarse seasons and initializes absent legacy episode histories as empty', async () => {
    const { titles } = await parseImportFile(importFile({ titles: [{ ...series, seasons: [
      { ...season, episodes: undefined },
      { ...season, id: 'detail', seasonNumber: 1, episodes: [{ id: 'old', episodeNumber: 1 }] },
    ] }] }))
    expect(titles[0].seasons![0].episodes).toBeUndefined()
    expect(titles[0].seasons![0].episodesWatched).toBe(1)
    expect(titles[0].seasons![1].episodes![0]).toMatchObject({ watchEvents: [], ratings: [], reviews: [] })
  })
})
