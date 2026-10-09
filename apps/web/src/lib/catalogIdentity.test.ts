import { describe, expect, it } from 'vitest'
import { isCatalogTitleOwned, libraryCatalogKeys } from './catalogIdentity'

describe('Discover catalog ownership', () => {
  const movie = { type: 'movie', tmdbId: 42 } as const
  const series = { type: 'tv', tmdbId: 42 } as const

  it('keeps movie ownership from disabling Add on a TV result with the same ID', () => {
    const owned = libraryCatalogKeys([movie])
    expect(isCatalogTitleOwned(owned, movie)).toBe(true)
    expect(isCatalogTitleOwned(owned, series)).toBe(false)
    expect(isCatalogTitleOwned(owned, null)).toBe(false)
  })

  it('retains the other media type in recommendations and cast discovery', () => {
    const recommendations = [movie, series, { type: 'movie', tmdbId: 43 } as const]
    const owned = libraryCatalogKeys([movie])
    expect(recommendations.filter(result => !isCatalogTitleOwned(owned, result))).toEqual([series, recommendations[2]])
    expect(recommendations.filter(result => !isCatalogTitleOwned(libraryCatalogKeys([series]), result))).toEqual([movie, recommendations[2]])
  })

  it('recognizes both independently when both are owned', () => {
    const owned = libraryCatalogKeys([movie, series, movie])
    expect(owned.size).toBe(2)
    expect(isCatalogTitleOwned(owned, movie)).toBe(true)
    expect(isCatalogTitleOwned(owned, series)).toBe(true)
  })

  it('does not mark unlinked search results as owned because an unlinked library title exists', () => {
    const unlinked = { type: 'movie' } as const
    const owned = libraryCatalogKeys([unlinked, { type: 'tv', tmdbId: null }])
    expect(owned.size).toBe(0)
    expect(isCatalogTitleOwned(owned, unlinked)).toBe(false)
  })
})
