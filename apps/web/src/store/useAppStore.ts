import { useMemo } from 'react'
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import { mockTitles, type Title, type CinemaOuting, type List, type LedgerStats, type WatchStatus, type MediaType } from './mockData'
import type { SharedOutingSnapshot } from '../lib/outingSharing'
import { computeLedgerStats } from './ledgerStats'
import { createBrowserCacheStorage } from '../lib/browserCacheStorage'
import { DEVICE_PREFERENCES_KEY, OfflineLibraryRuntime, hasLegacyLibraryCache, readLegacyDevicePreferences, pickDevicePreferences, fetchOwnerSnapshot, type OfflineLibraryStatus } from './offlineLibrary'
import { emptySnapshot, type OfflineSnapshot } from '../lib/offline/snapshot'
import type { TheaterInterest } from '../lib/moviegoingPreferences'
import { assertDeliverableCommand, createLibraryCommandDelivery } from '../lib/offlineRpc'
import { IndexedDbOfflineStore } from '../lib/offline/storage'
import { createTicketCommandDelivery } from '../lib/tickets/delivery'
import { ticketRemoteOptions } from '../lib/tickets/remote'
import type { TicketCapture } from '../lib/tickets/types'
import { createCommand } from '../lib/offline/commands'
import { createLibraryActions, type LibraryWrite } from './libraryActions'
import { computeUpNextShows, computeUpcomingTitles, type UpNextEntry, type UpcomingEntry } from './upNext'
import type { OutingSchedulePrefill } from './outings'
import type { User } from '@supabase/supabase-js'
import { isDevMockUser } from '../lib/devAuth'
import type { AppView, NavItemId } from '../lib/navigation'
import { DEFAULT_NAV_ORDER } from '../lib/navigation'
import type { LedgerPanelId, LedgerPanelWidth, LedgerWidget } from '../lib/ledgerPanels'
import {
  DEFAULT_LEDGER_PANEL_ORDER,
  DEFAULT_LEDGER_PANEL_WIDTHS,
  LEDGER_PANEL_LABELS,
  createLedgerWidget,
  defaultLedgerWidgets,
  normalizeLedgerWidgets,
} from '../lib/ledgerPanels'
import { decadeOf } from '../lib/utils'
import {
  fetchSharedLibrary, fetchFriendLibrary, fetchLedgerLayout,
  fetchNotifications, fetchUnreadNotificationCount, markNotificationRead, markAllNotificationsRead,
  deleteNotification,
  completeDueOutings,
  shareOutingPlans as shareOutingPlansRpc,
  type AppNotificationItem, type OutingCompletionResult,
} from '../lib/db'
import type { SearchResult } from '../lib/media'

// ─── Filter & Sort Types ────────────────────────────────────────────────────

export type SortField = 'title' | 'year' | 'rating' | 'addedAt' | 'director' | 'lastInteraction'
export type SortDir = 'asc' | 'desc'
export type ViewMode = 'grid' | 'list'

/** Poster-wall density. Drives how narrow a grid column is allowed to get, so
 *  'compact' fits more (smaller) posters per row and 'large' fewer. */
export type GridSize = 'compact' | 'default' | 'large'
export type Theme = 'dark' | 'light' | 'noir' | 'matrix'

/** The quick-toggle's 3-way choice — independent of the locked/secret
 *  noir/matrix styles, which are only picked from the full Appearance grid
 *  (and, when picked, count as an explicit dark-bucketed override; see
 *  `setTheme`). 'system' keeps tracking `prefers-color-scheme` live for as
 *  long as it's selected (see `watchSystemTheme` in lib/theme.ts) rather
 *  than just resolving it once. */
export type ThemeMode = 'light' | 'dark' | 'system'

/** Resolves 'system' mode to the current OS preference. Also used as the
 *  default theme for users with no persisted choice yet. Keep in sync with
 *  the inline FOUC script in index.html. */
