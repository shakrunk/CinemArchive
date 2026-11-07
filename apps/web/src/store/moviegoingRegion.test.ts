import { describe, expect, it } from 'vitest'
import { effectiveRegion, FALLBACK_REGION, isValidRegion, normalizeRegion, regionFromLanguage } from './moviegoingRegion'

// The same cases as Android's MoviegoingRegionTest.
describe('moviegoing region', () => {
  it('is valid only as exactly two uppercase ASCII letters', () => {
    expect(isValidRegion('US')).toBe(true)
    for (const bad of [null, undefined, '', 'u', 'us', 'USA', 'U1', 'Ü!', 'U S', 7]) expect(isValidRegion(bad)).toBe(false)
  })

  it('normalizes by trimming and uppercasing, never inventing a code', () => {
    expect(normalizeRegion(' gb ')).toBe('GB')
    expect(normalizeRegion('great britain')).toBeNull()
    expect(normalizeRegion(null)).toBeNull()
  })

  it('reads the country out of a language tag', () => {
    expect(regionFromLanguage('en-GB')).toBe('GB')
    expect(regionFromLanguage('zh-Hant-TW')).toBe('TW')
    expect(regionFromLanguage('fr')).toBeNull()
    expect(regionFromLanguage(undefined)).toBeNull()
  })

  it('prefers the saved region, then the language, then the fallback', () => {
    expect(effectiveRegion('CA', 'fr-FR')).toBe('CA')
    expect(effectiveRegion(null, 'fr-FR')).toBe('FR')
    expect(effectiveRegion('bogus', 'fr-FR')).toBe('FR')
    expect(effectiveRegion(null, 'fr')).toBe(FALLBACK_REGION)
  })
})
