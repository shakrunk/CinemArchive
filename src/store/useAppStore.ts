import { useMemo } from 'react'
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import { mockTitles, type Title, type LedgerStats, type WatchStatus, type MediaType } from './mockData'
import { computeLedgerStats } from './ledgerStats'
import { nextUnwatchedEpisode } from './episodeUtils'
import { computeUpNextShows, type UpNextEntry } from './upNext'
import type { User } from '@supabase/supabase-js'
import type { AppView } from '../lib/navigation'
import { fetchUserLibrary, fetchSharedLibrary, insertTitleToDb, updateTitleInDb, deleteTitleFromDb, logEpisodeToDb, deleteViewingFromDb, deleteEpisodeWatchEventFromDb } from '../lib/db'

// ─── Filter & Sort Types ────────────────────────────────────────────────────

export type SortField = 'title' | 'year' | 'rating' | 'addedAt' | 'director'
export type SortDir = 'asc' | 'desc'
export type ViewMode = 'grid' | 'list'

/** A cast/crew person, keyed by TMDB id with a display name. */
export interface PersonRef {
  id: number
  name: string
}

export interface LibraryFilters {
  search: string
  type: MediaType | 'all'
  status: WatchStatus | 'all'
  genres: string[]
  tags: string[]
  networks: string[]
  decades: string[]
  minRating: number
  person: PersonRef | null
  studio: string | null
  sortField: SortField
  sortDir: SortDir
}

// ─── Slice Types ────────────────────────────────────────────────────────────

interface EpisodeLogOpts {
  watchedAt?: string   // ISO date — creates a WatchEvent if provided
  watchNotes?: string
  rating?: number      // creates an EpisodeRating stamped at call-time
  reviewText?: string  // creates an EpisodeReview stamped at call-time
  colorMode?: 'bw' | 'color'
}

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
  logEpisode: (titleId: string, seasonNumber: number, episodeNumber: number, opts: EpisodeLogOpts) => void
  removeViewing: (titleId: string, viewingId: string) => void
  deleteEpisodeWatchEvent: (titleId: string, seasonNumber: number, episodeNumber: number, watchEventId: string) => void
  logNextEpisodeWatch: (titleId: string) => { seasonNumber: number; episodeNumber: number; watchEventId: string } | null
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
  isRefreshMetadataOpen: boolean
  isSharedView: boolean
  isCommandPaletteOpen: boolean
  // A top-level view requested by a component that can't reach App's currentView
  // (e.g. the detail drawer). App consumes and clears it. null = nothing pending.
  pendingView: AppView | null

  setViewMode: (mode: ViewMode) => void
  selectTitle: (id: string | null) => void
  openAddTitle: () => void
  closeAddTitle: () => void
  openDetailDrawer: (id: string) => void
  closeDetailDrawer: () => void
  openRefreshMetadata: () => void
  closeRefreshMetadata: () => void
  setIsSharedView: (isSharedView: boolean) => void
  openCommandPalette: () => void
  closeCommandPalette: () => void
  requestView: (view: AppView | null) => void
  // Filter the library to titles featuring a person, then surface it: close the
  // drawer and request the Library view.
  browseByPerson: (person: PersonRef) => void
  // Filter the library to titles from a studio, then surface it: close the
  // drawer and request the Library view.
  browseByStudio: (studio: string) => void
}

interface AuthSlice {
  user: User | null
  loadingUser: boolean
  setUser: (user: User | null) => void
  setLoadingUser: (loading: boolean) => void
  loadUserLibrary: () => Promise<void>
  loadSharedLibrary: (token: string) => Promise<void>
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
  person: null,
  studio: null,
  sortField: 'addedAt',
  sortDir: 'desc',
}

// ─── Filter Logic ───────────────────────────────────────────────────────────