export function getSystemTheme(): Theme {
  if (typeof window === 'undefined' || !window.matchMedia) return 'dark'
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

/** Nav bar layout/visibility preferences — order + hidden apply to both the
 *  TopBar pill nav and BottomNav; compact hides labels on the desktop pill nav. */
export interface NavPrefs {
  order: NavItemId[]
  hidden: NavItemId[]
  compact: boolean
}

/** Ledger dashboard layout preferences — an ordered list of widget instances
 *  on the board. A panel type not present in `widgets` is simply not on the
 *  board (re-addable from the layout editor's palette); the same panel type
 *  may appear more than once. */
export interface LedgerPrefs {
  widgets: LedgerWidget[]
}

/** Pre-widget-instance persisted shape (order/hidden/widths/heights keyed by
 *  panel type) — migrated to `widgets` on rehydrate. */
interface LegacyLedgerPrefs {
  order?: LedgerPanelId[]
  hidden?: LedgerPanelId[]
  widths?: Partial<Record<LedgerPanelId, LedgerPanelWidth>>
  heights?: Partial<Record<LedgerPanelId, number>>
}

/** A cast/crew person, keyed by TMDB id with a display name. */
export interface PersonRef {
  id: number
  name: string
}

/** A persistent notification. kind defaults to 'error'. */
export interface AppNotification {
  id: string
  /** Repeated reports of one operation update its existing notification. */
  dedupeKey?: string
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
  // ISO 639-1 original-language codes (e.g. "en", "ja")
  languages: string[]
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

interface LibrarySlice {
  titles: Title[]
  filters: LibraryFilters
  filteredTitles: Title[]

  setFilter: <K extends keyof LibraryFilters>(key: K, value: LibraryFilters[K]) => void
  resetFilters: () => void
  applyFilters: () => void
  // Marks every unwatched episode of a season (or the whole series when
  // seasonNumber is omitted) as watched before joining the platform: each gets
  // a dateless watch event. Series-wide marking also sets status to 'watched'.
}

interface LedgerSlice {
  stats: LedgerStats
  setStats: (stats: LedgerStats) => void
}

interface UISlice {
  viewMode: ViewMode
  gridSize: GridSize
  theme: Theme
  // The persisted 3-way quick-toggle choice (light/dark/system) driving
  // `theme` above. See `ThemeMode`'s doc comment.
  themeMode: ThemeMode
  // Themes beyond dark/light are locked until earned via an in-app easter egg
  // (e.g. Spider-Noir black & white, The Matrix red pill). Always includes
  // 'dark' and 'light'.
  unlockedThemes: Theme[]
  navPrefs: NavPrefs
  ledgerPrefs: LedgerPrefs
  // The board arrangement of whoever is being viewed in a shared/friend view.
  // Transient (never persisted) — it must not clobber the viewer's own
  // ledgerPrefs, which partialize writes to localStorage.
  viewedLedgerWidgets: LedgerWidget[] | null
  selectedTitleId: string | null
  isAddTitleOpen: boolean
  isDetailDrawerOpen: boolean
  isRefreshMetadataOpen: boolean
  // The list currently open in detail view (Lists.tsx), synced to ?list=<id> —
  // mirrors selectedTitleId/isDetailDrawerOpen's role for the title drawer, but
  // as a single nullable id since there's no easter-egg-style reason to keep it
  // around after close.
  selectedListId: string | null
  isSharedView: boolean
  isCommandPaletteOpen: boolean
  // A top-level view requested by a component that can't reach App's currentView
  // (e.g. the detail drawer). App consumes and clears it. null = nothing pending.
  pendingView: AppView | null
  // When non-null, AddTitleWorkflow skips to step 2 with this pre-selected result.
  preselectedResult: SearchResult | null

  setViewMode: (mode: ViewMode) => void
  setGridSize: (size: GridSize) => void
  // Explicit theme pick (Settings → Appearance grid, incl. noir/matrix).
  // Always breaks out of 'system' mode — dark/light set themeMode to match;
  // noir/matrix (both dark-on-black styles) bucket into 'dark'.
  setTheme: (theme: Theme) => void
  // The quick-toggle's 3-way pick. 'system' resolves `theme` to the current
  // OS preference and keeps tracking it live; 'light'/'dark' set both fields
  // to the same explicit value.
  setThemeMode: (mode: ThemeMode) => void
  // No-op if already unlocked or the theme is dark/light (always unlocked).
  unlockTheme: (theme: Theme) => void
  moveNavItem: (id: NavItemId, direction: 'up' | 'down') => void
  reorderNav: (order: NavItemId[]) => void
  toggleNavItemHidden: (id: NavItemId) => void
  setNavCompact: (compact: boolean) => void
  resetNavPrefs: () => void
  // Merge-patch a widget's settings; keys set to undefined are removed, and a
  // settings object with no remaining keys is dropped entirely.
  selectTitle: (id: string | null) => void
  openAddTitle: () => void
  openAddTitlePreselected: (result: SearchResult) => void
  closeAddTitle: () => void
  openDetailDrawer: (id: string) => void
  closeDetailDrawer: () => void
  openListDetail: (id: string) => void
  closeListDetail: () => void
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

  // Persistent notification inbox (bell icon) — distinct from the ephemeral
  // toast stack above. The list is fetched on demand by the bell dropdown;
  // this tracks the unread count shown as a badge, sourced from the server
  // (read_at), not a client-side "last seen" watermark.
  notificationInbox: AppNotificationItem[]
  unreadNotificationCount: number
  refreshUnreadNotificationCount: () => Promise<void>
  loadNotificationInbox: (before?: string) => Promise<void>
  markOneNotificationRead: (id: string) => Promise<void>
  markAllNotificationsSeen: () => Promise<void>
  deleteNotificationItem: (id: string) => Promise<void>
}

/**
 * Who's actually looking at this library right now. `isSharedView` (below)
 * remains the single boolean every read-only-gated component checks — this
 * type only matters where the *kind* of visitor matters (exit affordances,
 * per-friend/per-link scoping, comment write-gating).
 */
export type ViewerContext =
  | { kind: 'owner' }
  | { kind: 'shared-link'; token: string }
  | { kind: 'friend'; userId: string; displayName: string }

interface AuthSlice {
  theaterInterest: TheaterInterest[]
  moviegoingPreferencesSupport: OfflineSnapshot['moviegoingPreferencesSupport']
  setTheaterInterest: (titleId: string, present: boolean) => Promise<void>
  user: User | null
  loadingUser: boolean
  // Set when the last library load failed; cleared when a load starts or
  // succeeds. Views surface it instead of a misleading empty state.
  libraryLoadError: string | null
  viewerContext: ViewerContext
  offlineStatus: OfflineLibraryStatus
  librarySession: number
  offlineSyncError: string | null
  retryPendingCommand: (id: string) => Promise<void>
  discardPendingCommand: (id: string) => Promise<void>
  retryLibrarySync: () => Promise<void>
  discardDamagedCache: () => Promise<void>
  offlineStorageError: string | null
  legacyCacheAvailable: boolean
  setUser: (user: User | null) => void
  setLoadingUser: (loading: boolean) => void
  loadUserLibrary: () => Promise<void>
  loadSharedLibrary: (token: string) => Promise<void>
  loadFriendLibrary: (friendUserId: string, displayName: string) => Promise<void>
  exitFriendView: () => void
}

interface PinsSlice {
  pinnedModes: Record<string, 'bw' | 'color'>
  loadPinnedModes: () => Promise<void>
}

// User-created custom title lists. Private-only: owner-only RLS, loaded with the
// library (never fetched for shared/friend viewers, same convention as PinsSlice
// and OutingsSlice).
interface ListsSlice {
  lists: List[]
  // listId -> Set<titleId>. A Set gives O(1) membership checks for the
  // AddToListSheet chip picker; not persisted (see partialize below — Set
  // doesn't survive JSON.stringify/parse), re-derived by loadLists() instead.
  listMemberships: Record<string, Set<string>>
  loadLists: () => Promise<void>
  listsForTitle: (titleId: string) => List[]
}

// Cinema Outings ("I've got tickets") — see
// docs/superpowers/plans/2026-07-11-cinema-outings.md §7.2. Owner-only:
// loaded with the library, never fetched for shared/friend viewers.
interface OutingsSlice {
  outings: CinemaOuting[]
  // Pure bulk setter (no DB side effect, mirrors setTitles) — used by import,
  // which writes new outings to the DB itself before this runs (rule §5.13:
  // an outing's row must exist before a kept title's viewing back-references
  // it).
  // "I've got tickets" sheet (plan §4.1) — a single overlay reused by every
  // entry point. titleId preselects a movie (create mode); outingId, when
  // set, switches the sheet into edit mode for that outing (titleId is then
  // derived from the outing). Neither set → the sheet's own movie-picker step.
  // prefill seeds the create-mode form's showtime/venue/format — used by the
  // "I've got tickets too" CTA on a shared plan (plan §4.10); ignored in edit mode.
  isOutingScheduleOpen: boolean
  outingScheduleTitleId: string | null
  outingScheduleOutingId: string | null
  outingSchedulePrefill: OutingSchedulePrefill | null
  openOutingSchedule: (titleId?: string, outingId?: string, prefill?: OutingSchedulePrefill) => void
  closeOutingSchedule: () => void
  // Edit/reschedule — recomputes endsAt from the merged showtime/previews/runtime.
  // Soft-cancel (plan §4.2): kept as a history row, hidden from all surfaces.
  // Stamps follow_up_dismissed_at — called both on an explicit ✕ and after rating.
  shareOutingPlans: (outingId: string, recipientIds: string[], operationId: string) => Promise<SharedOutingSnapshot>
  attachOutingTicket: (outingId: string, capture: TicketCapture, blob: Blob) => Promise<void>
  detachOutingTicket: (outingId: string) => Promise<void>
  readOutingTicket: (outingId: string, attachmentId: string) => Promise<Blob>
  // "I've got tickets too" resolution (plan §4.10/§5.16) — if the shared
  // payload's tmdb_id isn't already in the library, adds it to the watchlist
  // first (same match-by-tmdbId+type resolution the recommendation inbox
  // uses), then returns the titleId either way for the prefilled sheet.
  // "Didn't make it" (plan §5.6): deletes the auto-logged viewing, reverts the
  // title status only when its completion revision is unchanged, and marks
  // the outing missed. Confirmed reversal removes its identified inbox item.
  // The single choke point for auto-completion (plan §4.3) — calls
  // complete_due_outings and applies whatever transitions it returns.
  reconcileOutings: () => Promise<void>
  // Post-show follow-up sheet (plan §4.4) — a single overlay, opened against a
  // specific completed outing from the bell inbox, the drawer's banner, or the
  // marquee's "Fresh from the lobby" card.
  isPostShowSheetOpen: boolean
  postShowOutingId: string | null
  openPostShowSheet: (outingId: string) => void
  closePostShowSheet: () => void
}

// ─── Default Nav Prefs ──────────────────────────────────────────────────────

const defaultNavPrefs: NavPrefs = {
  order: DEFAULT_NAV_ORDER,
  hidden: [],
  compact: false,
}

const defaultLedgerPrefs: LedgerPrefs = {
  widgets: defaultLedgerWidgets(),
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
  languages: [],
  minRating: 0,
  person: null,
  studio: null,
  groupByFranchise: false,
  sortField: 'lastInteraction',
  sortDir: 'desc',
}

// ─── Filter Logic ───────────────────────────────────────────────────────────

// True when the person (by TMDB id) appears anywhere in a title's credits:
// title cast/crew, any season's cast, or any episode's crew. Mirrored by
// scripts/verify-person-logic.mjs.
const titlePersonIdsCache = new WeakMap<Title, Set<number>>()

export function titleHasPerson(title: Title, personId: number): boolean {
  let personIds = titlePersonIdsCache.get(title)
  if (!personIds) {
    personIds = new Set<number>()
    title.cast?.forEach((c) => personIds!.add(c.tmdbPersonId))
    title.crew?.forEach((c) => personIds!.add(c.tmdbPersonId))
    for (const season of title.seasons ?? []) {
      season.cast?.forEach((c) => personIds!.add(c.tmdbPersonId))
      for (const ep of season.episodes ?? []) {
        ep.crew?.forEach((c) => personIds!.add(c.tmdbPersonId))
      }
    }
    titlePersonIdsCache.set(title, personIds)
  }
  return personIds.has(personId)
}

function timeOf(dateStr: string | undefined): number {
  return dateStr ? new Date(dateStr).getTime() : -Infinity
}

interface TitleSearchIndex {
  title: string
  director: string | undefined
  genres: string[]
  tags: string[]
  cast: string[]
}

const titleSearchCache = new WeakMap<Title, TitleSearchIndex>()

function getTitleSearch(t: Title): TitleSearchIndex {
  let index = titleSearchCache.get(t)
  if (!index) {
    index = {
      title: t.title.toLowerCase(),
      director: t.director?.toLowerCase(),
      genres: t.genres.map((g) => g.toLowerCase()),
      tags: t.tags.map((tag) => tag.toLowerCase()),
      cast: t.cast?.map((c) => c.name.toLowerCase()) || [],
    }
    titleSearchCache.set(t, index)
  }
  return index
}

// ⚡ Bolt: Cache expensive derived calculation with a WeakMap to prevent O(N*M) redundant computations
const lastInteractionCache = new WeakMap<Title, number>()

/** Most recent user interaction with a title: added, (re)watched, or — for
 *  TV — any per-episode watch/rating/review event. Deliberately excludes
 *  `titles.updated_at` (bumped by bulk metadata refresh on every title, which
 *  would collapse this into "everything touched just now") and sharing
 *  (no per-title timestamp exists for that in the data model). */
export function titleLastInteractionAt(title: Title): number {
  if (lastInteractionCache.has(title)) {
    return lastInteractionCache.get(title)!
  }

  let latest = timeOf(title.addedAt)
  for (const v of title.viewings) {
    latest = Math.max(latest, timeOf(v.date))
  }
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) {
      for (const we of ep.watchEvents) latest = Math.max(latest, timeOf(we.watchedAt))
      for (const r of ep.ratings) latest = Math.max(latest, timeOf(r.ratedAt))
      for (const rv of ep.reviews) latest = Math.max(latest, timeOf(rv.reviewedAt))
    }
  }

  lastInteractionCache.set(title, latest)
  return latest
}

