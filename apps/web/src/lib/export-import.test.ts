import { describe, expect, it } from 'vitest'
import { parseImportFile } from './export-import'

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
})
