import { describe, expect, it } from 'vitest'
import {
  areaPath,
  cn,
  decadeOf,
  fmtReleaseDate,
  getInitials,
  linePath,
  ratingColorVar,
  staggerDelays,
} from './utils'

describe('cn', () => {
  it('merges class names and resolves Tailwind conflicts', () => {
    expect(cn('px-2', 'px-4')).toBe('px-4')
  })

  it('drops falsy values', () => {
    expect(cn('a', false, undefined, null, 'b')).toBe('a b')
  })
})

describe('fmtReleaseDate', () => {
  it('formats a YYYY-MM-DD string as a local-naive date', () => {
    expect(fmtReleaseDate('2010-07-16')).toBe('Jul 16, 2010')
  })

  it('returns the input unchanged when it does not match the expected shape', () => {
    expect(fmtReleaseDate('not-a-date')).toBe('not-a-date')
  })
})

describe('decadeOf', () => {
  it('floors a year to its decade', () => {
    expect(decadeOf(1994)).toBe(1990)
    expect(decadeOf(2000)).toBe(2000)
    expect(decadeOf(2009)).toBe(2000)
  })
})

describe('getInitials', () => {
  it('uses the first two letters of a single-word name', () => {
    expect(getInitials('Madonna')).toBe('MA')
  })

  it('uses first-letter + last-letter for multi-word names', () => {
    expect(getInitials('  Steven   Spielberg  ')).toBe('SS')
  })
})

describe('ratingColorVar', () => {
  it('returns the mapped color for a known rating', () => {
    expect(ratingColorVar(5)).toBe('var(--amber-bright)')
  })

  it('falls back for an unmapped rating', () => {
    expect(ratingColorVar(0)).toBe('var(--paper-faint)')
  })
})

describe('linePath', () => {
  it('returns an empty string for no points', () => {
    expect(linePath([])).toBe('')
  })

  it('builds an SVG path starting with M and continuing with L', () => {
    expect(linePath([{ x: 0, y: 0 }, { x: 10, y: 5 }])).toBe('M 0,0 L 10,5')
  })
})

describe('areaPath', () => {
  it('returns an empty string for no points', () => {
    expect(areaPath([], 100)).toBe('')
  })

  it('closes the line back to the baseline', () => {
    const result = areaPath([{ x: 0, y: 0 }, { x: 10, y: 5 }], 100)
    expect(result).toBe('M 0,0 L 10,5 L 10,100 L 0,100 Z')
  })
})

describe('staggerDelays', () => {
  it('returns one delay per card', () => {
    expect(staggerDelays(5)).toHaveLength(5)
  })

  it('returns 0 for cards past the cap', () => {
    const delays = staggerDelays(30, 24)
    expect(delays.slice(24)).toEqual(Array(6).fill(0))
  })

  it('returns an empty array for zero cards', () => {
    expect(staggerDelays(0)).toEqual([])
  })
})
