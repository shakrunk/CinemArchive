import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAppStore } from './useAppStore'
import type { CinemaOuting, Title } from './mockData'
import type { AppNotificationItem } from '../lib/db'

function makeTitle(overrides: Partial<Title> = {}): Title {
  return {
    id: 't1',
    tmdbId: 1,
    type: 'movie',
    title: 'The Long Reel',
    year: 2026,
    genres: [],
    tags: [],
    status: 'watched',
    addedAt: '2026-01-01',
    viewings: [],
    ...overrides,
  }
}

function makeOuting(overrides: Partial<CinemaOuting> = {}): CinemaOuting {
  return {
    id: 'o1',
    titleId: 't1',
    showtime: '2026-07-17T19:30:00.000Z',
    previewsMinutes: 20,
    runtimeMinutes: 136,
    endsAt: '2026-07-17T22:06:00.000Z',
    companions: [{ name: 'Alex' }],
    seats: [],
    status: 'completed',
    previousStatus: 'watchlist',
    completedViewingId: 'v1',
    createdAt: '2026-07-16T00:00:00.000Z',
    ...overrides,
  }
}

function makeNotification(overrides: Partial<AppNotificationItem> = {}): AppNotificationItem {
  return {
    id: 'n1',
    type: 'outing_completed',
    actorId: null,
    actorDisplayName: null,
    actorUsername: null,
    titleId: 't1',
    tmdbId: 1,
    mediaType: 'movie',
    title: 'The Long Reel',
    posterUrl: null,
    payload: { outingId: 'o1' },
    createdAt: '2026-07-17T22:10:00.000Z',
    readAt: null,
    ...overrides,
  }
}

async function seedState(patch: Partial<ReturnType<typeof useAppStore.getState>>) {
  if (patch.titles) await useAppStore.getState().setTitles(patch.titles)
  if (patch.outings) await useAppStore.getState().setOutings(patch.outings)
  const rest = { ...patch }
  delete rest.titles
  delete rest.outings
  useAppStore.setState(rest)
}

beforeEach(async () => {
  useAppStore.getState().setUser(null)
  await vi.waitFor(() => expect(useAppStore.getState().offlineStatus.hydrated).toBe(true))
  await useAppStore.getState().setTitles([])
  await useAppStore.getState().setOutings([])
  useAppStore.setState({ notificationInbox: [], unreadNotificationCount: 0 })
})

describe('revertOutingCompletion ("Didn\'t make it")', async () => {
  it('removes the identified viewing but retains title status until a server revision proves restoration is safe', async () => {
    const viewing = { id: 'v1', titleId: 't1', date: '2026-07-17', venue: 'AMC Georgetown' }
    await seedState({
      titles: [makeTitle({ status: 'watched', viewings: [viewing] })],
      outings: [makeOuting()],
    })

    await useAppStore.getState().revertOutingCompletion('o1')

    const title = useAppStore.getState().titles.find((t) => t.id === 't1')!
    expect(title.viewings).toEqual([])
    expect(title.status).toBe('watched') // Matching status alone cannot rule out a later intentional write.

    const outing = useAppStore.getState().outings.find((o) => o.id === 'o1')!
    expect(outing.status).toBe('missed')
    expect(outing.completedViewingId).toBeUndefined()
  })

  it('leaves the title status alone when the user changed it manually since completion', async () => {
    const viewing = { id: 'v1', titleId: 't1', date: '2026-07-17' }
    await seedState({
      titles: [makeTitle({ status: 'watching', viewings: [viewing] })], // no longer 'watched'
      outings: [makeOuting()],
    })

    await useAppStore.getState().revertOutingCompletion('o1')

    const title = useAppStore.getState().titles.find((t) => t.id === 't1')!
    expect(title.status).toBe('watching') // untouched
    expect(title.viewings).toEqual([]) // the auto-logged viewing is still gone
  })

  it('is a no-op for an outing that is not completed', async () => {
    await seedState({
      titles: [makeTitle({ status: 'watchlist' })],
      outings: [makeOuting({ status: 'scheduled', completedViewingId: undefined })],
    })

    await useAppStore.getState().revertOutingCompletion('o1')

    expect(useAppStore.getState().outings[0].status).toBe('scheduled')
  })

  it('preserves rated history and refuses to queue a reversal for it', async () => {
    await seedState({ titles: [makeTitle({ viewings: [{ id: 'v1', titleId: 't1', rating: 4 }] })], outings: [makeOuting()] })
    await expect(useAppStore.getState().revertOutingCompletion('o1')).rejects.toThrow('rating')
    expect(useAppStore.getState().outings[0].status).toBe('completed')
    expect(useAppStore.getState().titles[0].viewings[0].rating).toBe(4)
  })

  it('drops the matching stale outing_completed notification from the inbox', async () => {
    await seedState({
      titles: [makeTitle({ status: 'watched', viewings: [{ id: 'v1', titleId: 't1', date: '2026-07-17' }] })],
      outings: [makeOuting()],
      notificationInbox: [makeNotification({ id: 'n1', titleId: 't1' })],
      unreadNotificationCount: 1,
    })

    await useAppStore.getState().revertOutingCompletion('o1')

    expect(useAppStore.getState().notificationInbox).toEqual([])
    expect(useAppStore.getState().unreadNotificationCount).toBe(0)
  })

  it('leaves unrelated notifications (a different title) untouched', async () => {
    await seedState({
      titles: [makeTitle({ status: 'watched', viewings: [{ id: 'v1', titleId: 't1', date: '2026-07-17' }] })],
      outings: [makeOuting()],
      notificationInbox: [makeNotification({ id: 'n1', titleId: 'other-title' })],
    })

    await useAppStore.getState().revertOutingCompletion('o1')

    expect(useAppStore.getState().notificationInbox).toHaveLength(1)
  })

  it('retains another trip notification for the same title and unidentifiable legacy items', async () => {
    await seedState({ titles: [makeTitle()], outings: [makeOuting()], notificationInbox: [
      makeNotification({ id: 'other-trip', payload: { outingId: 'o2' } }),
      makeNotification({ id: 'legacy', payload: {} }),
    ] })
    await useAppStore.getState().revertOutingCompletion('o1')
    expect(useAppStore.getState().notificationInbox.map((item) => item.id)).toEqual(['other-trip', 'legacy'])
  })
})