function applyFiltersToTitles(titles: Title[], filters: LibraryFilters): Title[] {
  let result = [...titles]

  if (filters.search.trim()) {
    const q = filters.search.toLowerCase()
    result = result.filter((t) => {
      const idx = getTitleSearch(t)
      return (
        idx.title.includes(q) ||
        idx.director?.includes(q) ||
        idx.genres.some((g) => g.includes(q)) ||
        idx.tags.some((tag) => tag.includes(q)) ||
        idx.cast.some((c) => c.includes(q))
      )
    })
  }

  if (filters.type !== 'all') {
    result = result.filter((t) => t.type === filters.type)
  }

  if (filters.status !== 'all') {
    result = result.filter((t) => t.status === filters.status)
  }

  if (filters.genres.length > 0) {
    // ⚡ Bolt: Replace O(N*M) array includes with O(1) Set lookup
    const filterGenres = new Set(filters.genres)
    result = result.filter((t) => t.genres.some((g) => filterGenres.has(g)))
  }

  if (filters.tags.length > 0) {
    // ⚡ Bolt: Replace O(N*M) array includes with O(1) Set lookup
    const filterTags = new Set(filters.tags)
    result = result.filter((t) => t.tags.some((tag) => filterTags.has(tag)))
  }

  if (filters.networks.length > 0) {
    // ⚡ Bolt: Replace O(N*M) array includes with O(1) Set lookup
    const filterNetworks = new Set(filters.networks)
    result = result.filter((t) => t.network && filterNetworks.has(t.network))
  }

  if (filters.decades.length > 0) {
    // ⚡ Bolt: Replace O(N*M) array includes with O(1) Set lookup
    const filterDecades = new Set(filters.decades)
    result = result.filter((t) => {
      const decade = `${decadeOf(t.year)}s`
      return filterDecades.has(decade)
    })
  }

  if (filters.languages.length > 0) {
    // ⚡ Bolt: Replace O(N*M) array includes with O(1) Set lookup
    const filterLanguages = new Set(filters.languages)
    result = result.filter((t) => t.originalLanguage && filterLanguages.has(t.originalLanguage))
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
  // Precomputed once per sort pass — titleLastInteractionAt walks every
  // episode's watch/rating/review events, so calling it per-comparison
  // would redo that work O(n log n) times instead of O(n).
  // ⚡ Bolt: Precompute addedAt timestamps to avoid O(N log N) date parsing inside the sort loop.
  const addedAtById =
    filters.sortField === 'addedAt'
      ? new Map(result.map((t) => [t.id, new Date(t.addedAt).getTime()]))
      : null
  const lastInteractionById =
    filters.sortField === 'lastInteraction'
      ? new Map(result.map((t) => [t.id, titleLastInteractionAt(t)]))
      : null

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
        comparison = (addedAtById!.get(a.id) ?? 0) - (addedAtById!.get(b.id) ?? 0)
        break
      case 'lastInteraction':
        comparison = (lastInteractionById!.get(a.id) ?? 0) - (lastInteractionById!.get(b.id) ?? 0)
        break
      case 'director':
        comparison = (a.director ?? '').localeCompare(b.director ?? '')
        break
    }
    return filters.sortDir === 'desc' ? -comparison : comparison
  })

  return result
}

