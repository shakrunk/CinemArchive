import { describe, expect, it } from 'vitest'
import { createElement } from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { AtTheMovies } from '../views/ledger/panels/AtTheMovies'
import { mapDbOutingToLocal, mapDbViewingToLocal } from '../lib/db'
import { parseImportFile } from '../lib/export-import'
import { companionSuggestions, deriveAtTheMovies, formatCompanions } from './outings'
import { useAppStore } from './useAppStore'
import type { Companion, Title } from './mockData'

const mixedCompanions = [' Alex ', { name: 'Sam', friendUserId: 'u2' }, {}, null, 42, { name: 7 }, ' ']
const expected = [{ name: 'Alex' }, { name: 'Sam', friendUserId: 'u2' }]
const viewingRow = { id: 'v1', title_id: 't1', viewed_at: '2026-09-01', venue: 'Cinema', companions: mixedCompanions }
const title: Title = {
  id: 't1', tmdbId: 603, type: 'movie', title: 'The Matrix', year: 1999,
  genres: [], tags: [], status: 'watched', addedAt: '2026-01-01', viewings: [],
}

describe('cross-platform companions', () => {
  it('keeps Android names and web friend links when reading database rows', () => {
    expect(mapDbViewingToLocal(viewingRow).companions).toEqual(expected)
    expect(mapDbOutingToLocal({ companions: mixedCompanions }).companions).toEqual(expected)
  })

  it('renders moviegoing stats and shared companion labels from mixed cached data', () => {
    const companions = mixedCompanions as Companion[]
    const viewing = { id: 'v1', titleId: 't1', venue: 'Cinema', companions }
    expect(deriveAtTheMovies([{ ...title, viewings: [viewing] }], [])).toMatchObject({
      tripsTotal: 1, topCompanion: { name: 'Alex', count: 1 },
    })
    expect(formatCompanions(companions)).toBe('Alex & Sam')
    expect(companionSuggestions([], [viewing], [])).toEqual(expected)
  })

  it('renders the Ledger moviegoing panel with Android companion names', () => {
    const initial = useAppStore.getState()
    try {
      useAppStore.setState({
        titles: [{ ...title, viewings: [mapDbViewingToLocal(viewingRow)] }], outings: [],
      })
      render(createElement(AtTheMovies))
      expect(screen.getByText('At the movies')).toBeInTheDocument()
      expect(screen.getByText('Alex')).toBeInTheDocument()
      expect(screen.getByText('Cinema')).toBeInTheDocument()
    } finally {
      cleanup()
      useAppStore.setState(initial, true)
    }
  })

  it('preserves an unknown-owner browser cache without adopting its library', async () => {
    const initial = useAppStore.getState()
    const stored = localStorage.getItem('cinemarchive-library')
    const deviceKey = 'cinemarchive-device-preferences-v1'
    const deviceStored = localStorage.getItem(deviceKey)
    try {
      localStorage.setItem('cinemarchive-library', JSON.stringify({
        version: useAppStore.persist.getOptions().version,
        state: {
          titles: [{ ...title, viewings: [{ id: 'v1', titleId: 't1', companions: mixedCompanions }] }],
          outings: [{ id: 'o1', companions: mixedCompanions }],
        },
      }))
      const legacy = localStorage.getItem('cinemarchive-library')
      useAppStore.setState({ titles: [], outings: [] })
      localStorage.removeItem(deviceKey)
      await useAppStore.persist.rehydrate()
      expect(useAppStore.getState().titles).toEqual([])
      expect(useAppStore.getState().outings).toEqual([])
      expect(localStorage.getItem('cinemarchive-library')).toBe(legacy)
    } finally {
      useAppStore.setState(initial, true)
      if (deviceStored === null) localStorage.removeItem(deviceKey)
      else localStorage.setItem(deviceKey, deviceStored)
      if (stored === null) localStorage.removeItem('cinemarchive-library')
      else localStorage.setItem('cinemarchive-library', stored)
    }
  })

  it('normalizes imported viewing and outing companions', async () => {
    const file = new File([JSON.stringify({
      titles: [{ ...title, viewings: [viewingRow] }],
      outings: [{ id: 'o1', titleId: 't1', companions: mixedCompanions }],
    })], 'library.json')
    const imported = await parseImportFile(file)
    expect(imported.titles[0].viewings[0].companions).toEqual(expected)
    expect(imported.outings[0].companions).toEqual(expected)
  })
})