describe('dismissOutingFollowUp', async () => {
  it('stamps followUpDismissedAt on the matching outing only', async () => {
    await seedState({
      outings: [makeOuting({ id: 'o1' }), makeOuting({ id: 'o2', followUpDismissedAt: undefined })],
    })

    await useAppStore.getState().dismissOutingFollowUp('o1')

    const o1 = useAppStore.getState().outings.find((outing) => outing.id === 'o1')!
    const o2 = useAppStore.getState().outings.find((outing) => outing.id === 'o2')!
    expect(o1.followUpDismissedAt).toBeDefined()
    expect(o2.followUpDismissedAt).toBeUndefined()
  })
})

describe('cancelOuting', async () => {
  it('soft-cancels — keeps the row but flips status to cancelled', async () => {
    await seedState({ outings: [makeOuting({ id: 'o1', status: 'scheduled' })] })

    await useAppStore.getState().cancelOuting('o1')

    expect(useAppStore.getState().outings).toHaveLength(1)
    expect(useAppStore.getState().outings[0].status).toBe('cancelled')
  })
})

describe('updateOuting', async () => {
  it('recomputes endsAt from the merged showtime/previews/runtime', async () => {
    await seedState({
      outings: [
        makeOuting({
          id: 'o1',
          status: 'scheduled',
          showtime: '2026-07-17T19:30:00.000Z',
          previewsMinutes: 20,
          runtimeMinutes: 136,
          endsAt: '2026-07-17T22:06:00.000Z',
        }),
      ],
    })

    await useAppStore.getState().updateOuting('o1', { runtimeMinutes: 150 })

    const outing = useAppStore.getState().outings[0]
    expect(outing.runtimeMinutes).toBe(150)
    // 19:30 + 20min previews + 150min runtime = 22:20
    expect(outing.endsAt).toBe('2026-07-17T22:20:00.000Z')
  })
})

describe('removeViewing', async () => {
  it('rule §5.8: deleting the auto-logged viewing leaves the outing completed but ends its pending follow-up', async () => {
    const viewing = { id: 'v1', titleId: 't1', date: '2026-07-17', venue: 'AMC Georgetown' }
    await seedState({
      titles: [makeTitle({ status: 'watched', viewings: [viewing] })],
      outings: [makeOuting({ completedViewingId: 'v1', followUpDismissedAt: undefined })],
    })

    await useAppStore.getState().removeViewing('t1', 'v1')

    const title = useAppStore.getState().titles.find((t) => t.id === 't1')!
    expect(title.viewings).toEqual([])

    const outing = useAppStore.getState().outings.find((o) => o.id === 'o1')!
    expect(outing.status).toBe('completed') // history, not a claim about the library
    expect(outing.completedViewingId).toBeUndefined()
    expect(outing.followUpDismissedAt).toBeDefined() // pending "how was it?" ends
  })

  it('leaves unrelated outings untouched when the deleted viewing is not their completedViewingId', async () => {
    const viewing = { id: 'v2', titleId: 't1', date: '2026-07-17' }
    await seedState({
      titles: [makeTitle({ status: 'watched', viewings: [viewing] })],
      outings: [makeOuting({ completedViewingId: 'v1', followUpDismissedAt: undefined })],
    })

    await useAppStore.getState().removeViewing('t1', 'v2')

    const outing = useAppStore.getState().outings.find((o) => o.id === 'o1')!
    expect(outing.completedViewingId).toBe('v1')
    expect(outing.followUpDismissedAt).toBeUndefined()
  })

  it('is a no-op on outings when the viewing being deleted was never outing-logged', async () => {
    const viewing = { id: 'v3', titleId: 't1', date: '2026-07-10' }
    await seedState({
      titles: [makeTitle({ status: 'watched', viewings: [viewing] })],
      outings: [],
    })

    await useAppStore.getState().removeViewing('t1', 'v3')

    expect(useAppStore.getState().titles[0].viewings).toEqual([])
    expect(useAppStore.getState().outings).toEqual([])
  })
})
