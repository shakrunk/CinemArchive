import { describe, expect, it } from 'vitest'
import { createCommand, type Mutation } from './commands'
import { applyMutation, replayPending } from './replay'
import { assertCommand, assertMutation, assertSnapshot } from './validation'
import { outing, owner, snapshot, stamp, title } from './fixtures.test-support'

describe('durable tracking commands', () => {
  it('refreshes season metadata without losing newer logs or omitted episodes', () => {
    const season = title.seasons![0]
    const episode = season.episodes![0]
    const saved = { ...episode, watchEvents: [{ id: 'watch-mobile' }], ratings: [{ id: 'rating-mobile', rating: 5, ratedAt: stamp }] }
    const base = { ...snapshot(), titles: [{ ...title, seasons: [{ ...season, episodes: [saved,
      { ...episode, id: 'missing-episode', episodeNumber: 2 }], episodesWatched: 1 }] }] }
    const result = applyMutation(base, { kind: 'season.put', titleId: title.id,
      season: { ...season, episodes: [{ ...episode, episodeName: 'Refreshed name' }], episodesWatched: 0 } })
    expect(result.titles[0].seasons![0].episodes).toHaveLength(2)
    expect(result.titles[0].seasons![0].episodes![0]).toMatchObject({ episodeName: 'Refreshed name', watchEvents: saved.watchEvents, ratings: saved.ratings })
    expect(result.titles[0].seasons![0].episodesWatched).toBe(1)
  })

  it('round-trips explicit clears and rejects undefined patch intent', () => {
    const command = createCommand(owner, { kind: 'outing.patch', outingId: outing.id, patch: { venue: null, completedViewingId: null } })
    const roundTrip = JSON.parse(JSON.stringify(command))
    assertCommand(roundTrip)
    if (roundTrip.mutation.kind !== 'outing.patch') throw new Error('Expected outing patch')
    expect(roundTrip.mutation.patch).toEqual({ venue: null, completedViewingId: null })
    expect(() => createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { rating: undefined } })).toThrow('Use null')
    expect(() => createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { status: null } } as unknown as Mutation)).toThrow('Invalid')
  })

  it('captures immutable payloads and preserves episode IDs and event time', () => {
    const mutation: Mutation = { kind: 'episode.log', titleId: title.id, episodeId: 'episode-a',
      watchEvent: { id: 'watch-a', watchedAt: undefined },
      rating: { id: 'rating-a', rating: 4.5, ratedAt: stamp },
      review: { id: 'review-a', reviewText: 'Lovely', reviewedAt: stamp },
    }
    const command = createCommand(owner, mutation, { id: 'command-a', createdAt: stamp })
    mutation.rating!.rating = 1
    const restored = JSON.parse(JSON.stringify(command))
    assertCommand(restored)
    const once = replayPending(snapshot(), [restored])
    const twice = replayPending(once, [restored])
    expect(twice).toEqual(once)
    expect(twice.titles[0].seasons![0].episodesWatched).toBe(1)
    expect(twice.titles[0].seasons![0].episodes![0].ratings).toEqual([{ id: 'rating-a', rating: 4.5, ratedAt: stamp }])
    expect(twice.titles[0].seasons![0].episodes![0].reviews[0].id).toBe('review-a')
  })

  it('replays edits over refreshed data without replacing unrelated viewings', () => {
    const base = snapshot()
    base.titles = [{ ...title, notes: 'old', viewings: [
      { id: 'v-a', titleId: title.id, rating: 1, notes: 'keep' },
      { id: 'v-from-mobile', titleId: title.id, rating: 5 },
    ] }]
    const changed = applyMutation(base, { kind: 'batch', mutations: [
      { kind: 'title.patch', titleId: title.id, patch: { notes: null } },
      { kind: 'viewing.patch', titleId: title.id, viewingId: 'v-a', patch: { rating: 4 } },
    ] })
    expect(changed.titles[0].notes).toBeUndefined()
    expect(changed.titles[0].viewings).toEqual([{ id: 'v-a', titleId: title.id, rating: 4, notes: 'keep' }, { id: 'v-from-mobile', titleId: title.id, rating: 5 }])
    expect(base.titles[0].notes).toBe('old')
    expect(base.titles[0].viewings[0].rating).toBe(1)
  })

  it('restores missing creation children while preserving already-delivered server rows', () => {
    const pendingTitle = { ...title, rating: 1,
      viewings: [{ id: 'v-existing', titleId: title.id, rating: 1 }, { id: 'v-missing', titleId: title.id, rating: 3 }],
      cast: [{ tmdbPersonId: 1, name: 'Original name', order: 1 }, { tmdbPersonId: 2, name: 'Missing cast', order: 2 }],
      seasons: [{ ...title.seasons![0], episodes: [
        { ...title.seasons![0].episodes![0], ratings: [{ id: 'r-existing', rating: 1, ratedAt: stamp }, { id: 'r-missing', rating: 2, ratedAt: stamp }],
          watchEvents: [{ id: 'w-missing' }], reviews: [{ id: 'review-missing', reviewText: 'Saved offline', reviewedAt: stamp }] },
        { id: 'episode-missing', episodeNumber: 2, watchEvents: [], ratings: [], reviews: [] },
      ] }, { id: 'season-missing', seasonNumber: 2, episodeCount: 1, episodesWatched: 0 }],
    }
    const serverTitle = { ...title, rating: 5, notes: 'Changed on mobile',
      viewings: [{ id: 'v-existing', titleId: title.id, rating: 5 }],
      cast: [{ tmdbPersonId: 1, name: 'Updated name', order: 1 }],
      seasons: [{ ...title.seasons![0], episodes: [{ ...title.seasons![0].episodes![0],
        ratings: [{ id: 'r-existing', rating: 5, ratedAt: stamp }],
      }] }],
    }
    const base = { ...snapshot(), titles: [serverTitle] }
    const mutation: Mutation = { kind: 'title.create', title: pendingTitle }
    const result = applyMutation(base, mutation)
    const merged = result.titles[0]
    expect(merged.rating).toBe(5)
    expect(merged.notes).toBe('Changed on mobile')
    expect(merged.viewings.map((v) => v.rating)).toEqual([5, 3])
    expect(merged.cast!.map((c) => c.name)).toEqual(['Updated name', 'Missing cast'])
    expect(merged.seasons).toHaveLength(2)
    expect(merged.seasons![0].episodes).toHaveLength(2)
    expect(merged.seasons![0].episodesWatched).toBe(1)
    expect(merged.seasons![0].episodes![0].ratings.map((r) => r.rating)).toEqual([5, 2])
    expect(merged.seasons![0].episodes![0].reviews[0].id).toBe('review-missing')
    expect(applyMutation(result, mutation)).toEqual(result)
    expect(base.titles[0].seasons[0].episodes).toHaveLength(1)
  })

  it('replays dependent list, outing, pin, and layout mutations, then title cascade', () => {
    const change: Mutation = { kind: 'batch', mutations: [
      { kind: 'list.create', list: { id: 'list-a', name: 'Films', description: 'old', createdAt: stamp, updatedAt: stamp } },
      { kind: 'list.patch', listId: 'list-a', patch: { description: null }, updatedAt: stamp },
      { kind: 'membership.set', listId: 'list-a', titleId: title.id, present: true },
      { kind: 'outing.create', outing },
      { kind: 'pin.set', titleId: title.id, easterEggKey: 'noir', variant: 'bw' },
      { kind: 'ledger.set', widgets: [] },
    ] }
    const changed = applyMutation(snapshot(), change)
    assertSnapshot(changed)
    expect(applyMutation(changed, change)).toEqual(changed)
    expect(changed.lists[0].description).toBeNull()
    expect(changed.listMemberships['list-a']).toEqual([title.id])
    expect(changed.pinnedModes[`${title.id}:noir`]).toBe('bw')
    const removed = applyMutation(changed, { kind: 'title.delete', titleId: title.id })
    expect(removed.titles).toEqual([])
    expect(removed.outings).toEqual([])
    expect(removed.listMemberships['list-a']).toEqual([])
    expect(removed.pinnedModes).toEqual({})
    expect(changed.titles).toHaveLength(1)
  })

  it('replays sequences, not timestamps, and does not mutate the caller array', () => {
    const first = { ...createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { rating: 2 } }), sequence: 1 }
    const second = { ...createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { rating: 5 } }), sequence: 2 }
    const pending = [second, first]
    expect(replayPending(snapshot(), pending).titles[0].rating).toBe(5)
    expect(pending[0]).toBe(second)
  })

  it('rejects unknown commands, functions, malformed logs, and prototype keys', () => {
    for (const invalid of [
      { kind: 'arbitrary.rpc', payload: {} },
      { kind: 'episode.log', titleId: title.id, episodeId: 'episode-a', rating: { rating: 5 } },
      { kind: 'title.patch', titleId: title.id, patch: { notes: () => 'unsafe' } },
      { kind: 'title.patch', titleId: title.id, patch: { toString: 'not a title field' } },
      { kind: 'title.patch', titleId: title.id, patch: { hasOwnProperty: 'not a title field' } },
      JSON.parse('{"kind":"title.patch","titleId":"title-a","patch":{"__proto__":{"notes":"unsafe"}}}'),
    ]) expect(() => assertMutation(invalid)).toThrow()
    expect(() => assertCommand({ ...createCommand(owner, { kind: 'title.delete', titleId: title.id }), version: 42 })).toThrow()
    expect(() => createCommand(owner, { kind: 'title.delete', titleId: title.id }, { preconditions: [{ table: 'titles', id: 'unrelated', updatedAt: stamp }] })).toThrow('precondition')
    expect(() => createCommand(owner, { kind: 'title.delete', titleId: title.id }, { id: 'self', preconditions: [{ table: 'titles', id: title.id, afterCommandId: 'self' }] })).toThrow('precondition')
  })
})
