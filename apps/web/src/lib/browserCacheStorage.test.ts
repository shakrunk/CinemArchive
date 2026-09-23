import { expect, it, vi } from 'vitest'
import { createBrowserCacheStorage } from './browserCacheStorage'

it('preserves the last cache on failure, reports once, and retries subsequent writes', () => {
  const saved = new Map([['library', 'previous library'], ['unrelated', 'keep me']])
  let full = true
  const storage = {
    getItem: (key: string) => saved.get(key) ?? null,
    setItem: (key: string, value: string) => {
      if (full) throw new DOMException('Full', 'QuotaExceededError')
      saved.set(key, value)
    },
    removeItem: (key: string) => { saved.delete(key) },
  }
  const report = vi.fn()
  const cache = createBrowserCacheStorage(() => storage, report)
  expect(() => cache.setItem('library', 'new library')).not.toThrow()
  cache.setItem('library', 'newer library')
  expect(cache.getItem('library')).toBe('previous library')
  expect(report).toHaveBeenCalledTimes(1)
  expect(saved.get('unrelated')).toBe('keep me')
  full = false
  cache.setItem('library', 'recovered library')
  expect(cache.getItem('library')).toBe('recovered library')
  full = true
  cache.setItem('library', 'another change')
  expect(report).toHaveBeenCalledTimes(2)
})

it('tolerates blocked storage access and does not recurse when reporting writes state', () => {
  const report = vi.fn(() => cache.setItem('notification', 'warning'))
  const cache = createBrowserCacheStorage(() => { throw new DOMException('Blocked', 'SecurityError') }, report)
  expect(cache.getItem('library')).toBeNull()
  expect(() => cache.setItem('library', 'updated')).not.toThrow()
  expect(() => cache.removeItem('library')).not.toThrow()
  expect(report).toHaveBeenCalledTimes(1)
})

it('passes successful reads writes and removals through', () => {
  const storage = { getItem: vi.fn(() => 'saved'), setItem: vi.fn(), removeItem: vi.fn() }
  const report = vi.fn()
  const cache = createBrowserCacheStorage(() => storage, report)
  expect(cache.getItem('library')).toBe('saved')
  cache.setItem('library', 'new')
  cache.removeItem('library')
  expect(storage.setItem).toHaveBeenCalledWith('library', 'new')
  expect(storage.removeItem).toHaveBeenCalledWith('library')
  expect(report).not.toHaveBeenCalled()
})
