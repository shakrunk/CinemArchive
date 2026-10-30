import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import type { AppNotificationItem, NotificationType } from 'src/lib/db'
import { NotificationCenter } from './NotificationCenter'

const initialState = useAppStore.getState()

const note = (type: NotificationType, extra: Partial<AppNotificationItem> = {}): AppNotificationItem => ({
  id: `n-${type}`, type, actorId: 'friend', actorDisplayName: 'Sam', actorUsername: 'sam', titleId: 'film', tmdbId: null,
  mediaType: null, title: 'Cinema film', posterUrl: null, payload: {}, createdAt: '2026-10-29T10:00:00Z', readAt: null, ...extra,
})

const markOne = vi.fn().mockResolvedValue(undefined)
const openDetailDrawer = vi.fn()
const onNavigate = vi.fn()

function show(items: AppNotificationItem[]) {
  useAppStore.setState({
    notificationInbox: items,
    unreadNotificationCount: items.length,
    loadNotificationInbox: vi.fn().mockResolvedValue(undefined),
    markOneNotificationRead: markOne,
    openDetailDrawer,
  } as never)
  render(<NotificationCenter onNavigate={onNavigate} />)
}

async function openMenu() {
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: /^Notifications/ }))
  return { user, menu: await screen.findByRole('menu', { name: 'Notifications' }) }
}

beforeEach(() => { markOne.mockClear(); openDetailDrawer.mockClear(); onNavigate.mockClear() })
afterEach(() => { cleanup(); useAppStore.setState(initialState, true) })

// ─── Rendering: the sentence each outing and ticket notification reads as ────

it('words each outing, poll and ticket notification', async () => {
  show([
    note('outing_invite', { payload: { title: 'Dune' } }),
    note('outing_invite_response', { payload: { title: 'Dune', accepted: true } }),
    note('outing_invite_response', { id: 'declined', payload: { title: 'Dune', accepted: false } }),
    note('outing_poll', { titleId: null, payload: { question: 'Friday or Saturday?' } }),
    note('outing_poll_closed', { titleId: null, payload: { question: 'Friday or Saturday?', title: 'Saturday' } }),
    note('outing_poll_closed', { id: 'cancelled', titleId: null, payload: { question: 'Friday or Saturday?', cancelled: true } }),
    note('ticket_received', { payload: { title: 'Dune' } }),
    note('ticket_opened', { payload: { title: 'Dune' } }),
  ])
  const { menu } = await openMenu()
  const text = within(menu)
  expect(text.getByText('invited you to "Dune"')).toBeInTheDocument()
  expect(text.getByText('is going to "Dune"')).toBeInTheDocument()
  expect(text.getByText('can\'t make "Dune"')).toBeInTheDocument()
  expect(text.getByText('asked "Friday or Saturday?"')).toBeInTheDocument()
  expect(text.getByText('picked "Saturday" for "Friday or Saturday?"')).toBeInTheDocument()
  expect(text.getByText('cancelled "Friday or Saturday?"')).toBeInTheDocument()
  expect(text.getByText('sent you a ticket to "Dune"')).toBeInTheDocument()
  expect(text.getByText('opened your ticket to "Dune"')).toBeInTheDocument()
  expect(text.getAllByText('Sam')).toHaveLength(8)
})

it('falls back to the notification title when the payload names none', async () => {
  show([note('ticket_opened'), note('ticket_received', { title: null })])
  const { menu } = await openMenu()
  expect(within(menu).getByText('opened your ticket to "Cinema film"')).toBeInTheDocument()
  expect(within(menu).getByText('sent you a ticket to "a movie"')).toBeInTheDocument()
})

// ─── Click routing ───────────────────────────────────────────────────────────

const cases: [NotificationType, Partial<AppNotificationItem>, 'upnext' | 'detail'][] = [
  ['outing_invite', { payload: { title: 'Dune' } }, 'upnext'],
  ['outing_poll', { titleId: null, payload: { question: 'Which?' } }, 'upnext'],
  ['outing_poll_closed', { titleId: null, payload: { question: 'Which?', title: 'Dune' } }, 'upnext'],
  ['ticket_received', { payload: { title: 'Dune' } }, 'upnext'],
  ['outing_invite_response', { payload: { title: 'Dune', accepted: true } }, 'detail'],
  ['ticket_opened', { payload: { title: 'Dune', grant_id: 'g1' } }, 'detail'],
]

for (const [type, extra, destination] of cases) {
  it(`routes a ${type} notification to ${destination === 'upnext' ? 'Up Next' : 'the title detail'} and marks it read`, async () => {
    const item = note(type, extra)
    show([item])
    const { user, menu } = await openMenu()
    await user.click(within(menu).getByRole('menuitem'))
    if (destination === 'upnext') {
      expect(onNavigate).toHaveBeenCalledWith('upnext')
      expect(openDetailDrawer).not.toHaveBeenCalled()
    } else {
      expect(openDetailDrawer).toHaveBeenCalledWith('film')
      expect(onNavigate).not.toHaveBeenCalled()
    }
    expect(markOne).toHaveBeenCalledWith(item.id)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })
}

it('does nothing but close when a ticket_opened notification has no title to open', async () => {
  show([note('ticket_opened', { titleId: null })])
  const { user, menu } = await openMenu()
  await user.click(within(menu).getByRole('menuitem'))
  expect(openDetailDrawer).not.toHaveBeenCalled()
  expect(onNavigate).not.toHaveBeenCalled()
  expect(screen.queryByRole('menu')).not.toBeInTheDocument()
})

it('does not mark an already-read notification read again', async () => {
  show([note('ticket_received', { readAt: '2026-10-29T11:00:00Z' })])
  const { user, menu } = await openMenu()
  await user.click(within(menu).getByRole('menuitem'))
  expect(markOne).not.toHaveBeenCalled()
  expect(onNavigate).toHaveBeenCalledWith('upnext')
})
