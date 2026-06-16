import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import { mockTitles, type Title, type LedgerStats, type WatchStatus, type MediaType } from './mockData'
import { computeLedgerStats } from './ledgerStats'

// ─── Filter & Sort Types ────────────────────────────────────────────────────

export type SortField = 'title' | 'year' | 'rating' | 'addedAt' | 'director'
export type SortDir = 'asc' | 'desc'
export type ViewMode = 'grid' | 'list'

export interface LibraryFilters {
  search: string
  type: MediaType | 'all'
  status: WatchStatus | 'all'
  genres: string[]
  tags: string[]
  networks: string[]
  decades: string[]
  minRating: number
  sortField: SortField
  sortDir: SortDir
}

// ─── Slice Types ────────────────────────────────────────────────────────────

interface LibrarySlice {
  titles: Title[]
  filters: LibraryFilters
  filteredTitles: Title[]

  setTitles: (titles: Title[]) => void
  addTitle: (title: Title) => void
  updateTitle: (id: string, patch: Partial<Title>) => void
  removeTitle: (id: string) => void
  setFilter: <K extends keyof LibraryFilters>(key: K, value: LibraryFilters[K]) => void
  resetFilters: () => void
  applyFilters: () => void
}

interface LedgerSlice {
  stats: LedgerStats
  setStats: (stats: LedgerStats) => void
}

interface UISlice {
  viewMode: ViewMode
  selectedTitleId: string | null
  isAddTitleOpen: boolean
  isDetailDrawerOpen: boolean

  setViewMode: (mode: ViewMode) => void
  selectTitle: (id: string | null) => void
  openAddTitle: () => void
  closeAddTitle: () => void
  openDetailDrawer: (id: string) => void
  closeDetailDrawer: () => void
}

// ─── Default Filters ────────────────────────────────────────────────────────

const defaultFilters: LibraryFilters = {
  search: '',
  type: 'all',
  status: 'all',
  genres: [],
  tags: [],
  networks: [],
  decades: [],
  minRating: 0,
  sortField: 'addedAt',
  sortDir: 'desc',
}

// ─── Filter Logic ───────────────────────────────────────────────────────────

function applyFiltersToTitles(titles: Title[], filters: LibraryFilters): Title[] {
  let result = [...titles]

  if (filters.search.trim()) {
    const q = filters.search.toLowerCase()
    result = result.filter(
      (t) =>
        t.title.toLowerCase().includes(q) ||
        t.director?.toLowerCase().includes(q) ||
        t.genres.some((g) => g.toLowerCase().includes(q)) ||
        t.tags.some((tag) => tag.toLowerCase().includes(q))
    )
  }

  if (filters.type !== 'all') {
    result = result.filter((t) => t.type === filters.type)
  }

  if (filters.status !== 'all') {
    result = result.filter((t) => t.status === filters.status)
  }

  if (filters.genres.length > 0) {
    result = result.filter((t) => filters.genres.some((g) => t.genres.includes(g)))
  }

  if (filters.tags.length > 0) {
    result = result.filter((t) => filters.tags.some((tag) => t.tags.includes(tag)))
  }

  if (filters.networks.length > 0) {
    result = result.filter((t) => t.network && filters.networks.includes(t.network))
  }

  if (filters.decades.length > 0) {
    result = result.filter((t) => {
      const decade = `${Math.floor(t.year / 10) * 10}s`
      return filters.decades.includes(decade)
    })
  }

  if (filters.minRating > 0) {
    result = result.filter((t) => (t.rating ?? 0) >= filters.minRating)
  }

  // Sort
  result.sort((a, b) => {
    let comparison = 0
    switch (filters.sortField) {
      case 'title':
        comparison = a.title.localeCompare(b.title)
        break
      case 'year':
        comparison = a.year - b.year
        break
      case 'rating':
        comparison = (a.rating ?? 0) - (b.rating ?? 0)
        break
      case 'addedAt':
        comparison = new Date(a.addedAt).getTime() - new Date(b.addedAt).getTime()
        break
      case 'director':
        comparison = (a.director ?? '').localeCompare(b.director ?? '')
        break
    }
    return filters.sortDir === 'desc' ? -comparison : comparison
  })

  return result
}

// ─── Store ──────────────────────────────────────────────────────────────────