// Every mutator that replaces `titles` must keep `filteredTitles`/`stats` in
// sync with it — bundling the recompute here means a new mutator can't forget
// one half of the pair.
function withDerivedTitles(titles: Title[], filters: LibraryFilters) {
  return {
    titles,
    filteredTitles: applyFiltersToTitles(titles, filters),
    stats: computeLedgerStats(titles),
  }
}

// Swap the element at `idx` with its "up"/"down" neighbor. Returns null (no
// change) when `idx` wasn't found or the neighbor would fall outside the list.
function swapAdjacent<T>(list: T[], idx: number, direction: 'up' | 'down'): T[] | null {
  const swapWith = direction === 'up' ? idx - 1 : idx + 1
  if (idx === -1 || swapWith < 0 || swapWith >= list.length) return null
  const next = [...list]
  ;[next[idx], next[swapWith]] = [next[swapWith], next[idx]]
  return next
}

// ─── Store ──────────────────────────────────────────────────────────────────

type AppStore = LibrarySlice & LedgerSlice & UISlice & AuthSlice & PinsSlice & OutingsSlice & ListsSlice & ReturnType<typeof createLibraryActions>

// Bump when the persisted shape changes incompatibly; older payloads are dropped.
const PERSIST_VERSION = 2

// Guards the one-time default-sort migration below (separate localStorage key
// so it fires exactly once, independent of PERSIST_VERSION).
const SORT_DEFAULT_MIGRATION_KEY = 'cinemarchive-sort-default-migrated'

// Polls the unread notification count while a user is logged in — the inbox
// has no Supabase Realtime subscription, so this is what keeps the bell
// current for a friend's comment/reaction/request arriving mid-session.
const NOTIFICATION_POLL_MS = 45_000
let notificationPollTimer: number | undefined
let ownerLibraryRequest: { userId: string; promise: Promise<void> } | undefined
const LIBRARY_ERROR_KEY = 'owner-library-load'
let libraryGeneration = 0
let runtimeOwnerId: string | null = null
let libraryHydration: Promise<void> | undefined
let localWriteTail: Promise<unknown> = Promise.resolve()

function writeLocalLibrary<T>(work: (state: AppStore) => Promise<T>): Promise<T> {
  const generation = libraryGeneration
  const ownerId = useAppStore.getState().user?.id ?? null
  const current = () => generation === libraryGeneration && (useAppStore.getState().user?.id ?? null) === ownerId
  const pending = localWriteTail.catch(() => {}).then(async () => {
    if (!current()) throw new Error('Account changed before this change could be saved')
    if (!useAppStore.getState().offlineStatus.hydrated) await libraryHydration
    if (!current()) throw new Error('Account changed before this change could be saved')
    const state = useAppStore.getState()
    if (state.isSharedView || state.viewerContext.kind !== 'owner') throw new Error('This library is read-only')
    if (!state.offlineStatus.hydrated) throw new Error('Your local library is still loading. Please retry shortly.')
    if (state.offlineStatus.quarantined.length) throw new Error('Recover the damaged local cache before saving more changes')
    const result = await work(state)
    if (!current()) throw new Error('The change was saved for the previous account; the account has now changed')
    useAppStore.setState({ offlineStorageError: null })
    return result
  })
  localWriteTail = pending
  // Handle ignored event-handler promises as well as awaited form saves. The
  // original promise still rejects so callers never close/announce success.
  void pending.catch((error) => {
    if (!current()) return
    const message = error instanceof Error ? error.message : 'This change could not be saved on this device'
    useAppStore.setState({ offlineStorageError: message })
    useAppStore.getState().pushNotification({ dedupeKey: 'offline-local-write', message })
  })
  return pending
}

const writeLibrary: LibraryWrite = (prepare) => writeLocalLibrary(async (state) => {
  const { mutation, result } = prepare(state)
  if (mutation) {
    const ownerId = state.user && !isDevMockUser(state.user) ? state.user.id : 'anonymous-local-only'
    assertDeliverableCommand(createCommand({ projectId: import.meta.env.VITE_SUPABASE_URL || 'local', userId: ownerId }, mutation))
    if (state.user && !isDevMockUser(state.user)) await libraryRuntime.submit(mutation)
    else await libraryRuntime.submitAnonymous(mutation)
  }
  return result
})

function afterOutingRevert(outingId: string, confirmed = false): void {
  const state = useAppStore.getState()
  if (state.user && !isDevMockUser(state.user) && !confirmed) return
  const outing = state.outings.find((row) => row.id === outingId && row.status === 'missed')
  if (!outing) return
  const stale = state.notificationInbox.filter((item) => item.type === 'outing_completed' && item.titleId === outing.titleId && item.payload.outingId === outingId)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]
  if (stale) void state.deleteNotificationItem(stale.id)
}

function snapshotState(snapshot: OfflineSnapshot, s: AppStore): Partial<AppStore> {
  return { ...withDerivedTitles(snapshot.titles, s.filters), outings: snapshot.outings,
    theaterInterest: snapshot.theaterInterest ?? [], moviegoingPreferencesSupport: snapshot.moviegoingPreferencesSupport,
    lists: snapshot.lists,
    listMemberships: Object.fromEntries(Object.entries(snapshot.listMemberships).map(([id, members]) => [id, new Set(members)])),
    pinnedModes: snapshot.pinnedModes,
    ledgerPrefs: { widgets: snapshot.ledgerWidgets ?? defaultLedgerPrefs.widgets },
  }
}

