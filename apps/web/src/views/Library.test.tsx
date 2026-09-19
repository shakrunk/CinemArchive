import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import { Library } from './Library'

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  vi.stubGlobal('scrollTo', vi.fn())
  useAppStore.getState().resetFilters()
})
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals() })

it('does not let pending typing overwrite an external search change', () => {
  render(<Library />)
  fireEvent.change(screen.getByRole('textbox', { name: 'Search' }), { target: { value: 'unfinished draft' } })
  act(() => { useAppStore.getState().setFilter('search', 'Christopher Nolan') })
  expect(screen.getByRole('textbox', { name: 'Search' })).toHaveValue('Christopher Nolan')
  act(() => { vi.advanceTimersByTime(200) })
  expect(useAppStore.getState().filters.search).toBe('Christopher Nolan')
  expect(screen.getByRole('textbox', { name: 'Search' })).toHaveValue('Christopher Nolan')
})
