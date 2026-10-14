import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import { Library } from './Library'

const initialState = useAppStore.getState()

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  vi.stubGlobal('scrollTo', vi.fn())
  useAppStore.setState(initialState, true)
  useAppStore.getState().resetFilters()
})
afterEach(() => { cleanup(); useAppStore.setState(initialState, true); vi.useRealTimers(); vi.unstubAllGlobals() })

it('does not let pending typing overwrite an external search change', () => {
  render(<Library />)
  fireEvent.change(screen.getByRole('textbox', { name: 'Search' }), { target: { value: 'unfinished draft' } })
  act(() => { useAppStore.getState().setFilter('search', 'Christopher Nolan') })
  expect(screen.getByRole('textbox', { name: 'Search' })).toHaveValue('Christopher Nolan')
  act(() => { vi.advanceTimersByTime(200) })
  expect(useAppStore.getState().filters.search).toBe('Christopher Nolan')
  expect(screen.getByRole('textbox', { name: 'Search' })).toHaveValue('Christopher Nolan')
})

it('shows a shared-link failure and retries the same link without an empty-owner CTA', () => {
  const loadSharedLibrary = vi.fn().mockResolvedValue(undefined)
  useAppStore.setState({
    viewerContext: { kind: 'shared-link', token: 'revoked-link' },
    isSharedView: true,
    titles: [], filteredTitles: [],
    libraryLoadError: "Couldn't load this shared library — the link may have expired.",
    loadSharedLibrary,
  })
  render(<Library />)
  expect(screen.getByRole('alert')).toHaveTextContent('the link may have expired')
  expect(screen.queryByText('Your archive is empty.')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Add a title' })).not.toBeInTheDocument()
  expect(screen.queryByText(/on the bill/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(loadSharedLibrary).toHaveBeenCalledWith('revoked-link')
})

it('retries a failed friend library using its existing viewer identity', () => {
  const loadFriendLibrary = vi.fn().mockResolvedValue(undefined)
  useAppStore.setState({
    viewerContext: { kind: 'friend', userId: 'friend-id', displayName: 'Friend' },
    isSharedView: true,
    libraryLoadError: "Couldn't load that friend's library — check your connection.",
    loadFriendLibrary,
  })
  render(<Library />)
  expect(screen.getByRole('alert')).toHaveTextContent('check your connection')
  expect(screen.queryByRole('textbox', { name: 'Search' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(loadFriendLibrary).toHaveBeenCalledWith('friend-id', 'Friend')
})

it('preserves the owner library controls when a background refresh fails', () => {
  useAppStore.setState({ viewerContext: { kind: 'owner' }, libraryLoadError: 'Offline' })
  render(<Library />)
  expect(screen.getByRole('textbox', { name: 'Search' })).toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})
