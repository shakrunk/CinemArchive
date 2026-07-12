import { beforeEach, describe, expect, it } from 'vitest'
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
    payload: {},
    createdAt: '2026-07-17T22:10:00.000Z',
    readAt: null,
    ...overrides,
  }
}

// No `user` is ever set below — every action's DB sync branch is gated on
// `if (s.user)`, so these tests exercise pure optimistic-state logic only,
// with no Supabase calls in play (unconfigured in the test environment anyway).
beforeEach(() => {
  useAppStore.setState({
    titles: [],
    filteredTitles: [],
    outings: [],
    notificationInbox: [],
    unreadNotificationCount: 0,
    user: null,
  })
})

describe('revertOutingCompletion ("Didn\'t make it")', () => {
  it('deletes the auto-logged viewing, reverts a still-watched title, and marks the outing missed', () => {
    const viewing = { id: 'v1', titleId: 't1', date: '2026-07-17', venue: 'AMC Georgetown' }
    useAppStore.setState({
      titles: [makeTitle({ status: 'watched', viewings: [viewing] })],
      outings: [makeOuting()],
    })

    useAppStore.getState().revertOutingCompletion('o1')

    const title = useAppStore.getState().titles.find((t) => t.id === 't1')!
    expect(title.viewings).toEqual([])
    expect(title.status).toBe('watchlist') // outing.previousStatus

    const outing = useAppStore.getState().outings.find((o) => o.id === 'o1')!
    expect(outing.status).toBe('missed')
    expect(outing.completedViewingId).toBeUndefined()
  })

  it('leaves the title status alone when the user changed it manually since completion', () => {
    const viewing = { id: 'v1', titleId: 't1', date: '2026-07-17' }
    useAppStore.setState({
      titles: [makeTitle({ status: 'watching', viewings: [viewing] })], // no longer 'watched'
      outings: [makeOuting()],
    })

    useAppStore.getState().revertOutingCompletion('o1')

    const title = useAppStore.getState().titles.find((t) => t.id === 't1')!
    expect(title.status).toBe('watching') // untouched
    expect(title.viewings).toEqual([]) // the auto-logged viewing is still gone
  })

  it('is a no-op for an outing that is not completed', () => {
    useAppStore.setState({
      titles: [makeTitle({ status: 'watchlist' })],
      outings: [makeOuting({ status: 'scheduled', completedViewingId: undefined })],
    })

    useAppStore.getState().revertOutingCompletion('o1')

    expect(useAppStore.getState().outings[0].status).toBe('scheduled')
  })

  it('drops the matching stale outing_completed notification from the inbox', () => {
    useAppStore.setState({
      titles: [makeTitle({ status: 'watched', viewings: [{ id: 'v1', titleId: 't1', date: '2026-07-17' }] })],
      outings: [makeOuting()],
      notificationInbox: [makeNotification({ id: 'n1', titleId: 't1' })],
      unreadNotificationCount: 1,
    })

    useAppStore.getState().revertOutingCompletion('o1')

    expect(useAppStore.getState().notificationInbox).toEqual([])
    expect(useAppStore.getState().unreadNotificationCount).toBe(0)
  })

  it('leaves unrelated notifications (a different title) untouched', () => {
    useAppStore.setState({
      titles: [makeTitle({ status: 'watched', viewings: [{ id: 'v1', titleId: 't1', date: '2026-07-17' }] })],
      outings: [makeOuting()],
      notificationInbox: [makeNotification({ id: 'n1', titleId: 'other-title' })],
    })

    useAppStore.getState().revertOutingCompletion('o1')

    expect(useAppStore.getState().notificationInbox).toHaveLength(1)
  })
})

describe('dismissOutingFollowUp', () => {
  it('stamps followUpDismissedAt on the matching outing only', () => {
    useAppStore.setState({
      outings: [makeOuting({ id: 'o1' }), makeOuting({ id: 'o2', followUpDismissedAt: undefined })],
    })

    useAppStore.getState().dismissOutingFollowUp('o1')

    const [o1, o2] = useAppStore.getState().outings
    expect(o1.followUpDismissedAt).toBeDefined()
    expect(o2.followUpDismissedAt).toBeUndefined()
  })
})

describe('cancelOuting', () => {
  it('soft-cancels — keeps the row but flips status to cancelled', () => {
    useAppStore.setState({ outings: [makeOuting({ id: 'o1', status: 'scheduled' })] })

    useAppStore.getState().cancelOuting('o1')

    expect(useAppStore.getState().outings).toHaveLength(1)
    expect(useAppStore.getState().outings[0].status).toBe('cancelled')
  })
})

describe('updateOuting', () => {
  it('recomputes endsAt from the merged showtime/previews/runtime', () => {
    useAppStore.setState({
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

    useAppStore.getState().updateOuting('o1', { runtimeMinutes: 150 })

    const outing = useAppStore.getState().outings[0]
    expect(outing.runtimeMinutes).toBe(150)
    // 19:30 + 20min previews + 150min runtime = 22:20
    expect(outing.endsAt).toBe('2026-07-17T22:20:00.000Z')
  })
})

describe('removeViewing', () => {
  it('rule §5.8: deleting the auto-logged viewing leaves the outing completed but ends its pending follow-up', () => {
    const viewing = { id: 'v1', titleId: 't1', date: '2026-07-17', venue: 'AMC Georgetown' }
    useAppStore.setState({
      titles: [makeTitle({ status: 'watched', viewings: [viewing] })],
      outings: [makeOuting({ completedViewingId: 'v1', followUpDismissedAt: undefined })],
    })

    useAppStore.getState().removeViewing('t1', 'v1')

    const title = useAppStore.getState().titles.find((t) => t.id === 't1')!
    expect(title.viewings).toEqual([])

    const outing = useAppStore.getState().outings.find((o) => o.id === 'o1')!
    expect(outing.status).toBe('completed') // history, not a claim about the library
    expect(outing.completedViewingId).toBeUndefined()
    expect(outing.followUpDismissedAt).toBeDefined() // pending "how was it?" ends
  })

  it('leaves unrelated outings untouched when the deleted viewing is not their completedViewingId', () => {
    const viewing = { id: 'v2', titleId: 't1', date: '2026-07-17' }
    useAppStore.setState({
      titles: [makeTitle({ status: 'watched', viewings: [viewing] })],
      outings: [makeOuting({ completedViewingId: 'v1', followUpDismissedAt: undefined })],
    })

    useAppStore.getState().removeViewing('t1', 'v2')

    const outing = useAppStore.getState().outings.find((o) => o.id === 'o1')!
    expect(outing.completedViewingId).toBe('v1')
    expect(outing.followUpDismissedAt).toBeUndefined()
  })

  it('is a no-op on outings when the viewing being deleted was never outing-logged', () => {
    const viewing = { id: 'v3', titleId: 't1', date: '2026-07-10' }
    useAppStore.setState({
      titles: [makeTitle({ status: 'watched', viewings: [viewing] })],
      outings: [],
    })

    useAppStore.getState().removeViewing('t1', 'v3')

    expect(useAppStore.getState().titles[0].viewings).toEqual([])
    expect(useAppStore.getState().outings).toEqual([])
  })
})
