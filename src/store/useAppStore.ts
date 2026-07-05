import { useMemo } from 'react'
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import { mockTitles, type Title, type LedgerStats, type WatchStatus, type MediaType } from './mockData'
import { computeLedgerStats } from './ledgerStats'
import { nextUnwatchedEpisode } from './episodeUtils'
import { computeUpNextShows, computeUpcomingTitles, type UpNextEntry, type UpcomingEntry } from './upNext'
import type { User } from '@supabase/supabase-js'
import type { AppView, NavItemId } from '../lib/navigation'
import { DEFAULT_NAV_ORDER } from '../lib/navigation'
import type { LedgerPanelId, LedgerPanelWidth } from '../lib/ledgerPanels'
import { DEFAULT_LEDGER_PANEL_ORDER, DEFAULT_LEDGER_PANEL_WIDTHS } from '../lib/ledgerPanels'
import {
  fetchUserLibrary, fetchSharedLibrary, fetchFriendLibrary, insertTitleToDb, updateTitleInDb,
  deleteTitleFromDb, logEpisodeToDb, deleteViewingFromDb,
  deleteEpisodeWatchEventFromDb, insertPrePlatformWatchEventsToDb,
  fetchAllTitlePins, upsertTitlePin, deleteTitlePin,
  fetchFriendActivityFeed,
} from '../lib/db'
import type { SearchResult } from '../lib/media'

// ─── Filter & Sort Types ────────────────────────────────────────────────────

export type SortField = 'title' | 'year' | 'rating' | 'addedAt' | 'director'
export type SortDir = 'asc' | 'desc'
export type ViewMode = 'grid' | 'list'
export type Theme = 'dark' | 'light' | 'noir' | 'matrix'

/** Nav bar layout/visibility preferences — order + hidden apply to both the
 *  TopBar pill nav and BottomNav; compact hides labels on the desktop pill nav. */
export interface NavPrefs {
  order: NavItemId[]
  hidden: NavItemId[]
  compact: boolean
}

/** Ledger dashboard panel layout preferences — order + hidden apply to the
 *  panel grid rendered by Ledger.tsx. */
export interface LedgerPrefs {
  order: LedgerPanelId[]
  hidden: LedgerPanelId[]
  widths: Record<LedgerPanelId, LedgerPanelWidth>
}

/** A cast/crew person, keyed by TMDB id with a display name. */
export interface PersonRef {
  id: number
  name: string
}

/** A persistent notification. kind defaults to 'error'. */
export interface AppNotification {
  id: string
  message: string
  kind?: 'error' | 'tip'
  /** Auto-dismiss after this many ms (tips only). */
  autoClose?: number
  retry?: () => Promise<void>
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
  // Group the library into franchise sections (TMDB collections, e.g.
  // "The Lord of the Rings Collection"); non-franchise titles trail behind.
  groupByFranchise: boolean
  sortField: SortField
  sortDir: SortDir
}

// ─── Slice Types ────────────────────────────────────────────────────────────

interface EpisodeLogOpts {
  watchedAt?: string   // ISO date — creates a WatchEvent if provided
  prePlatform?: boolean // watched before joining — creates a WatchEvent with no date (indeterminate)
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
  // Marks every unwatched episode of a season (or the whole series when
  // seasonNumber is omitted) as watched before joining the platform: each gets
  // a dateless watch event. Series-wide marking also sets status to 'watched'.
  markPrePlatformWatched: (titleId: string, seasonNumber?: number) => void
  removeViewing: (titleId: string, viewingId: string) => void
  deleteEpisodeWatchEvent: (titleId: string, seasonNumber: number, episodeNumber: number, watchEventId: string) => void
  logNextEpisodeWatch: (titleId: string, colorMode?: 'bw' | 'color') => { seasonNumber: number; episodeNumber: number; watchEventId: string } | null
}

interface LedgerSlice {
  stats: LedgerStats
  setStats: (stats: LedgerStats) => void
}