function reportOfflineStorageError(error: unknown): void {
  console.error('Browser library persistence failed:', error)
  const message = "Couldn't save this browser's library data. Keep this tab open and retry."
  useAppStore.setState({ offlineStorageError: message })
  useAppStore.getState().pushNotification({ dedupeKey: 'offline-storage-error', message })
}

const ownerOfflineStorage = new IndexedDbOfflineStore()
const libraryRuntime = new OfflineLibraryRuntime({
  projectId: import.meta.env.VITE_SUPABASE_URL || 'unconfigured-local',
  ownerStorage: ownerOfflineStorage,
  deliver: createLibraryCommandDelivery(fetchOwnerSnapshot, createTicketCommandDelivery({ ...ticketRemoteOptions,
    readBlob: (scope, id) => ownerOfflineStorage.readTicketBlob(scope, id),
    fetchBase: (context) => fetchOwnerSnapshot(context, true),
  }), (outingId) => afterOutingRevert(outingId, true)),
  onSnapshot: (snapshot) => {
    const s = useAppStore.getState()
    const userId = s.user && !isDevMockUser(s.user) ? s.user.id : null
    if (s.isSharedView || s.viewerContext.kind !== 'owner' || userId !== runtimeOwnerId) return
    useAppStore.setState(snapshotState(snapshot ?? emptySnapshot(), s))
  },
  onStatus: (offlineStatus) => {
    const s = useAppStore.getState()
    const userId = s.user && !isDevMockUser(s.user) ? s.user.id : null
    if (s.isSharedView || s.viewerContext.kind !== 'owner' || userId !== offlineStatus.ownerId) return
    useAppStore.setState({ offlineStatus })
  },
  onError: (error) => useAppStore.setState({ offlineSyncError: error instanceof Error ? error.message : 'Library sync is unavailable' }),
})

function stopLibraryRuntime(): void {
  libraryGeneration++
  useAppStore.setState({ librarySession: libraryGeneration, offlineSyncError: null })
  runtimeOwnerId = null
  libraryRuntime.deactivate()
}

async function loadAnonymousLibrary(): Promise<void> {
  runtimeOwnerId = null
  libraryHydration = libraryRuntime.loadAnonymous({ ...emptySnapshot(), titles: import.meta.env.DEV ? mockTitles : [] })
  await libraryHydration
}

const browserCacheStorage = createBrowserCacheStorage(() => localStorage, (error) => {
  console.warn('Browser offline cache unavailable:', error)
  // Hydration may encounter blocked storage while the store is still being
  // constructed; defer notification until initialization/current mutation ends.
  queueMicrotask(() => useAppStore.getState().pushNotification({
    dedupeKey: 'browser-cache-unavailable',
    message: "Couldn't update this browser's offline cache. Changes are still in this tab, but may not survive a reload. Signed-in changes can still sync online.",
  }))
})

