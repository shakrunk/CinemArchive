import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { LibrarySyncStatus } from './LibrarySyncStatus'
import { useAppStore } from '../store/useAppStore'
import { createCommand } from '../lib/offline/commands'

const command = { ...createCommand({ projectId: 'project', userId: 'owner' }, { kind: 'title.patch', titleId: 'title', patch: { notes: 'Keep this' } }), state: 'conflict' as const, lastError: 'A newer edit exists' }
beforeEach(() => useAppStore.setState({ isSharedView: false, offlineStorageError: null, offlineSyncError: null, legacyCacheAvailable: false,
  offlineStatus: { ownerId: 'owner', hydrated: true, commands: [command], quarantined: [] } }))
afterEach(() => { cleanup(); vi.restoreAllMocks() })

it('keeps failed work visible and reports a refused prerequisite discard', async () => {
  vi.spyOn(useAppStore.getState(), 'discardPendingCommand').mockRejectedValueOnce(new Error('Resolve dependent commands first'))
  render(<LibrarySyncStatus />)
  expect(screen.getByText('A newer edit exists')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Discard…' }))
  fireEvent.click(screen.getByRole('button', { name: 'Confirm discard' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Resolve dependent commands first')
  expect(useAppStore.getState().offlineStatus.commands).toHaveLength(1)
})

it('never exposes owner pending work while viewing a shared library', () => {
  useAppStore.setState({ isSharedView: true })
  render(<LibrarySyncStatus />)
  expect(screen.queryByLabelText('Library sync')).not.toBeInTheDocument()
})