interface UISlice {
  viewMode: ViewMode
  theme: Theme
  // Themes beyond dark/light are locked until earned via an in-app easter egg
  // (e.g. Spider-Noir black & white, The Matrix red pill). Always includes
  // 'dark' and 'light'.
  unlockedThemes: Theme[]
  navPrefs: NavPrefs
  ledgerPrefs: LedgerPrefs
  selectedTitleId: string | null
  isAddTitleOpen: boolean
  isDetailDrawerOpen: boolean
  isRefreshMetadataOpen: boolean
  isSharedView: boolean
  isCommandPaletteOpen: boolean
  // A top-level view requested by a component that can't reach App's currentView
  // (e.g. the detail drawer). App consumes and clears it. null = nothing pending.
  pendingView: AppView | null
  // When non-null, AddTitleWorkflow skips to step 2 with this pre-selected result.
  preselectedResult: SearchResult | null

  setViewMode: (mode: ViewMode) => void
  setTheme: (theme: Theme) => void
  // No-op if already unlocked or the theme is dark/light (always unlocked).
  unlockTheme: (theme: Theme) => void
  moveNavItem: (id: NavItemId, direction: 'up' | 'down') => void
  reorderNav: (order: NavItemId[]) => void
  toggleNavItemHidden: (id: NavItemId) => void
  setNavCompact: (compact: boolean) => void
  resetNavPrefs: () => void
  moveLedgerPanel: (id: LedgerPanelId, direction: 'up' | 'down') => void
  reorderLedgerPanels: (order: LedgerPanelId[]) => void
  toggleLedgerPanelHidden: (id: LedgerPanelId) => void
  setLedgerPanelWidth: (id: LedgerPanelId, width: LedgerPanelWidth) => void
  resetLedgerPrefs: () => void
  selectTitle: (id: string | null) => void
  openAddTitle: () => void
  openAddTitlePreselected: (result: SearchResult) => void
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

  notifications: AppNotification[]
  pushNotification: (n: Omit<AppNotification, 'id'>) => void
  dismissNotification: (id: string) => void

  // Friend activity feed badge — the feed itself is fetched on demand by
  // ProfileModal; this just tracks the unseen count shown on TopBar.
  activityFeedLastSeenAt: string | null
  activityUnseenCount: number
  markActivityFeedSeen: () => void
  refreshActivityUnseenCount: () => Promise<void>
}

/** Identifies whose library is currently loaded when browsing a friend's collection. */
export interface FriendViewInfo {
  userId: string
  displayName: string
}

interface AuthSlice {
  user: User | null
  loadingUser: boolean
  friendView: FriendViewInfo | null
  setUser: (user: User | null) => void
  setLoadingUser: (loading: boolean) => void
  loadUserLibrary: () => Promise<void>
  loadSharedLibrary: (token: string) => Promise<void>
  loadFriendLibrary: (friendUserId: string, displayName: string) => Promise<void>
  exitFriendView: () => void
}

interface PinsSlice {
  pinnedModes: Record<string, 'bw' | 'color'>
  setPinnedMode: (titleId: string, easterEggKey: string, variant: 'bw' | 'color' | null) => void
  loadPinnedModes: () => Promise<void>
}

// ─── Default Nav Prefs ──────────────────────────────────────────────────────

const defaultNavPrefs: NavPrefs = {
  order: DEFAULT_NAV_ORDER,
  hidden: [],
  compact: false,
}

