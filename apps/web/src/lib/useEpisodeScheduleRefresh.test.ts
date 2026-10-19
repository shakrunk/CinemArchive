import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { create } from 'zustand'

const { runEpisodeScheduleRefresh } = vi.hoisted(() => ({ runEpisodeScheduleRefresh: vi.fn(async () => 0) }))

vi.mock('src/lib/auth', () => ({ isSupabaseConfigured: true }))
vi.mock('src/lib/episodeSchedule', () => ({ runEpisodeScheduleRefresh }))
vi.mock('src/store/useAppStore', () => {
  const useAppStore = create(() => ({
    user: { id: 'owner' },
    isSharedView: false,
    viewerContext: { kind: 'owner' },
    librarySession: 1,
    loadingUser: true,
    libraryLoadError: null as string | null,
    titles: [],
    offlineStatus: { ownerId: 'owner', hydrated: true, commands: [], quarantined: [] },
    applyCatalogRefresh: vi.fn(),
  }))
  return { useAppStore }
})

import { useEpisodeScheduleRefresh } from './useEpisodeScheduleRefresh'
import { useAppStore } from 'src/store/useAppStore'

afterEach(() => vi.clearAllMocks())

describe('useEpisodeScheduleRefresh', () => {
  it('waits for the server library load, then runs one pass', async () => {
    renderHook(() => useEpisodeScheduleRefresh())
    // Local cache hydrated, but this session's server snapshot hasn't landed.
    await act(async () => {})
    expect(runEpisodeScheduleRefresh).not.toHaveBeenCalled()

    await act(async () => { useAppStore.setState({ loadingUser: false }) })
    expect(runEpisodeScheduleRefresh).toHaveBeenCalledTimes(1)

    // Later store churn doesn't start another pass.
    await act(async () => { useAppStore.setState({ offlineStatus: { ownerId: 'owner', hydrated: true, commands: [], quarantined: [] } }) })
    expect(runEpisodeScheduleRefresh).toHaveBeenCalledTimes(1)
  })

  it('does not run after a failed library load or in a shared view', async () => {
    useAppStore.setState({ loadingUser: false, libraryLoadError: 'Could not load' })
    const { unmount } = renderHook(() => useEpisodeScheduleRefresh())
    await act(async () => {})
    unmount()

    useAppStore.setState({ libraryLoadError: null, isSharedView: true })
    renderHook(() => useEpisodeScheduleRefresh())
    await act(async () => {})
    expect(runEpisodeScheduleRefresh).not.toHaveBeenCalled()
  })
})
