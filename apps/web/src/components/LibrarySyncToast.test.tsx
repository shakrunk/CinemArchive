import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { LibrarySyncToast, LIBRARY_SYNC_TOAST_KEY, PENDING_GRACE_MS } from './LibrarySyncToast'
import { useAppStore } from '../store/useAppStore'
import { createCommand, type PendingCommand } from '../lib/offline/commands'

function pending(id: string, state: PendingCommand['state'] = 'pending'): PendingCommand {
  return { ...createCommand({ projectId: 'project', userId: 'owner' }, { kind: 'title.patch', titleId: id, patch: { notes: id } }), state }
}

function setCommands(commands: PendingCommand[]) {
  act(() => useAppStore.setState({ offlineStatus: { ownerId: 'owner', hydrated: true, commands, quarantined: [] } }))
}

const syncToast = () => useAppStore.getState().notifications.find((n) => n.dedupeKey === LIBRARY_SYNC_TOAST_KEY)

beforeEach(() => {
  vi.useFakeTimers()
  useAppStore.setState({ isSharedView: false, isLibrarySyncOpen: false, notifications: [], offlineSyncError: null,
    offlineStatus: { ownerId: 'owner', hydrated: true, commands: [], quarantined: [] } })
})
afterEach(() => { cleanup(); vi.useRealTimers() })

it('waits out the grace period before announcing pending changes, then updates the same toast', () => {
  render(<LibrarySyncToast />)
  setCommands([pending('a')])
  expect(syncToast()).toBeUndefined()
  act(() => { vi.advanceTimersByTime(PENDING_GRACE_MS) })
  const first = syncToast()
  expect(first?.message).toBe('1 change saved on this device, waiting to sync.')
  expect(first?.kind).toBe('tip')
  setCommands([pending('a'), pending('b')])
  expect(syncToast()?.id).toBe(first?.id)
  expect(syncToast()?.message).toBe('2 changes saved on this device, waiting to sync.')
  expect(useAppStore.getState().notifications).toHaveLength(1)
})

it('shows changes needing review immediately and opens the dialog from Review', () => {
  render(<LibrarySyncToast />)
  setCommands([pending('a', 'conflict')])
  expect(syncToast()).toMatchObject({ kind: 'error', message: '1 saved change could not sync and needs review.' })
  act(() => syncToast()?.action?.run())
  expect(useAppStore.getState().isLibrarySyncOpen).toBe(true)
})

it('confirms once everything has synced', () => {
  render(<LibrarySyncToast />)
  setCommands([pending('a', 'failed')])
  setCommands([])
  expect(syncToast()).toMatchObject({ kind: 'tip', message: 'All changes saved on this device have synced.' })
  expect(syncToast()?.autoClose).toBeGreaterThan(0)
})

it('never announces work that synced within the grace period', () => {
  render(<LibrarySyncToast />)
  setCommands([pending('a')])
  setCommands([])
  act(() => { vi.advanceTimersByTime(PENDING_GRACE_MS) })
  expect(useAppStore.getState().notifications).toHaveLength(0)
})

it('respects a dismissal until the situation escalates', () => {
  render(<LibrarySyncToast />)
  setCommands([pending('a', 'failed'), pending('b')])
  act(() => useAppStore.getState().dismissNotification(syncToast()!.id))
  setCommands([pending('a', 'failed')])
  expect(syncToast()).toBeUndefined()
  setCommands([pending('a', 'failed'), pending('b'), pending('c')])
  expect(syncToast()?.message).toBe('1 saved change could not sync and needs review.')
})

it('restores a toast evicted by newer notifications', () => {
  render(<LibrarySyncToast />)
  setCommands([pending('a', 'failed')])
  act(() => { for (let i = 0; i < 5; i++) useAppStore.getState().pushNotification({ message: `other ${i}` }) })
  expect(syncToast()).toBeDefined()
})

it('withdraws the toast when a shared library is opened', () => {
  render(<LibrarySyncToast />)
  setCommands([pending('a', 'conflict')])
  expect(syncToast()).toBeDefined()
  act(() => useAppStore.setState({ isSharedView: true }))
  expect(useAppStore.getState().notifications).toHaveLength(0)
})
