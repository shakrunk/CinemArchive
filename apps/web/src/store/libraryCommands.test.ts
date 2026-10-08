import { describe, expect, it } from 'vitest'
import { title, stamp, snapshot } from '../lib/offline/fixtures.test-support'
import { createCommand } from '../lib/offline/commands'
import { applyMutation } from '../lib/offline/replay'
import { explicitPatch, titlePatchCommand } from './libraryCommands'
import { collectRowRevisions } from '../lib/offline/preconditions'

describe('UI command conversion', () => {
  it('keeps server revisions alongside nested domain rows before mapping drops them', () => {
    expect(collectRowRevisions([{ id: 't', updated_at: stamp, seasons: [{ id: 's', updated_at: stamp }],
      viewings: [{ id: 'v', updated_at: stamp }], episodes: [{ id: 'ep', updated_at: stamp, episode_watch_events: [{ id: 'w', updated_at: stamp }] }],
    }], [{ id: 'o', updated_at: stamp }])).toEqual({
      'titles:t': stamp, 'seasons:s': stamp, 'viewings:v': stamp, 'episodes:ep': stamp, 'episode_watch_events:w': stamp, 'cinema_outings:o': stamp,
    })
  })

  it('distinguishes omitted fields from explicit undefined clears', () => {
    expect(explicitPatch({ notes: undefined, rating: 4 })).toEqual({ notes: null, rating: 4 })
    const mutation = titlePatchCommand(title, { notes: undefined, cast: [{ tmdbPersonId: 4, name: 'Actor', order: 1 }] })!
    expect(mutation).toMatchObject({ kind: 'title.patch', patch: { notes: null, cast: [{ name: 'Actor' }] } })
    expect(() => createCommand({ projectId: 'project', userId: 'owner' }, mutation)).not.toThrow()
  })

  it('makes viewing edits and title rating a compound action with explicit clears', () => {
    const before = { ...title, viewings: [{ id: 'v1', titleId: title.id, notes: 'old', rating: 2 }, { id: 'v2', titleId: title.id }] }
    const mutation = titlePatchCommand(before, { rating: 4, viewings: [{ id: 'v1', titleId: title.id, rating: 4 }, { id: 'v3', titleId: title.id }] })!
    expect(mutation).toEqual({ kind: 'batch', mutations: [
      { kind: 'title.patch', titleId: title.id, patch: { rating: 4 } },
      { kind: 'viewing.patch', titleId: title.id, viewingId: 'v1', patch: { notes: null, rating: 4 } },
      { kind: 'viewing.put', titleId: title.id, viewing: { id: 'v3', titleId: title.id } },
      { kind: 'viewing.delete', titleId: title.id, viewingId: 'v2' },
    ] })
  })

  it('keeps metadata refresh separate from newly appended episode history', () => {
    const seasons = structuredClone(title.seasons!)
    seasons[0].episodes![0].episodeName = 'New metadata'
    seasons[0].episodes![0].watchEvents.push({ id: 'watch-new' })
    seasons[0].episodes![0].ratings.push({ id: 'rating-new', rating: 4, ratedAt: stamp })
    const mutation = titlePatchCommand(title, { seasons })!
    const result = applyMutation(snapshot(), mutation)
    expect(result.titles[0].seasons![0].episodes![0]).toMatchObject({ episodeName: 'New metadata', watchEvents: [{ id: 'watch-new' }], ratings: [{ id: 'rating-new' }] })
    expect(result.titles[0].seasons![0].episodesWatched).toBe(1)
    expect(applyMutation(result, mutation)).toEqual(result)
  })

  it('represents coarse season progress explicitly without inventing dated logs', () => {
    const before = { ...title, seasons: [{ id: 'coarse', seasonNumber: 1, episodeCount: 10, episodesWatched: 1 }] }
    const mutation = titlePatchCommand(before, { seasons: [{ ...before.seasons[0], episodesWatched: 4 }] })!
    expect(mutation).toMatchObject({ kind: 'batch', mutations: [{ kind: 'season.put' }, { kind: 'season.progress', episodesWatched: 4 }] })
    expect(applyMutation({ ...snapshot(), titles: [before] }, mutation).titles[0].seasons![0].episodesWatched).toBe(4)
  })

  it('rejects unsupported removal of user review history', () => {
    const before = structuredClone(title)
    before.seasons![0].episodes![0].reviews = [{ id: 'review', reviewedAt: stamp, reviewText: 'Keep this' }]
    expect(() => titlePatchCommand(before, { seasons: title.seasons })).toThrow('cannot be replaced')
  })

  it('metadata refresh preserves logs added after the fetch began', () => {
    const current = structuredClone(title)
    current.seasons![0].episodes![0].watchEvents.push({ id: 'concurrent-watch' })
    const fetched = structuredClone(title.seasons!)
    fetched[0].episodes![0].episodeName = 'Refreshed'
    const mutation = titlePatchCommand(current, { seasons: fetched }, { metadataOnly: true })!
    const result = applyMutation({ ...snapshot(), titles: [current] }, mutation)
    expect(result.titles[0].seasons![0].episodes![0]).toMatchObject({ episodeName: 'Refreshed', watchEvents: [{ id: 'concurrent-watch' }] })
  })

  it('backfilled episode rows retain older coarse watch progress as dateless history', () => {
    const current = { ...title, seasons: [{ id: 'coarse', seasonNumber: 1, episodeCount: 2, episodesWatched: 1 }] }
    const fetched = [{ ...current.seasons[0], episodes: [1, 2].map((episodeNumber) => ({ id: `ep-${episodeNumber}`, episodeNumber, watchEvents: [], ratings: [], reviews: [] })) }]
    const mutation = titlePatchCommand(current, { seasons: fetched }, { metadataOnly: true })!
    const result = applyMutation({ ...snapshot(), titles: [current] }, mutation)
    expect(result.titles[0].seasons![0].episodesWatched).toBe(1)
    expect(result.titles[0].seasons![0].episodes![0].watchEvents).toHaveLength(1)
    expect(result.titles[0].seasons![0].episodes![0].watchEvents[0].watchedAt).toBeUndefined()
    expect(applyMutation(result, mutation)).toEqual(result)
  })
})