export const useAppStore = create<AppStore>()(
  persist(
    (set, get): AppStore => ({
  // ── Library ────────────────────────────────────────────────
  titles: import.meta.env.DEV ? mockTitles : [],
  filters: defaultFilters,
  filteredTitles: applyFiltersToTitles(import.meta.env.DEV ? mockTitles : [], defaultFilters),

  ...createLibraryActions(writeLibrary, afterOutingRevert),
  attachOutingTicket: (outingId, capture, blob) => writeLocalLibrary(() => libraryRuntime.attachTicket(outingId, capture, blob)),
  detachOutingTicket: (outingId) => writeLocalLibrary(() => libraryRuntime.detachTicket(outingId)),
  readOutingTicket: (outingId, attachmentId) => {
    const state = get()
    if (state.isSharedView || state.viewerContext.kind !== 'owner') return Promise.reject(new Error('Tickets are private to the library owner'))
    return libraryRuntime.readTicketPhoto(outingId, attachmentId)
  },

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
  stats: computeLedgerStats(import.meta.env.DEV ? mockTitles : []),

  setStats: (stats) => set({ stats }),

  // ── UI ─────────────────────────────────────────────────────
  viewMode: 'grid',
  gridSize: 'default',
  theme: getSystemTheme(),
  themeMode: 'system',
  unlockedThemes: ['dark', 'light'],
  navPrefs: defaultNavPrefs,
  ledgerPrefs: defaultLedgerPrefs,
  viewedLedgerWidgets: null,
  selectedTitleId: null,
  isAddTitleOpen: false,
  isDetailDrawerOpen: false,
  selectedListId: null,
  preselectedResult: null,

  setViewMode: (viewMode) => set({ viewMode }),

  setGridSize: (gridSize) => set({ gridSize }),

  setTheme: (theme) => set({ theme, themeMode: theme === 'light' ? 'light' : 'dark' }),

  setThemeMode: (mode) => set({ themeMode: mode, theme: mode === 'system' ? getSystemTheme() : mode }),

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
      const order = swapAdjacent(s.navPrefs.order, s.navPrefs.order.indexOf(id), direction)
      return order ? { navPrefs: { ...s.navPrefs, order } } : {}
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

  openListDetail: (id) => set({ selectedListId: id }),
  closeListDetail: () => set({ selectedListId: null }),

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
    set((s) => {
      const existing = n.dedupeKey
        ? s.notifications.find((item) => item.dedupeKey === n.dedupeKey)
        : undefined
      return {
        notifications: existing
          ? s.notifications.map((item) => item.id === existing.id ? { ...n, id: existing.id } : item)
          : [{ ...n, id: crypto.randomUUID() }, ...s.notifications].slice(0, 5),
      }
    }),

  dismissNotification: (id) =>
    set((s) => ({
      notifications: s.notifications.filter((n) => n.id !== id),
    })),

  notificationInbox: [],
  unreadNotificationCount: 0,

  refreshUnreadNotificationCount: async () => {
    const userId = get().user?.id
    if (!userId) return
    const generation = libraryGeneration
    try {
      const count = await fetchUnreadNotificationCount()
      if (libraryGeneration !== generation || get().user?.id !== userId) return
      set({ unreadNotificationCount: count })
    } catch (err) {
      console.error('Failed to refresh unread notification count:', err)
    }
  },

  loadNotificationInbox: async (before) => {
    const userId = get().user?.id
    if (!userId) return
    const generation = libraryGeneration
    try {
      const page = await fetchNotifications(before)
      if (libraryGeneration !== generation || get().user?.id !== userId) return
      set((s) => ({ notificationInbox: before ? [...s.notificationInbox, ...page] : page }))
    } catch (err) {
      console.error('Failed to load notification inbox:', err)
    }
  },

  markOneNotificationRead: async (id) => {
    const generation = libraryGeneration
    const prev = get().notificationInbox
    set((s) => ({
      notificationInbox: s.notificationInbox.map((n) => (n.id === id && !n.readAt ? { ...n, readAt: new Date().toISOString() } : n)),
      unreadNotificationCount: Math.max(0, s.unreadNotificationCount - (prev.find((n) => n.id === id)?.readAt ? 0 : 1)),
    }))
    try {
      await markNotificationRead(id)
    } catch (err) {
      if (libraryGeneration !== generation) return
      console.error('Failed to mark notification read:', err)
      set({ notificationInbox: prev })
      void get().refreshUnreadNotificationCount()
    }
  },

  markAllNotificationsSeen: async () => {
    const generation = libraryGeneration
    const prev = get().notificationInbox
    const now = new Date().toISOString()
    set((s) => ({
      notificationInbox: s.notificationInbox.map((n) => (n.readAt ? n : { ...n, readAt: now })),
      unreadNotificationCount: 0,
    }))
    try {
      await markAllNotificationsRead()
    } catch (err) {
      if (libraryGeneration !== generation) return
      console.error('Failed to mark all notifications read:', err)
      set({ notificationInbox: prev })
      void get().refreshUnreadNotificationCount()
    }
  },

  deleteNotificationItem: async (id) => {
    const generation = libraryGeneration
    const prev = get().notificationInbox
    const removed = prev.find((n) => n.id === id)
    set((s) => ({
      notificationInbox: s.notificationInbox.filter((n) => n.id !== id),
      unreadNotificationCount: removed && !removed.readAt ? Math.max(0, s.unreadNotificationCount - 1) : s.unreadNotificationCount,
    }))
    try {
      await deleteNotification(id)
    } catch (err) {
      if (libraryGeneration !== generation) return
      console.error('Failed to delete notification:', err)
      set({ notificationInbox: prev })
      void get().refreshUnreadNotificationCount()
    }
  },

  // ── Auth ───────────────────────────────────────────────────
  theaterInterest: [],
  moviegoingPreferencesSupport: undefined,
  setTheaterInterest: (titleId, present) => writeLocalLibrary(async (state) => {
    if (!state.user || isDevMockUser(state.user)) throw new Error('Sign in to save a private theater preference')
    if (state.moviegoingPreferencesSupport !== 'authoritative') throw new Error('Sync moviegoing preferences with the updated server before editing theater interest')
    if (!state.titles.some((title) => title.id === titleId && title.type === 'movie')) throw new Error('This movie is no longer in your library')
    if (state.theaterInterest.some((row) => row.titleId === titleId) === present) return
    await libraryRuntime.submit({ kind: 'theaterInterest.set', titleId, userId: state.user.id, present, createdAt: new Date().toISOString() })
  }),
  user: null,
  loadingUser: false,
  libraryLoadError: null,
  viewerContext: { kind: 'owner' },
  offlineStatus: { ownerId: null, hydrated: false, commands: [], quarantined: [] },
  librarySession: 0,
  offlineSyncError: null,
  retryPendingCommand: async (id) => {
    const generation = libraryGeneration
    set({ offlineSyncError: null })
    await libraryRuntime.retry(id)
    if (generation !== libraryGeneration) throw new Error('Library account changed')
  },
  discardPendingCommand: async (id) => {
    const generation = libraryGeneration
    await libraryRuntime.discard(id)
    if (generation !== libraryGeneration) throw new Error('Library account changed')
    await libraryRuntime.refresh()
  },
  retryLibrarySync: async () => {
    const generation = libraryGeneration
    set({ offlineSyncError: null })
    await libraryRuntime.flush()
    if (generation !== libraryGeneration) throw new Error('Library account changed')
    await libraryRuntime.refresh()
  },
  discardDamagedCache: () => libraryRuntime.discardDamagedCache(),
  offlineStorageError: null,
  legacyCacheAvailable: hasLegacyLibraryCache(browserCacheStorage),

  setUser: (user) => {
    const previousUserId = get().user?.id
    // SIGNED_IN on tab focus and TOKEN_REFRESHED don't change library ownership.
    if (user && user.id === previousUserId) { set({ user }); return }
    stopLibraryRuntime()
    ownerLibraryRequest = undefined
    set((s) => ({
      ...snapshotState(emptySnapshot(), s), user, loadingUser: Boolean(user), libraryLoadError: null,
      offlineStorageError: null, notifications: [], notificationInbox: [], unreadNotificationCount: 0,
      offlineStatus: { ownerId: user?.id ?? null, hydrated: false, commands: [], quarantined: [] },
      viewerContext: { kind: 'owner' }, isSharedView: false, viewedLedgerWidgets: null,
      selectedTitleId: null, selectedListId: null, isDetailDrawerOpen: false, isAddTitleOpen: false,
      isOutingScheduleOpen: false, isPostShowSheetOpen: false, postShowOutingId: null,
    }))
    window.clearInterval(notificationPollTimer)
    notificationPollTimer = undefined
    if (get().legacyCacheAvailable) get().pushNotification({ dedupeKey: 'legacy-library-recovery', kind: 'tip',
      message: 'An older library cache is preserved on this device for recovery. It has not been assigned to this account.',
    })
    if (user && isDevMockUser(user)) {
      void loadAnonymousLibrary().catch(reportOfflineStorageError).finally(() => {
        if (get().user?.id === user.id) set({ loadingUser: false })
      })
      return
    }
    if (user) {
      void get().loadUserLibrary()
      get().refreshUnreadNotificationCount()
      notificationPollTimer = window.setInterval(() => get().refreshUnreadNotificationCount(), NOTIFICATION_POLL_MS)
    } else {
      void loadAnonymousLibrary().catch(reportOfflineStorageError)
    }
  },

  setLoadingUser: (loadingUser) => set({ loadingUser }),

  loadUserLibrary: async () => {
    const user = get().user
    if (!user || get().isSharedView) return
    if (ownerLibraryRequest?.userId === user.id) return ownerLibraryRequest.promise
    const request = { userId: user.id, promise: Promise.resolve() }
    ownerLibraryRequest = request
    const isCurrent = () => ownerLibraryRequest === request &&
      get().user?.id === user.id && !get().isSharedView
    set({ loadingUser: true, libraryLoadError: null })
    request.promise = (async () => {
      try {
        // Outings ride along with the owner's own library fetch (rule §9 —
        // owner-private; never fetched for shared/friend views).
        if (runtimeOwnerId !== user.id) {
                  runtimeOwnerId = user.id
          libraryHydration = libraryRuntime.activate(user.id)
          await libraryHydration
        }
        if (!isCurrent()) return
        await libraryRuntime.refresh()
        if (!isCurrent()) return
        set((s) => ({
          notifications: s.notifications.filter((n) => n.dedupeKey !== LIBRARY_ERROR_KEY),
        }))
        // Reconciliation trigger: app load, right after the library lands
        // (plan §4.3) — completes anything that finished while the app was closed.
        void get().reconcileOutings()
      } catch (err) {
        if (!isCurrent()) return
        console.error('Failed to load user library from DB:', err)
        const message = (err as { code?: string })?.code === '57014'
          ? "Loading your library timed out — please retry."
          : "Couldn't load your library — please retry."
        set({ libraryLoadError: message })
        get().pushNotification({
          dedupeKey: LIBRARY_ERROR_KEY,
          message,
          retry: async () => {
            await get().loadUserLibrary()
            if (get().libraryLoadError) throw new Error(get().libraryLoadError!)
          },
        })
      } finally {
        if (isCurrent()) set({ loadingUser: false })
        if (ownerLibraryRequest === request) ownerLibraryRequest = undefined
      }
    })()
    return request.promise
  },

  loadSharedLibrary: async (token) => {
    stopLibraryRuntime()
    const generation = libraryGeneration
    ownerLibraryRequest = undefined
    set((s) => ({ ...snapshotState(emptySnapshot(), s), loadingUser: true, isSharedView: true,
      offlineStatus: { ownerId: null, hydrated: false, commands: [], quarantined: [] },
      viewedLedgerWidgets: null, viewerContext: { kind: 'shared-link', token }, libraryLoadError: null }))
    try {
      const { titles: dbTitles, ledgerWidgets } = await fetchSharedLibrary(token)
      if (libraryGeneration !== generation) return
      set((s) => ({ ...withDerivedTitles(dbTitles, s.filters), viewedLedgerWidgets: ledgerWidgets }))
    } catch (err) {
      if (libraryGeneration !== generation) return
      console.error('Failed to load shared library from DB:', err)
      set({ libraryLoadError: "Couldn't load this shared library — the link may have expired." })
    } finally {
      if (libraryGeneration === generation) set({ loadingUser: false })
    }
  },

  // Reuses isSharedView for the existing read-only gating throughout the app
  // (TitleDetailDrawer, episode-card, Discover, etc.) — viewerContext just adds
  // who's being viewed, for the exit affordance and heading text.
  loadFriendLibrary: async (friendUserId, displayName) => {
    stopLibraryRuntime()
    const generation = libraryGeneration
    ownerLibraryRequest = undefined
    set((s) => ({
      ...snapshotState(emptySnapshot(), s), viewedLedgerWidgets: null,
      offlineStatus: { ownerId: null, hydrated: false, commands: [], quarantined: [] },
      loadingUser: true,
      isSharedView: true,
      libraryLoadError: null,
      viewerContext: { kind: 'friend', userId: friendUserId, displayName },
      pendingView: 'library',
    }))
    try {
      const dbTitles = await fetchFriendLibrary(friendUserId)
      if (libraryGeneration !== generation) return
      set((s) => withDerivedTitles(dbTitles, s.filters))
      // Show the friend's board arrangement (read-only RLS policy).
      void fetchLedgerLayout(friendUserId)
        .then((widgets) => { if (libraryGeneration === generation) set({ viewedLedgerWidgets: widgets }) })
        .catch(() => { if (libraryGeneration === generation) set({ viewedLedgerWidgets: null }) })
    } catch (err) {
      if (libraryGeneration !== generation) return
      console.error('Failed to load friend library from DB:', err)
      set({ libraryLoadError: "Couldn't load that friend's library — check your connection." })
      get().pushNotification({ message: "Couldn't load that friend's library — check your connection." })
    } finally {
      if (libraryGeneration === generation) set({ loadingUser: false })
    }
  },

  exitFriendView: () => {
    stopLibraryRuntime()
    // Clear the friend's titles before refetching — loadUserLibrary's
    // hasRealLocalData guard would otherwise see the friend's (real, non-mock)
    // titles still in state and skip the replace if the user's own library is
    // empty, stranding read-only-disabled friend data with edit controls live.
    set((s) => ({
      viewerContext: { kind: 'owner' },
      isSharedView: false,
      viewedLedgerWidgets: null,
      ...withDerivedTitles([], s.filters),
    }))
    void get().loadUserLibrary()
  },

  // ── Pins ───────────────────────────────────────────────────
  pinnedModes: {},

  loadPinnedModes: async () => {
    await get().loadUserLibrary()
  },

  // ── Lists ──────────────────────────────────────────────────
  lists: [],
  listMemberships: {},

  loadLists: async () => {
    await get().loadUserLibrary()
  },

  listsForTitle: (titleId) => get().lists.filter((l) => get().listMemberships[l.id]?.has(titleId)),

  // ── Cinema Outings ("I've got tickets") ─────────────────────
  outings: [],


  isOutingScheduleOpen: false,
  outingScheduleTitleId: null,
  outingScheduleOutingId: null,
  outingSchedulePrefill: null,
  openOutingSchedule: (titleId, outingId, prefill) => {
    // Rule (plan ground rules): isSharedView never renders scheduling actions.
    // Every entry point already gates on it, but guarding here too means a
    // stray call can't slip an owner-only overlay into a shared/friend session.
    if (get().isSharedView) return
    set({
      isOutingScheduleOpen: true,
      outingScheduleTitleId: titleId ?? null,
      outingScheduleOutingId: outingId ?? null,
      outingSchedulePrefill: prefill ?? null,
    })
  },
  closeOutingSchedule: () =>
    set({ isOutingScheduleOpen: false, outingScheduleTitleId: null, outingScheduleOutingId: null, outingSchedulePrefill: null }),

  isPostShowSheetOpen: false,
  postShowOutingId: null,
  openPostShowSheet: (outingId) => {
    if (get().isSharedView) return
    set({ isPostShowSheetOpen: true, postShowOutingId: outingId })
  },
  closePostShowSheet: () => set({ isPostShowSheetOpen: false, postShowOutingId: null }),

  shareOutingPlans: async (outingId, recipientIds, operationId) => {
    const generation = libraryGeneration
    const ownerId = get().user?.id
    const current = () => generation === libraryGeneration && get().user?.id === ownerId && !get().isSharedView && get().viewerContext.kind === 'owner'
    try {
      if (!ownerId || !current()) throw new Error('Sign in to your own library to share plans')
      await localWriteTail.catch(() => {})
      await libraryHydration
      if (!current()) throw new Error('Library account changed')
      const outing = get().outings.find((row) => row.id === outingId)
      if (!outing) throw new Error('This outing is no longer in your library')
      return await libraryRuntime.runSyncedRemote([`outing:${outingId}`, `title:${outing.titleId}`], async (snapshot, context, assertReady) => {
        const latest = snapshot.outings.find((row) => row.id === outingId)
        if (!current()) throw new Error('Library account changed')
        if (!latest || !snapshot.titles.some((row) => row.id === latest.titleId)) throw new Error('This outing is no longer in your library')
        if (latest.status !== 'scheduled' || !(Date.parse(latest.endsAt) > Date.now())) throw new Error('Only upcoming scheduled outings can be shared')
        return shareOutingPlansRpc(outingId, [...new Set(recipientIds)], operationId, context, assertReady)
      })
    } catch (err) {
      if (current()) get().pushNotification({ message: err instanceof Error ? err.message : "Sharing was not confirmed. Retry to check the same send." })
      throw err
    }
  },

  reconcileOutings: async () => {
    const user = get().user
    if (!user || get().isSharedView || !libraryRuntime.canReconcile) return
    const generation = libraryGeneration

    let results: OutingCompletionResult[]
    try {
      const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
      results = await libraryRuntime.runIdleRemote((context) => completeDueOutings(tz, context)) ?? []
    } catch (err) {
      console.error('Failed to reconcile cinema outings:', err)
      return
    }
    if (results.length === 0 || libraryGeneration !== generation || get().user?.id !== user.id || get().isSharedView) return

    // One toast per completed outing (plan §4.4).
    for (const r of results) {
      const title = get().titles.find((t) => t.id === r.titleId)
      if (title) {
        get().pushNotification({
          kind: 'tip',
          autoClose: 6000,
          message: `Marked ${title.title} watched — hope it was worth the popcorn.`,
        })
      }
    }

    void get().refreshUnreadNotificationCount()
  },
    }),
    {
      name: DEVICE_PREFERENCES_KEY,
      version: PERSIST_VERSION,
      storage: createJSONStorage(() => ({ ...browserCacheStorage,
        getItem: (name) => browserCacheStorage.getItem(name) ?? (name === DEVICE_PREFERENCES_KEY
          ? JSON.stringify({ version: PERSIST_VERSION, state: readLegacyDevicePreferences(browserCacheStorage) }) : null),
      })),
      // Device presentation only. Authenticated and anonymous libraries live
      // in separate IndexedDB databases; shared/friend data is never persisted.
      partialize: (s) => ({
        filters: s.filters,
        viewMode: s.viewMode,
        gridSize: s.gridSize,
        theme: s.theme,
        themeMode: s.themeMode,
        unlockedThemes: s.unlockedThemes,
        navPrefs: s.navPrefs,
      }),
      merge: (persisted, current) => ({ ...current, ...pickDevicePreferences(persisted) }),
      onRehydrateStorage: () => (state) => {
        if (!state) return
        // Older persisted payloads predate themeMode entirely — preserve their
        // persisted theme as an explicit choice rather than opting them into
        // live system-tracking they never asked for.
        if (state.themeMode === undefined) {
          state.themeMode = state.theme === 'light' ? 'light' : 'dark'
        }
        // A 'system' mode may be stale after time away — re-resolve against
        // the current OS preference now, same as the FOUC script does.
        if (state.themeMode === 'system') {
          state.theme = getSystemTheme()
        }
        // Older persisted payloads may lack newer filter keys — backfill them.
        state.filters = { ...defaultFilters, ...state.filters }
        // Backfill any NavItemId a user's persisted navPrefs.order predates
        // (e.g. 'lists') — append missing ids at the end rather than resetting
        // the whole array, so a user's existing customization/reordering
        // survives. Without this, a returning user would never see a newly
        // added nav tab.
        const missingNavItems = DEFAULT_NAV_ORDER.filter((id) => !state.navPrefs.order.includes(id))
        if (missingNavItems.length > 0) {
          state.navPrefs = { ...state.navPrefs, order: [...state.navPrefs.order, ...missingNavItems] }
        }
        // One-time migration: the default sort used to be 'addedAt'. Flip
        // still-on-default users over to the new 'lastInteraction' default
        // without touching anyone who has since picked a different sort.
        if (!browserCacheStorage.getItem(SORT_DEFAULT_MIGRATION_KEY)) {
          if (state.filters.sortField === 'addedAt') {
            state.filters.sortField = 'lastInteraction'
          }
          browserCacheStorage.setItem(SORT_DEFAULT_MIGRATION_KEY, '1')
        }
        state.filteredTitles = applyFiltersToTitles(state.titles, state.filters)
        state.stats = computeLedgerStats(state.titles)
        // Migrate ledger layout prefs. Older payloads used per-panel-type
        // order/hidden/widths/heights; the board is now a list of widget
        // instances. Hidden panels become "not on the board".
        const rawPrefs = (state.ledgerPrefs ?? {}) as LedgerPrefs & LegacyLedgerPrefs
        if (!Array.isArray(rawPrefs.widgets)) {
          if (Array.isArray(rawPrefs.order)) {
            const widths = { ...DEFAULT_LEDGER_PANEL_WIDTHS, ...(rawPrefs.widths ?? {}) }
            const hidden = rawPrefs.hidden ?? []
            const known = rawPrefs.order.filter((id) => id in LEDGER_PANEL_LABELS)
            const order = [...known, ...DEFAULT_LEDGER_PANEL_ORDER.filter((id) => !known.includes(id))]
            state.ledgerPrefs = {
              widgets: order
                .filter((panel) => !hidden.includes(panel))
                .map((panel) => createLedgerWidget(panel, widths[panel])),
            }
          } else {
            state.ledgerPrefs = { widgets: defaultLedgerWidgets() }
          }
        } else {
          // Sanitize widget instances: drop unknown panel types, backfill
          // widths, keep only well-typed settings keys, strip stray fields
          // (e.g. per-widget heights from before heights were standardized).
          state.ledgerPrefs = {
            widgets: normalizeLedgerWidgets(rawPrefs.widgets) ?? defaultLedgerWidgets(),
          }
        }
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
  return useMemo(() => [...new Set(titles.map((t) => `${decadeOf(t.year)}s`))].sort(), [titles])
}

export const useVisibleNavItems = () => {
  const navPrefs = useAppStore((s) => s.navPrefs)
  return useMemo(
    () => navPrefs.order.filter((id) => !navPrefs.hidden.includes(id)),
    [navPrefs],
  )
}

export const useAllLanguages = () => {
  const titles = useAppStore((s) => s.titles)
  return useMemo(
    () => [...new Set(titles.map((t) => t.originalLanguage).filter(Boolean) as string[])].sort(),
    [titles],
  )
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
  const outings = useAppStore((s) => s.outings)
  return useMemo(() => {
    const today = new Date().toISOString().slice(0, 10)
    return computeUpcomingTitles(titles, today, outings)
  }, [titles, outings])
}
