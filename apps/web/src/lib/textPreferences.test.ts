import { afterEach, describe, expect, it, vi } from 'vitest'
import bootstrap from '../../public/bootstrap.js?raw'
import { DEFAULT_TEXT_PREFERENCES, TEXT_SIZES, applyTextPreferences, parseTextPreferences, saveTextPreferences, watchTextPreferences } from './textPreferences'

afterEach(() => { vi.restoreAllMocks(); saveTextPreferences(DEFAULT_TEXT_PREFERENCES) })

describe('device text preferences', () => {
  it('normalizes malformed and unsupported settings', () => {
    for (const raw of [null, '{', 'null', '42', '[]', '{"family":"comic","size":900}']) {
      expect(parseTextPreferences(raw)).toEqual(DEFAULT_TEXT_PREFERENCES)
    }
    expect(parseTextPreferences('{"family":"lexend","size":"large"}')).toEqual({ family: 'lexend', size: 'large' })
  })

  it.each(TEXT_SIZES)('applies $value without overriding the browser base size', ({ value, scale }) => {
    document.documentElement.style.fontSize = '20px'
    applyTextPreferences({ family: 'lexend', size: value })
    expect(document.documentElement.style.fontSize).toBe('20px')
    expect(document.documentElement.style.getPropertyValue('--text-scale')).toBe(String(scale))
    expect(document.documentElement.dataset.textFamily).toBe('lexend')
    document.documentElement.style.removeProperty('font-size')
  })

  it('applies external storage clearing and removes its subscription', () => {
    saveTextPreferences({ family: 'lexend', size: 'large' })
    const stop = watchTextPreferences()
    window.dispatchEvent(new StorageEvent('storage', { key: null }))
    expect(document.documentElement.dataset.textFamily).toBe('default')
    stop()
    window.dispatchEvent(new StorageEvent('storage', { key: 'cinemarchive-text-preferences', newValue: '{"family":"lexend"}' }))
    expect(document.documentElement.dataset.textFamily).toBe('default')
  })
})

describe('first paint bootstrap', () => {
  function boot(raw: string | null, blocked = false) {
    const attributes: Record<string, string> = {}
    const properties: Record<string, string> = {}
    const run = new Function('document', 'localStorage', 'sessionStorage', 'window', bootstrap)
    run(
      {
        documentElement: { setAttribute: (key: string, value: string) => { attributes[key] = value }, style: { setProperty: (key: string, value: string) => { properties[key] = value } } },
        querySelector: () => null,
      },
      { getItem: (key: string) => { if (blocked) throw new Error('Blocked'); return key === 'cinemarchive-text-preferences' ? raw : null } },
      { getItem: () => null, removeItem: () => {} },
      {},
    )
    return { attributes, properties }
  }
  it.each(TEXT_SIZES)('restores $value before React loads', ({ value, scale }) => {
    const result = boot(JSON.stringify({ family: 'lexend', size: value }))
    expect(result.attributes['data-text-family']).toBe('lexend')
    expect(result.attributes['data-text-size']).toBe(value)
    expect(result.properties['--text-scale']).toBe(String(scale))
  })
  it('falls back without breaking startup on malformed or restricted storage', () => {
    for (const result of [boot('{'), boot(null, true)]) {
      expect(result.attributes['data-text-family']).toBe('default')
      expect(result.properties['--text-scale']).toBe('1')
    }
  })
})
