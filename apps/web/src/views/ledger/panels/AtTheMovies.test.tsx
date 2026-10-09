import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import type { CinemaOuting, Title } from 'src/store/mockData'
import { AtTheMovies } from './AtTheMovies'

const outings: CinemaOuting[] = Array.from({ length: 10 }, (_, index) => ({
  id: `o${index}`, titleId: 'film', venue: index < 5 ? 'Palace' : `Venue ${index}`,
  format: 'IMAX', ticketPrice: index < 5 ? 10 : 20,
  showtime: '2026-07-01T19:00:00Z', endsAt: '2026-07-01T21:00:00Z',
  previewsMinutes: 20, runtimeMinutes: 100, companions: [], seats: [],
  status: 'completed', createdAt: '2026-07-01',
}))
const titles: Title[] = [{
  id: 'film', tmdbId: 1, type: 'movie', title: 'Cinema film', year: 2026,
  genres: [], tags: [], status: 'watched', addedAt: '2026-01-01',
  viewings: outings.map((outing, index) => ({
    id: `v${index}`, titleId: 'film', outingId: outing.id, venue: outing.venue,
    date: `${2010 + index}-07-01`, companions: [{ name: `Companion ${index}` }],
  })),
}]

const initialState = useAppStore.getState()
afterEach(() => { cleanup(); useAppStore.setState(initialState, true) })

function seed() {
  useAppStore.setState({ titles, outings, isSharedView: false, viewerContext: { kind: 'owner' } })
}

it('opens complete accessible details from a compact card and closes with Escape', async () => {
  seed()
  const user = userEvent.setup()
  render(<AtTheMovies width="sm" />)
  expect(screen.queryByText('Venue 9')).not.toBeInTheDocument()
  const trigger = screen.getByRole('button', { name: 'View moviegoing details' })
  trigger.focus()
  await user.keyboard('{Enter}')
  const dialog = screen.getByRole('dialog', { name: 'Moviegoing details' })
  const details = within(dialog)
  expect(details.getByRole('button', { name: 'Close' })).toHaveFocus()
  await user.tab()
  expect(details.getByRole('region', { name: 'Moviegoing breakdown' })).toHaveFocus()
  await user.tab()
  expect(details.getByRole('button', { name: 'Close' })).toHaveFocus()
  expect(details.getByRole('region', { name: 'Moviegoing breakdown' })).toHaveAttribute('tabindex', '0')
  expect(within(details.getByRole('region', { name: 'Theaters' })).getByText('Venue 9')).toBeInTheDocument()
  expect(within(details.getByRole('region', { name: 'Companions' })).getByText('Companion 9')).toBeInTheDocument()
  expect(within(details.getByRole('region', { name: 'Trips by year' })).getByText('2010')).toBeInTheDocument()
  expect(details.getByRole('region', { name: 'Best value venue' })).toHaveTextContent('Palace · $10.00 per priced trip')
  const venueTable = within(details.getByRole('table', { name: 'Spend by venue' }))
  expect(venueTable.getByRole('row', { name: 'Palace 5 $50.00 $10.00' })).toBeInTheDocument()
  expect(details.getByRole('table', { name: 'Cost per outing by format' })).toHaveTextContent('$150.00')
  const milestones = within(details.getByRole('region', { name: 'Moviegoing milestones' }))
  expect(milestones.getByText('5 visits')).toBeInTheDocument()
  expect(milestones.getByText('First outing')).toBeInTheDocument()
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await waitFor(() => expect(trigger).toHaveFocus())
})

it.each(['shared-link', 'friend'] as const)('ignores stale owner outings for a %s viewer', (kind) => {
  seed()
  useAppStore.setState({
    isSharedView: true,
    viewerContext: kind === 'shared-link' ? { kind, token: 'shared' } : { kind, userId: 'friend', displayName: 'Friend' },
  })
  render(<AtTheMovies />)
  expect(screen.queryByText('IMAX')).not.toBeInTheDocument()
  expect(screen.queryByText(/priced trip/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'View moviegoing details' }))
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Best value venue' })).not.toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Moviegoing milestones' })).not.toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Theaters' })).toHaveTextContent('Palace')
})

it.each(['shared-link', 'friend'] as const)('protects private data when only viewerContext identifies the %s view', (kind) => {
  seed()
  useAppStore.setState({ viewerContext: kind === 'shared-link'
    ? { kind, token: 'shared' }
    : { kind, userId: 'friend', displayName: 'Friend' } })
  expect(useAppStore.getState().isSharedView).toBe(false)
  render(<AtTheMovies />)
  fireEvent.click(screen.getByRole('button', { name: 'View moviegoing details' }))
  expect(screen.queryByText('IMAX')).not.toBeInTheDocument()
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Moviegoing milestones' })).not.toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Best value venue' })).not.toBeInTheDocument()
})

it('removes private details immediately when an open owner dialog becomes shared', () => {
  seed()
  render(<AtTheMovies />)
  fireEvent.click(screen.getByRole('button', { name: 'View moviegoing details' }))
  expect(screen.getByRole('table', { name: 'Spend by venue' })).toBeInTheDocument()
  act(() => useAppStore.setState({ isSharedView: true }))
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.queryByText('IMAX')).not.toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Moviegoing milestones' })).not.toBeInTheDocument()
})

it('does not imply unpriced trips were free and keeps the empty panel actionable', () => {
  seed()
  useAppStore.setState({ outings: outings.map((outing) => ({ ...outing, ticketPrice: undefined })) })
  render(<AtTheMovies />)
  fireEvent.click(screen.getByRole('button', { name: 'View moviegoing details' }))
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.queryByText(/Total ticket spending/)).not.toBeInTheDocument()
  act(() => useAppStore.setState({ titles: [] }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Browse Library' })).toBeInTheDocument()
})
