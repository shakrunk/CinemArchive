import { useState } from 'react'
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { useNavigationSync } from './useNavigationSync'
import type { AppView } from './navigation'
import { useAppStore } from '../store/useAppStore'

function Harness() {
  const [currentView, setCurrentView] = useState<AppView>('library')
  useNavigationSync({ currentView, setCurrentView })
  return null
}
beforeEach(() => {
  window.history.replaceState({}, '', '/?view=library&title=deep-title')
  useAppStore.setState({ selectedTitleId: null, selectedListId: null, isDetailDrawerOpen: false, isAddTitleOpen: false,
    offlineStatus: { ownerId: null, hydrated: false, commands: [], quarantined: [] },
    isSharedView: false, loadingUser: false, libraryLoadError: null, offlineStorageError: null, viewerContext: { kind: 'owner' } })
})
afterEach(cleanup)

it('preserves the initial deep link until library hydration finishes', () => {
  render(<Harness />)
  expect(window.location.search).toContain('title=deep-title')
  expect(useAppStore.getState().isDetailDrawerOpen).toBe(false)
  act(() => useAppStore.setState({ offlineStatus: { ownerId: null, hydrated: true, commands: [], quarantined: [] } }))
  expect(useAppStore.getState().selectedTitleId).toBe('deep-title')
  expect(useAppStore.getState().isDetailDrawerOpen).toBe(true)
  expect(window.location.search).toContain('title=deep-title')
})

it('does not restore the former owner drawer when a later account change resets it', () => {
  render(<Harness />)
  act(() => useAppStore.setState({ offlineStatus: { ownerId: 'first', hydrated: true, commands: [], quarantined: [] } }))
  act(() => useAppStore.setState({ selectedTitleId: null, isDetailDrawerOpen: false,
    offlineStatus: { ownerId: 'second', hydrated: false, commands: [], quarantined: [] } }))
  expect(window.location.search).not.toContain('title=')
  act(() => useAppStore.setState({ offlineStatus: { ownerId: 'second', hydrated: true, commands: [], quarantined: [] } }))
  expect(useAppStore.getState().selectedTitleId).toBeNull()
  expect(useAppStore.getState().isDetailDrawerOpen).toBe(false)
})
