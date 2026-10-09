import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { User } from '@supabase/supabase-js'
import { TheaterInterestControl } from './TheaterInterestControl'
import { useAppStore } from '../store/useAppStore'
import { title, outing, deferred } from '../lib/offline/fixtures.test-support'

const initial = useAppStore.getState()
const user = { id: '10000000-0000-4000-8000-000000000001' } as User
const interest = { id: title.id, titleId: title.id, userId: user.id, createdAt: '2026-10-08T00:00:00Z', updatedAt: '2026-10-08T00:00:00Z' }
beforeEach(() => useAppStore.setState({ user, titles: [{ ...title, type: 'movie' }], outings: [], theaterInterest: [], isSharedView: false,
  viewerContext: { kind: 'owner' }, moviegoingPreferencesSupport: 'authoritative',
  offlineStatus: { ownerId: user.id, hydrated: true, commands: [], quarantined: [] } }))
afterEach(() => { cleanup(); useAppStore.setState(initial, true) })

it('waits for durability and retains the old selection when persistence fails', async () => {
  const saved = deferred<void>()
  useAppStore.setState({ setTheaterInterest: vi.fn(() => saved.promise) })
  render(<TheaterInterestControl titleId={title.id} />)
  fireEvent.click(screen.getByRole('button'))
  expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'false')
  expect(screen.getByRole('button')).toBeDisabled()
  await act(async () => { saved.reject(new Error('Device storage is full')); await saved.promise.catch(() => {}) })
  expect(screen.getByRole('alert')).toHaveTextContent('Device storage is full')
  expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'false')
  expect(screen.getByRole('button')).toBeEnabled()
})

it('shows the committed projection and suppresses private controls after an account/view change', async () => {
  const saved = deferred<void>()
  useAppStore.setState({ setTheaterInterest: vi.fn(() => saved.promise) })
  render(<TheaterInterestControl titleId={title.id} />)
  fireEvent.click(screen.getByRole('button'))
  await act(async () => { useAppStore.setState({ theaterInterest: [interest] }); saved.resolve(); await saved.promise })
  expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'true')
  act(() => useAppStore.setState({ viewerContext: { kind: 'friend', userId: 'friend', displayName: 'Friend' } }))
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  act(() => useAppStore.setState({ viewerContext: { kind: 'owner' }, user: null }))
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
})

it('retains a cached selected preference on unsupported servers and hides only scheduled trips', () => {
  useAppStore.setState({ theaterInterest: [interest], moviegoingPreferencesSupport: 'unsupported', outings: [{ ...outing, titleId: title.id, status: 'completed' }] })
  render(<TheaterInterestControl titleId={title.id} />)
  expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button')).toBeDisabled()
  expect(screen.getByText(/Saved preferences remain/)).toBeInTheDocument()
  act(() => useAppStore.setState({ outings: [{ ...outing, titleId: title.id, status: 'scheduled' }] }))
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
})