// True when the person (by TMDB id) appears anywhere in a title's credits:
// title cast/crew, any season's cast, or any episode's crew. Mirrored by
// scripts/verify-person-logic.mjs.
export function titleHasPerson(title: Title, personId: number): boolean {
  if (title.cast?.some((c) => c.tmdbPersonId === personId)) return true
  if (title.crew?.some((c) => c.tmdbPersonId === personId)) return true
  for (const season of title.seasons ?? []) {
    if (season.cast?.some((c) => c.tmdbPersonId === personId)) return true
    for (const ep of season.episodes ?? []) {
      if (ep.crew?.some((c) => c.tmdbPersonId === personId)) return true
    }
  }
  return false
}

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

  if (filters.person) {
    const personId = filters.person.id
    result = result.filter((t) => titleHasPerson(t, personId))
  }

  if (filters.studio) {
    const studio = filters.studio
    result = result.filter((t) => t.studios?.includes(studio))
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

type AppStore = LibrarySlice & LedgerSlice & UISlice & AuthSlice

// Bump when the persisted shape changes incompatibly; older payloads are dropped.
const PERSIST_VERSION = 2

export const useAppStore = create<AppStore>()(
  persist(
    (set, get) => ({
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
      if (s.user) {
        insertTitleToDb(s.user.id, title).catch((err) => {
          console.error('Failed to sync added title to DB:', err)
        })
      }
      return {
        titles,
        filteredTitles: applyFiltersToTitles(titles, s.filters),
        stats: computeLedgerStats(titles),
      }
    }),

  updateTitle: (id, patch) =>
    set((s) => {
      const titles = s.titles.map((t) => (t.id === id ? { ...t, ...patch } : t))
      if (s.user) {
        updateTitleInDb(s.user.id, id, patch).catch((err) => {
          console.error('Failed to sync updated title to DB:', err)
        })
      }
      return {
        titles,
        filteredTitles: applyFiltersToTitles(titles, s.filters),
        stats: computeLedgerStats(titles),
      }
    }),

  removeTitle: (id) =>
    set((s) => {
      const titles = s.titles.filter((t) => t.id !== id)
      if (s.user) {
        deleteTitleFromDb(s.user.id, id).catch((err) => {
          console.error('Failed to sync deleted title from DB:', err)
        })
      }
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

  logEpisode: (titleId, seasonNumber, episodeNumber, opts) =>
    set((s) => {
      // Sync to DB: resolve episode id from current state, then fire async
      if (s.user) {
        const targetTitle = s.titles.find((t) => t.id === titleId)
        const targetSeason = targetTitle?.seasons?.find((season) => season.seasonNumber === seasonNumber)
        const targetEpisode = targetSeason?.episodes?.find((ep) => ep.episodeNumber === episodeNumber)
        if (targetEpisode) {
          logEpisodeToDb(s.user.id, targetEpisode.id, opts).catch((err) =>
            console.error('Failed to sync episode log to DB:', err)
          )
        }
      }

      const now = new Date().toISOString()
      const titles = s.titles.map((t) => {
        if (t.id !== titleId) return t
        const seasons = (t.seasons ?? []).map((season) => {
          if (season.seasonNumber !== seasonNumber) return season
          if (!season.episodes) return season
          const episodes = season.episodes.map((ep) => {
            if (ep.episodeNumber !== episodeNumber) return ep
            const updated = { ...ep }
            if (opts.watchedAt) {
              updated.watchEvents = [
                ...ep.watchEvents,
                {
                  id: `we-${titleId}-s${seasonNumber}-e${episodeNumber}-${Date.now()}`,
                  watchedAt: opts.watchedAt,
                  notes: opts.watchNotes || undefined,
                  colorMode: opts.colorMode,
                },
              ]
            }
            if (opts.rating && opts.rating > 0) {
              updated.ratings = [
                ...ep.ratings,
                {
                  id: `er-${titleId}-s${seasonNumber}-e${episodeNumber}-${Date.now()}`,
                  rating: opts.rating,
                  ratedAt: now,
                },
              ]
            }
            if (opts.reviewText?.trim()) {
              updated.reviews = [
                ...ep.reviews,
                {
                  id: `rv-${titleId}-s${seasonNumber}-e${episodeNumber}-${Date.now()}`,
                  reviewText: opts.reviewText.trim(),
                  reviewedAt: now,
                  colorMode: opts.colorMode,
                },
              ]
            }
            return updated
          })
          const episodesWatched = episodes.filter((e) => e.watchEvents.length > 0).length
          return { ...season, episodes, episodesWatched }
        })
        return { ...t, seasons }
      })
      return {
        titles,
        filteredTitles: applyFiltersToTitles(titles, s.filters),
        stats: computeLedgerStats(titles),
      }
    }),

  logNextEpisodeWatch: (titleId) => {
    const state = get()
    const title = state.titles.find((t) => t.id === titleId)
    if (!title || !title.seasons) return null
    const next = nextUnwatchedEpisode(title.seasons)
    if (!next) return null

    const seasonNumber = next.season.seasonNumber
    const episodeNumber = next.episode.episodeNumber
    const episodeId = next.episode.id
    const watchEventId = crypto.randomUUID()
    const watchedAt = new Date().toISOString().slice(0, 10)

    if (state.user) {
      logEpisodeToDb(state.user.id, episodeId, { watchedAt, watchEventId }).catch((err) =>
        console.error('Failed to sync quick episode log to DB:', err)
      )
    }

    set((s) => {
      const titles = s.titles.map((t) => {
        if (t.id !== titleId) return t
        const seasons = (t.seasons ?? []).map((season) => {
          if (season.seasonNumber !== seasonNumber || !season.episodes) return season
          const episodes = season.episodes.map((ep) =>
            ep.episodeNumber === episodeNumber
              ? { ...ep, watchEvents: [...ep.watchEvents, { id: watchEventId, watchedAt }] }
              : ep
          )
          const episodesWatched = episodes.filter((e) => e.watchEvents.length > 0).length
          return { ...season, episodes, episodesWatched }
        })
        return { ...t, seasons }
      })
      return {
        titles,
        filteredTitles: applyFiltersToTitles(titles, s.filters),
        stats: computeLedgerStats(titles),
      }
    })

    return { seasonNumber, episodeNumber, watchEventId }
  },

  removeViewing: (titleId, viewingId) =>
    set((s) => {
      const titles = s.titles.map((t) => {
        if (t.id !== titleId) return t
        return { ...t, viewings: t.viewings.filter((v) => v.id !== viewingId) }
      })
      if (s.user) {
        deleteViewingFromDb(s.user.id, viewingId).catch((err) =>
          console.error('Failed to sync deleted viewing to DB:', err)
        )
      }
      return {
        titles,
        filteredTitles: applyFiltersToTitles(titles, s.filters),
        stats: computeLedgerStats(titles),
      }
    }),

  deleteEpisodeWatchEvent: (titleId, seasonNumber, episodeNumber, watchEventId) =>
    set((s) => {
      if (s.user) {
        deleteEpisodeWatchEventFromDb(s.user.id, watchEventId).catch((err) =>
          console.error('Failed to sync deleted episode watch event to DB:', err)
        )
      }
      const titles = s.titles.map((t) => {
        if (t.id !== titleId) return t
        const seasons = (t.seasons ?? []).map((season) => {
          if (season.seasonNumber !== seasonNumber) return season
          if (!season.episodes) return season
          const episodes = season.episodes.map((ep) => {
            if (ep.episodeNumber !== episodeNumber) return ep
            return {
              ...ep,
              watchEvents: ep.watchEvents.filter((we) => we.id !== watchEventId),
            }
          })
          const episodesWatched = episodes.filter((e) => e.watchEvents.length > 0).length
          return { ...season, episodes, episodesWatched }
        })
        return { ...t, seasons }
      })
      return {
        titles,
        filteredTitles: applyFiltersToTitles(titles, s.filters),
        stats: computeLedgerStats(titles),
      }
    }),

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
    set({ isDetailDrawerOpen: false, isRefreshMetadataOpen: false, selectedTitleId: null }),

  isRefreshMetadataOpen: false,
  openRefreshMetadata: () => set({ isRefreshMetadataOpen: true }),
  closeRefreshMetadata: () => set({ isRefreshMetadataOpen: false }),

  isSharedView: false,
  setIsSharedView: (isSharedView) => set({ isSharedView }),

  isCommandPaletteOpen: false,
  openCommandPalette: () => set({ isCommandPaletteOpen: true }),
  closeCommandPalette: () => set({ isCommandPaletteOpen: false }),

  pendingView: null,
  requestView: (pendingView) => set({ pendingView }),

  browseByPerson: (person) =>
    set((s) => {
      const filters = { ...s.filters, person }
      return {
        filters,
        filteredTitles: applyFiltersToTitles(s.titles, filters),
        isDetailDrawerOpen: false,
        isRefreshMetadataOpen: false,
        selectedTitleId: null,
        pendingView: 'library',
      }
    }),

  browseByStudio: (studio) =>
    set((s) => {
      const filters = { ...s.filters, studio }
      return {
        filters,
        filteredTitles: applyFiltersToTitles(s.titles, filters),
        isDetailDrawerOpen: false,
        isRefreshMetadataOpen: false,
        selectedTitleId: null,
        pendingView: 'library',
      }
    }),

  // ── Auth ───────────────────────────────────────────────────
  user: null,
  loadingUser: false,

  setUser: (user) => {
    set({ user })
    if (user) {
      get().loadUserLibrary()
    } else {
      // Clear or reload mock data on logout
      set((s) => ({
        titles: mockTitles,
        filteredTitles: applyFiltersToTitles(mockTitles, s.filters),
        stats: computeLedgerStats(mockTitles),
      }))
    }
  },

  setLoadingUser: (loadingUser) => set({ loadingUser }),

  loadUserLibrary: async () => {
    const user = get().user
    if (!user) return
    set({ loadingUser: true })
    try {
      const dbTitles = await fetchUserLibrary(user.id)
      set((s) => ({
        titles: dbTitles,
        filteredTitles: applyFiltersToTitles(dbTitles, s.filters),
        stats: computeLedgerStats(dbTitles),
      }))
    } catch (err) {
      console.error('Failed to load user library from DB:', err)
    } finally {
      set({ loadingUser: false })
    }
  },

  loadSharedLibrary: async (token) => {
    set({ loadingUser: true, isSharedView: true })
    try {
      const dbTitles = await fetchSharedLibrary(token)
      set((s) => ({
        titles: dbTitles,
        filteredTitles: applyFiltersToTitles(dbTitles, s.filters),
        stats: computeLedgerStats(dbTitles),
      }))
    } catch (err) {
      console.error('Failed to load shared library from DB:', err)
    } finally {
      set({ loadingUser: false })
    }
  },
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
  return useMemo(() => [...new Set(titles.flatMap((t) => t.genres))].sort(), [titles])
}

export const useAllNetworks = () => {
  const titles = useAppStore((s) => s.titles)
  return useMemo(() => [...new Set(titles.map((t) => t.network).filter(Boolean) as string[])].sort(), [titles])
}

export const useAllDecades = () => {
  const titles = useAppStore((s) => s.titles)
  return useMemo(() => [...new Set(titles.map((t) => `${Math.floor(t.year / 10) * 10}s`))].sort(), [titles])
}

export const useAllTags = () => {
  const titles = useAppStore((s) => s.titles)
  return useMemo(() => [...new Set(titles.flatMap((t) => t.tags))].sort(), [titles])
}

export const useUpNextShows = (): UpNextEntry[] => {
  const titles = useAppStore((s) => s.titles)
  // ⚡ Bolt: wrap expensive computation in useMemo to prevent unnecessary recalculations
  // and maintain a stable array reference across renders
  return useMemo(() => computeUpNextShows(titles), [titles])
}