const defaultLedgerPrefs: LedgerPrefs = {
  order: DEFAULT_LEDGER_PANEL_ORDER,
  hidden: [],
  widths: DEFAULT_LEDGER_PANEL_WIDTHS,
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
  groupByFranchise: false,
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
        t.tags.some((tag) => tag.toLowerCase().includes(q)) ||
        t.cast?.some((c) => c.name.toLowerCase().includes(q))
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

type AppStore = LibrarySlice & LedgerSlice & UISlice & AuthSlice & PinsSlice

// Bump when the persisted shape changes incompatibly; older payloads are dropped.
const PERSIST_VERSION = 2

export const useAppStore = create<AppStore>()(
  persist(
    (set, get) => ({
  // ── Library ────────────────────────────────────────────────
  titles: import.meta.env.DEV ? mockTitles : [],
  filters: defaultFilters,
  filteredTitles: applyFiltersToTitles(import.meta.env.DEV ? mockTitles : [], defaultFilters),

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
        const userId = s.user.id
        insertTitleToDb(userId, title).catch((err) => {
          console.error('Failed to sync added title to DB:', err)
          get().pushNotification({
            message: `Couldn't save "${title.title}" — check your connection.`,
            retry: () => insertTitleToDb(userId, title),
          })
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
        const userId = s.user.id
        updateTitleInDb(userId, id, patch).catch((err) => {
          console.error('Failed to sync updated title to DB:', err)
          get().pushNotification({
            message: 'Couldn\'t save changes — check your connection.',
            retry: () => updateTitleInDb(userId, id, patch),
          })
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
        const userId = s.user.id
        deleteTitleFromDb(userId, id).catch((err) => {
          console.error('Failed to sync deleted title from DB:', err)
          get().pushNotification({
            message: 'Couldn\'t remove title — check your connection.',
            retry: () => deleteTitleFromDb(userId, id),
          })
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
      // Stable UUIDs so local IDs match the DB rows — enables reliable delete/undo
      const createsWatchEvent = Boolean(opts.watchedAt || opts.prePlatform)
      const watchEventId = createsWatchEvent ? crypto.randomUUID() : undefined

      // Sync to DB: resolve episode id from current state, then fire async
      if (s.user) {
        const targetTitle = s.titles.find((t) => t.id === titleId)
        const targetSeason = targetTitle?.seasons?.find((season) => season.seasonNumber === seasonNumber)
        const targetEpisode = targetSeason?.episodes?.find((ep) => ep.episodeNumber === episodeNumber)
        if (targetEpisode) {
          const userId = s.user.id
          const episodeId = targetEpisode.id
          const dbOpts = { ...opts, watchEventId }
          logEpisodeToDb(userId, episodeId, dbOpts).catch((err) => {
            console.error('Failed to sync episode log to DB:', err)
            get().pushNotification({
              message: 'Couldn\'t save watch event — check your connection.',
              retry: () => logEpisodeToDb(userId, episodeId, dbOpts),
            })
          })
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
            if (watchEventId) {
              updated.watchEvents = [
                ...ep.watchEvents,
                {
                  id: watchEventId,
                  watchedAt: opts.prePlatform ? undefined : opts.watchedAt,
                  notes: opts.watchNotes || undefined,
                  colorMode: opts.colorMode,
                },
              ]
            }
            if (opts.rating && opts.rating > 0) {
              updated.ratings = [
                ...ep.ratings,
                {
                  id: crypto.randomUUID(),
                  rating: opts.rating,
                  ratedAt: now,
                },
              ]
            }
            if (opts.reviewText?.trim()) {
              updated.reviews = [
                ...ep.reviews,
                {
                  id: crypto.randomUUID(),
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

  logNextEpisodeWatch: (titleId, colorMode) => {
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
      const userId = state.user.id
      const dbOpts = { watchedAt, watchEventId, colorMode }
      logEpisodeToDb(userId, episodeId, dbOpts).catch((err) => {
        console.error('Failed to sync quick episode log to DB:', err)
        get().pushNotification({
          message: 'Couldn\'t save watch event — check your connection.',
          retry: () => logEpisodeToDb(userId, episodeId, dbOpts),
        })
      })
    }

    set((s) => {
      const titles = s.titles.map((t) => {
        if (t.id !== titleId) return t
        const seasons = (t.seasons ?? []).map((season) => {
          if (season.seasonNumber !== seasonNumber || !season.episodes) return season
          const episodes = season.episodes.map((ep) =>
            ep.episodeNumber === episodeNumber
              ? { ...ep, watchEvents: [...ep.watchEvents, { id: watchEventId, watchedAt, colorMode }] }
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

  markPrePlatformWatched: (titleId, seasonNumber) =>
    set((s) => {
      const newEvents: Array<{ id: string; episodeId: string }> = []

      const titles = s.titles.map((t) => {
        if (t.id !== titleId) return t
        const seasons = (t.seasons ?? []).map((season) => {
          if (seasonNumber !== undefined && season.seasonNumber !== seasonNumber) return season
          if (!season.episodes) return season
          const episodes = season.episodes.map((ep) => {
            if (ep.watchEvents.length > 0) return ep
            const watchEventId = crypto.randomUUID()
            newEvents.push({ id: watchEventId, episodeId: ep.id })
            return { ...ep, watchEvents: [{ id: watchEventId }] }
          })
          const episodesWatched = episodes.filter((e) => e.watchEvents.length > 0).length
          return { ...season, episodes, episodesWatched }
        })
        const status = seasonNumber === undefined ? 'watched' : t.status
        return { ...t, seasons, status }
      })

      if (newEvents.length === 0 && seasonNumber !== undefined) return s

      if (s.user && newEvents.length > 0) {
        const userId = s.user.id
        insertPrePlatformWatchEventsToDb(userId, newEvents).catch((err) => {
          console.error('Failed to sync pre-platform watch events to DB:', err)
          get().pushNotification({
            message: 'Couldn\'t save watch events — check your connection.',
            retry: () => insertPrePlatformWatchEventsToDb(userId, newEvents),
          })
        })
      }
      if (s.user && seasonNumber === undefined) {
        const userId = s.user.id
        updateTitleInDb(userId, titleId, { status: 'watched' }).catch((err) => {
          console.error('Failed to sync watched status to DB:', err)
        })
      }

      return {
        titles,
        filteredTitles: applyFiltersToTitles(titles, s.filters),
        stats: computeLedgerStats(titles),
      }
    }),

  removeViewing: (titleId, viewingId) =>
    set((s) => {
      const titles = s.titles.map((t) => {
        if (t.id !== titleId) return t
        return { ...t, viewings: t.viewings.filter((v) => v.id !== viewingId) }
      })
      if (s.user) {
        const userId = s.user.id
        deleteViewingFromDb(userId, viewingId).catch((err) => {
          console.error('Failed to sync deleted viewing to DB:', err)
          get().pushNotification({
            message: 'Couldn\'t remove viewing — check your connection.',
            retry: () => deleteViewingFromDb(userId, viewingId),
          })
        })
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
        const userId = s.user.id
        deleteEpisodeWatchEventFromDb(userId, watchEventId).catch((err) => {
          console.error('Failed to sync deleted episode watch event to DB:', err)
          get().pushNotification({
            message: 'Couldn\'t remove watch event — check your connection.',
            retry: () => deleteEpisodeWatchEventFromDb(userId, watchEventId),
          })
        })
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
  stats: computeLedgerStats(import.meta.env.DEV ? mockTitles : []),

  setStats: (stats) => set({ stats }),

  // ── UI ─────────────────────────────────────────────────────
  viewMode: 'grid',
  theme: 'dark',
  unlockedThemes: ['dark', 'light'],
  navPrefs: defaultNavPrefs,
  ledgerPrefs: defaultLedgerPrefs,
  selectedTitleId: null,
  isAddTitleOpen: false,
  isDetailDrawerOpen: false,
  preselectedResult: null,

  setViewMode: (viewMode) => set({ viewMode }),

  setTheme: (theme) => set({ theme }),

  unlockTheme: (theme) => {
    if (theme === 'dark' || theme === 'light') return
    if (get().unlockedThemes.includes(theme)) return
    set((s) => ({ unlockedThemes: [...s.unlockedThemes, theme] }))
    const label = theme === 'noir' ? 'Spider-Man Noir' : 'The Construct'
    get().pushNotification({
      message: `New theme unlocked: "${label}" — pick it in Settings → Appearance.`,
      kind: 'tip',
      autoClose: 6000,
    })
  },

  moveNavItem: (id, direction) =>
    set((s) => {
      const order = [...s.navPrefs.order]
      const idx = order.indexOf(id)
      const swapWith = direction === 'up' ? idx - 1 : idx + 1
      if (idx === -1 || swapWith < 0 || swapWith >= order.length) return {}
      ;[order[idx], order[swapWith]] = [order[swapWith], order[idx]]
      return { navPrefs: { ...s.navPrefs, order } }
    }),

  reorderNav: (order) => set((s) => ({ navPrefs: { ...s.navPrefs, order } })),

  toggleNavItemHidden: (id) =>
    set((s) => {
      const isHidden = s.navPrefs.hidden.includes(id)
      const hidden = isHidden
        ? s.navPrefs.hidden.filter((x) => x !== id)
        : [...s.navPrefs.hidden, id]
      // Keep at least one tab visible.
      if (!isHidden && hidden.length >= s.navPrefs.order.length) return {}
      return { navPrefs: { ...s.navPrefs, hidden } }
    }),

  setNavCompact: (compact) => set((s) => ({ navPrefs: { ...s.navPrefs, compact } })),

  resetNavPrefs: () => set({ navPrefs: defaultNavPrefs }),

  moveLedgerPanel: (id, direction) =>
    set((s) => {
      const order = [...s.ledgerPrefs.order]
      const idx = order.indexOf(id)
      const swapWith = direction === 'up' ? idx - 1 : idx + 1
      if (idx === -1 || swapWith < 0 || swapWith >= order.length) return {}
      ;[order[idx], order[swapWith]] = [order[swapWith], order[idx]]
      return { ledgerPrefs: { ...s.ledgerPrefs, order } }
    }),

  reorderLedgerPanels: (order) => set((s) => ({ ledgerPrefs: { ...s.ledgerPrefs, order } })),

  toggleLedgerPanelHidden: (id) =>
    set((s) => {
      const isHidden = s.ledgerPrefs.hidden.includes(id)
      const hidden = isHidden
        ? s.ledgerPrefs.hidden.filter((x) => x !== id)
        : [...s.ledgerPrefs.hidden, id]
      // Keep at least one panel visible.
      if (!isHidden && hidden.length >= s.ledgerPrefs.order.length) return {}
      return { ledgerPrefs: { ...s.ledgerPrefs, hidden } }
    }),

  setLedgerPanelWidth: (id, width) =>
    set((s) => ({ ledgerPrefs: { ...s.ledgerPrefs, widths: { ...s.ledgerPrefs.widths, [id]: width } } })),

  resetLedgerPrefs: () => set({ ledgerPrefs: defaultLedgerPrefs }),

  selectTitle: (selectedTitleId) => set({ selectedTitleId }),

  openAddTitle: () => set({ isAddTitleOpen: true, preselectedResult: null }),
  openAddTitlePreselected: (result) => set({ isAddTitleOpen: true, preselectedResult: result }),
  closeAddTitle: () => set({ isAddTitleOpen: false, preselectedResult: null }),

  openDetailDrawer: (id) =>
    set({ selectedTitleId: id, isDetailDrawerOpen: true }),

  // selectedTitleId is intentionally NOT nulled here — keeping it non-null lets
  // TitleDetailDrawer derive the correct body class (e.g. spider-noir-bw) for
  // pinned easter-egg modes even after the drawer closes. browseByPerson /
  // browseByStudio DO null it because navigating away is a hard context switch.
  closeDetailDrawer: () =>
    set({ isDetailDrawerOpen: false, isRefreshMetadataOpen: false }),

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

  notifications: [],

  pushNotification: (n) =>
    set((s) => ({
      notifications: [{ ...n, id: crypto.randomUUID() }, ...s.notifications].slice(0, 5),
    })),

  dismissNotification: (id) =>
    set((s) => ({
      notifications: s.notifications.filter((n) => n.id !== id),
    })),

  activityFeedLastSeenAt: null,
  activityUnseenCount: 0,

  markActivityFeedSeen: () => set({ activityFeedLastSeenAt: new Date().toISOString(), activityUnseenCount: 0 }),

  refreshActivityUnseenCount: async () => {
    if (!get().user) return
    try {
      const feed = await fetchFriendActivityFeed()
      const lastSeen = get().activityFeedLastSeenAt
      const lastSeenMs = lastSeen ? new Date(lastSeen).getTime() : 0
      const unseen = feed.filter((e) => new Date(e.eventAt).getTime() > lastSeenMs).length
      set({ activityUnseenCount: unseen })
    } catch (err) {
      console.error('Failed to refresh friend activity feed count:', err)
    }
  },

  // ── Auth ───────────────────────────────────────────────────
  user: null,
  loadingUser: false,
  friendView: null,

  setUser: (user) => {
    set({ user })
    if (user) {
      get().loadUserLibrary()
      get().loadPinnedModes()
      get().refreshActivityUnseenCount()
    } else {
      // Clear on logout — restore mock data only in dev
      const fallback = import.meta.env.DEV ? mockTitles : []
      set((s) => ({
        titles: fallback,
        filteredTitles: applyFiltersToTitles(fallback, s.filters),
        stats: computeLedgerStats(fallback),
        pinnedModes: {},
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
      // Guard: if we have local titles but DB returned empty, the session auth
      // may not have fully propagated — skip the wipe rather than hiding data.
      const currentTitles = get().titles
      const hasRealLocalData = currentTitles.some((t) => !t.id.startsWith('mt-'))
      if (dbTitles.length === 0 && hasRealLocalData) {
        console.warn('loadUserLibrary: DB returned 0 titles but local store has user data — skipping replace. Check auth session.')
        return
      }
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

  // Reuses isSharedView for the existing read-only gating throughout the app
  // (TitleDetailDrawer, episode-card, Discover, etc.) — friendView just adds
  // who's being viewed, for the exit affordance and heading text.
  loadFriendLibrary: async (friendUserId, displayName) => {
    set({
      loadingUser: true,
      isSharedView: true,
      friendView: { userId: friendUserId, displayName },
      pendingView: 'library',
    })
    try {
      const dbTitles = await fetchFriendLibrary(friendUserId)
      set((s) => ({
        titles: dbTitles,
        filteredTitles: applyFiltersToTitles(dbTitles, s.filters),
        stats: computeLedgerStats(dbTitles),
      }))
    } catch (err) {
      console.error('Failed to load friend library from DB:', err)
      get().pushNotification({ message: "Couldn't load that friend's library — check your connection." })
    } finally {
      set({ loadingUser: false })
    }
  },

  exitFriendView: () => {
    // Clear the friend's titles before refetching — loadUserLibrary's
    // hasRealLocalData guard would otherwise see the friend's (real, non-mock)
    // titles still in state and skip the replace if the user's own library is
    // empty, stranding read-only-disabled friend data with edit controls live.
    set((s) => ({
      friendView: null,
      isSharedView: false,
      titles: [],
      filteredTitles: applyFiltersToTitles([], s.filters),
      stats: computeLedgerStats([]),
    }))
    void get().loadUserLibrary()
  },

  // ── Pins ───────────────────────────────────────────────────
  pinnedModes: {},

  setPinnedMode: (titleId, easterEggKey, variant) => {
    const key = `${titleId}:${easterEggKey}`
    if (variant === null) {
      set((s) => {
        const next = { ...s.pinnedModes }
        delete next[key]
        return { pinnedModes: next }
      })
      const user = get().user
      if (user) {
        deleteTitlePin(user.id, titleId, easterEggKey).catch((e) =>
          console.error('deleteTitlePin failed:', e)
        )
      }
    } else {
      set((s) => ({ pinnedModes: { ...s.pinnedModes, [key]: variant } }))
      const user = get().user
      if (user) {
        upsertTitlePin(user.id, titleId, easterEggKey, variant).catch((e) =>
          console.error('upsertTitlePin failed:', e)
        )
      }
    }
  },

  loadPinnedModes: async () => {
    const user = get().user
    if (!user) return
    const pins = await fetchAllTitlePins(user.id)
    const pinnedModes: Record<string, 'bw' | 'color'> = {}
    for (const pin of pins) {
      pinnedModes[`${pin.titleId}:${pin.easterEggKey}`] = pin.pinnedVariant
    }
    set({ pinnedModes })
  },
    }),
    {
      name: 'cinemarchive-library',
      version: PERSIST_VERSION,
      storage: createJSONStorage(() => localStorage),
      // Only the source of truth is persisted; derived state (filteredTitles,
      // stats) and transient UI flags are recomputed/reset on load. While
      // browsing a friend's library, `titles` holds THEIR data — never persist
      // that to the viewer's localStorage.
      partialize: (s) => ({
        titles: s.friendView ? [] : s.titles,
        filters: s.filters,
        viewMode: s.viewMode,
        theme: s.theme,
        unlockedThemes: s.unlockedThemes,
        navPrefs: s.navPrefs,
        ledgerPrefs: s.ledgerPrefs,
        activityFeedLastSeenAt: s.activityFeedLastSeenAt,
      }),
      onRehydrateStorage: () => (state) => {
        if (!state) return
        // Older persisted payloads may lack newer filter keys — backfill them.
        state.filters = { ...defaultFilters, ...state.filters }
        state.filteredTitles = applyFiltersToTitles(state.titles, state.filters)
        state.stats = computeLedgerStats(state.titles)
        // Older persisted payloads predate per-panel widths — backfill any missing ones.
        state.ledgerPrefs.widths = { ...DEFAULT_LEDGER_PANEL_WIDTHS, ...state.ledgerPrefs.widths }
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

export const useUpcomingTitles = (): UpcomingEntry[] => {
  const titles = useAppStore((s) => s.titles)
  return useMemo(() => {
    const today = new Date().toISOString().slice(0, 10)
    return computeUpcomingTitles(titles, today)
  }, [titles])
}