type AppStore = LibrarySlice & LedgerSlice & UISlice

// Bump when the persisted shape changes incompatibly; older payloads are dropped.
const PERSIST_VERSION = 1

export const useAppStore = create<AppStore>()(
  persist(
    (set) => ({
  // ── Library ────────────────────────────────────────────────
  titles: mockTitles,
  filters: defaultFilters,
  filteredTitles: applyFiltersToTitles(mockTitles, defaultFilters),

  setTitles: (titles) =>
    set((s) => ({
      titles,
      filteredTitles: applyFiltersToTitles(titles, s.filters),
      stats: computeLedgerStats(titles),
    })),

  addTitle: (title) =>
    set((s) => {
      const titles = [title, ...s.titles]
      return {
        titles,
        filteredTitles: applyFiltersToTitles(titles, s.filters),
        stats: computeLedgerStats(titles),
      }
    }),

  updateTitle: (id, patch) =>
    set((s) => {
      const titles = s.titles.map((t) => (t.id === id ? { ...t, ...patch } : t))
      return {
        titles,
        filteredTitles: applyFiltersToTitles(titles, s.filters),
        stats: computeLedgerStats(titles),
      }
    }),

  removeTitle: (id) =>
    set((s) => {
      const titles = s.titles.filter((t) => t.id !== id)
      return {
        titles,
        filteredTitles: applyFiltersToTitles(titles, s.filters),
        stats: computeLedgerStats(titles),
      }
    }),

  setFilter: (key, value) =>
    set((s) => {
      const filters = { ...s.filters, [key]: value }
      return { filters, filteredTitles: applyFiltersToTitles(s.titles, filters) }
    }),

  resetFilters: () =>
    set((s) => ({
      filters: defaultFilters,
      filteredTitles: applyFiltersToTitles(s.titles, defaultFilters),
    })),

  applyFilters: () =>
    set((s) => ({
      filteredTitles: applyFiltersToTitles(s.titles, s.filters),
    })),

  // ── Ledger ─────────────────────────────────────────────────
  stats: computeLedgerStats(mockTitles),

  setStats: (stats) => set({ stats }),

  // ── UI ─────────────────────────────────────────────────────
  viewMode: 'grid',
  selectedTitleId: null,
  isAddTitleOpen: false,
  isDetailDrawerOpen: false,

  setViewMode: (viewMode) => set({ viewMode }),

  selectTitle: (selectedTitleId) => set({ selectedTitleId }),

  openAddTitle: () => set({ isAddTitleOpen: true }),
  closeAddTitle: () => set({ isAddTitleOpen: false }),

  openDetailDrawer: (id) =>
    set({ selectedTitleId: id, isDetailDrawerOpen: true }),

  closeDetailDrawer: () =>
    set({ isDetailDrawerOpen: false, selectedTitleId: null }),
    }),
    {
      name: 'cinemarchive-library',
      version: PERSIST_VERSION,
      storage: createJSONStorage(() => localStorage),
      // Only the source of truth is persisted; derived state (filteredTitles,
      // stats) and transient UI flags are recomputed/reset on load.
      partialize: (s) => ({ titles: s.titles, filters: s.filters, viewMode: s.viewMode }),
      onRehydrateStorage: () => (state) => {
        if (!state) return
        state.filteredTitles = applyFiltersToTitles(state.titles, state.filters)
        state.stats = computeLedgerStats(state.titles)
      },
    }
  )
)

// ─── Selectors ──────────────────────────────────────────────────────────────

export const useSelectedTitle = () =>
  useAppStore((s) => s.titles.find((t) => t.id === s.selectedTitleId) ?? null)

export const useAllGenres = () => {
  const titles = useAppStore((s) => s.titles)
  return [...new Set(titles.flatMap((t) => t.genres))].sort()
}

export const useAllNetworks = () => {
  const titles = useAppStore((s) => s.titles)
  return [...new Set(titles.map((t) => t.network).filter(Boolean) as string[])].sort()
}

export const useAllDecades = () => {
  const titles = useAppStore((s) => s.titles)
  return [...new Set(titles.map((t) => `${Math.floor(t.year / 10) * 10}s`))].sort()
}

export const useAllTags = () => {
  const titles = useAppStore((s) => s.titles)
  return [...new Set(titles.flatMap((t) => t.tags))].sort()
}
