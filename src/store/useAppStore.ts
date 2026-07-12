import { useMemo } from 'react'
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import { mockTitles, type Title, type Viewing, type CinemaOuting, type LedgerStats, type WatchStatus, type MediaType } from './mockData'
import { computeLedgerStats } from './ledgerStats'
import { nextUnwatchedEpisode } from './episodeUtils'
import { computeUpNextShows, computeUpcomingTitles, type UpNextEntry, type UpcomingEntry } from './upNext'
import { localDateStr, type OutingSchedulePrefill, type OutingSharePayload } from './outings'
import type { User } from '@supabase/supabase-js'
import { isDevMockUser } from '../lib/devAuth'
import type { AppView, NavItemId } from '../lib/navigation'
import { DEFAULT_NAV_ORDER } from '../lib/navigation'
import type { LedgerPanelId, LedgerPanelWidth, LedgerWidget, LedgerWidgetSettings } from '../lib/ledgerPanels'
import {
  DEFAULT_LEDGER_PANEL_ORDER,
  DEFAULT_LEDGER_PANEL_WIDTHS,
  LEDGER_PANEL_LABELS,
  createLedgerWidget,
  defaultLedgerWidgets,
  newLedgerWidgetId,
  normalizeLedgerWidgets,
} from '../lib/ledgerPanels'
import { decadeOf } from '../lib/utils'
import {
  fetchUserLibrary, fetchSharedLibrary, fetchFriendLibrary, insertTitleToDb, updateTitleInDb,
  deleteTitleFromDb, logEpisodeToDb, deleteViewingFromDb,
  deleteEpisodeWatchEventFromDb, insertPrePlatformWatchEventsToDb,
  fetchAllTitlePins, upsertTitlePin, deleteTitlePin,
  fetchLedgerLayout, saveLedgerLayout,
  fetchNotifications, fetchUnreadNotificationCount, markNotificationRead, markAllNotificationsRead,
  deleteNotification,
  insertOutingToDb, updateOutingInDb, completeDueOutings,
  shareOutingPlans as shareOutingPlansRpc,
  type AppNotificationItem, type OutingCompletionResult,
} from '../lib/db'
import type { SearchResult } from '../lib/media'

// ─── Filter & Sort Types ────────────────────────────────────────────────────

export type SortField = 'title' | 'year' | 'rating' | 'addedAt' | 'director' | 'lastInteraction'
export type SortDir = 'asc' | 'desc'
export type ViewMode = 'grid' | 'list'
export type Theme = 'dark' | 'light' | 'noir' | 'matrix'

/** Default theme for users with no persisted choice yet — matches the OS
 *  preference instead of hard-coding dark. Keep in sync with the inline FOUC
 *  script in index.html. */
function getSystemTheme(): Theme {
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
  // The board arrangement of whoever is being viewed in a shared/friend view.
  // Transient (never persisted) — it must not clobber the viewer's own
  // ledgerPrefs, which partialize writes to localStorage.
  viewedLedgerWidgets: LedgerWidget[] | null
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
  addLedgerWidget: (panel: LedgerPanelId) => string
  duplicateLedgerWidget: (id: string) => string | null
  removeLedgerWidget: (id: string) => void
  moveLedgerWidget: (id: string, direction: 'up' | 'down') => void
  reorderLedgerWidgets: (ids: string[]) => void
  setLedgerWidgetWidth: (id: string, width: LedgerPanelWidth) => void
  // Merge-patch a widget's settings; keys set to undefined are removed, and a
  // settings object with no remaining keys is dropped entirely.
  setLedgerWidgetSettings: (id: string, patch: Partial<LedgerWidgetSettings>) => void
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
  user: User | null
  loadingUser: boolean
  // Set when the last library load failed; cleared when a load starts or
  // succeeds. Views surface it instead of a misleading empty state.
  libraryLoadError: string | null
  viewerContext: ViewerContext
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

// Cinema Outings ("I've got tickets") — see
// docs/superpowers/plans/2026-07-11-cinema-outings.md §7.2. Owner-only:
// loaded with the library, never fetched for shared/friend viewers.
interface OutingsSlice {
  outings: CinemaOuting[]
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
  addOuting: (outing: CinemaOuting) => void
  // Edit/reschedule — recomputes endsAt from the merged showtime/previews/runtime.
  updateOuting: (id: string, patch: Partial<CinemaOuting>) => void
  // Soft-cancel (plan §4.2): kept as a history row, hidden from all surfaces.
  cancelOuting: (id: string) => void
  // Stamps follow_up_dismissed_at — called both on an explicit ✕ and after rating.
  dismissOutingFollowUp: (id: string) => void
  shareOutingPlans: (outingId: string, recipientIds: string[]) => Promise<void>
  // "I've got tickets too" resolution (plan §4.10/§5.16) — if the shared
  // payload's tmdb_id isn't already in the library, adds it to the watchlist
  // first (same match-by-tmdbId+type resolution the recommendation inbox
  // uses), then returns the titleId either way for the prefilled sheet.
  resolveSharedOutingTitle: (payload: OutingSharePayload) => string
  // "Didn't make it" (plan §5.6): deletes the auto-logged viewing, reverts the
  // title status iff it's still 'watched', flips the outing to 'missed', and
  // drops the now-stale outing_completed inbox item.
  revertOutingCompletion: (outingId: string) => void
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

function timeOf(dateStr: string | undefined): number {
  return dateStr ? new Date(dateStr).getTime() : -Infinity
}

/** Most recent user interaction with a title: added, (re)watched, or — for
 *  TV — any per-episode watch/rating/review event. Deliberately excludes
 *  `titles.updated_at` (bumped by bulk metadata refresh on every title, which
 *  would collapse this into "everything touched just now") and sharing
 *  (no per-title timestamp exists for that in the data model). */
export function titleLastInteractionAt(title: Title): number {
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
  return latest
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
      const decade = `${decadeOf(t.year)}s`
      return filters.decades.includes(decade)
    })
  }

  if (filters.languages.length > 0) {
    result = result.filter((t) => t.originalLanguage && filters.languages.includes(t.originalLanguage))
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
        comparison = new Date(a.addedAt).getTime() - new Date(b.addedAt).getTime()
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

// Fire-and-forget a DB write: log the failure and, if a user-facing message is
// given, surface a retry-able notification. `retry` reruns the same call
// raw (NotificationStack already wraps it in its own try/catch), so it must
// not recurse back into this wrapper.
function syncToDb(get: () => AppStore, logMessage: string, dbCall: () => Promise<unknown>, failureMessage?: string) {
  dbCall().catch((err) => {
    console.error(logMessage, err)
    if (failureMessage) get().pushNotification({ message: failureMessage, retry: async () => { await dbCall() } })
  })
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

type AppStore = LibrarySlice & LedgerSlice & UISlice & AuthSlice & PinsSlice & OutingsSlice

// Bump when the persisted shape changes incompatibly; older payloads are dropped.
const PERSIST_VERSION = 2

// Guards the one-time default-sort migration below (separate localStorage key
// so it fires exactly once, independent of PERSIST_VERSION).
const SORT_DEFAULT_MIGRATION_KEY = 'cinemarchive-sort-default-migrated'

// ─── Ledger layout write-behind ─────────────────────────────────────────────
// Layout edits are rapid (drags fire many width/order updates), so the synced
// copy is written on a debounce rather than per-action. localStorage persist
// still captures every change immediately (anon/offline fallback).

const LEDGER_SAVE_DEBOUNCE_MS = 800

let ledgerSaveTimer: number | undefined
let ledgerSaveGet: (() => AppStore) | null = null

// Polls the unread notification count while a user is logged in — the inbox
// has no Supabase Realtime subscription, so this is what keeps the bell
// current for a friend's comment/reaction/request arriving mid-session.
const NOTIFICATION_POLL_MS = 45_000
let notificationPollTimer: number | undefined

function flushLedgerLayoutSave() {
  window.clearTimeout(ledgerSaveTimer)
  ledgerSaveTimer = undefined
  const get = ledgerSaveGet
  if (!get) return
  const s = get()
  // Viewers must never write the owner's board into their own prefs.
  if (!s.user || s.isSharedView || s.viewerContext.kind === 'friend') return
  const user = s.user
  saveLedgerLayout(user.id, s.ledgerPrefs.widgets).catch(() => {
    s.pushNotification({
      message: "Couldn't sync your Ledger layout — it's saved on this device.",
      retry: () => saveLedgerLayout(user.id, get().ledgerPrefs.widgets),
    })
  })
}

function scheduleLedgerLayoutSave(get: () => AppStore) {
  ledgerSaveGet = get
  window.clearTimeout(ledgerSaveTimer)
  ledgerSaveTimer = window.setTimeout(flushLedgerLayoutSave, LEDGER_SAVE_DEBOUNCE_MS)
}

// A drag session followed by closing the tab shouldn't lose the layout.
if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden' && ledgerSaveTimer !== undefined) flushLedgerLayoutSave()
  })
}

export const useAppStore = create<AppStore>()(
  persist(
    (set, get) => ({
  // ── Library ────────────────────────────────────────────────
  titles: import.meta.env.DEV ? mockTitles : [],
  filters: defaultFilters,
  filteredTitles: applyFiltersToTitles(import.meta.env.DEV ? mockTitles : [], defaultFilters),

  setTitles: (titles) =>
    set((s) => withDerivedTitles(titles, s.filters)),

  addTitle: (title) =>
    set((s) => {
      const titles = [title, ...s.titles]
      if (s.user) {
        const userId = s.user.id
        syncToDb(get, 'Failed to sync added title to DB:', () => insertTitleToDb(userId, title),
          `Couldn't save "${title.title}" — check your connection.`)
      }
      return withDerivedTitles(titles, s.filters)
    }),

  updateTitle: (id, patch) =>
    set((s) => {
      const titles = s.titles.map((t) => (t.id === id ? { ...t, ...patch } : t))
      if (s.user) {
        const userId = s.user.id
        syncToDb(get, 'Failed to sync updated title to DB:', () => updateTitleInDb(userId, id, patch),
          'Couldn\'t save changes — check your connection.')
      }
      return withDerivedTitles(titles, s.filters)
    }),

  removeTitle: (id) =>
    set((s) => {
      const titles = s.titles.filter((t) => t.id !== id)
      if (s.user) {
        const userId = s.user.id
        syncToDb(get, 'Failed to sync deleted title from DB:', () => deleteTitleFromDb(userId, id),
          'Couldn\'t remove title — check your connection.')
      }
      return withDerivedTitles(titles, s.filters)
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
          syncToDb(get, 'Failed to sync episode log to DB:', () => logEpisodeToDb(userId, episodeId, dbOpts),
            'Couldn\'t save watch event — check your connection.')
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
      return withDerivedTitles(titles, s.filters)
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
      syncToDb(get, 'Failed to sync quick episode log to DB:', () => logEpisodeToDb(userId, episodeId, dbOpts),
        'Couldn\'t save watch event — check your connection.')
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
      return withDerivedTitles(titles, s.filters)
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
        syncToDb(get, 'Failed to sync pre-platform watch events to DB:', () => insertPrePlatformWatchEventsToDb(userId, newEvents),
          'Couldn\'t save watch events — check your connection.')
      }
      if (s.user && seasonNumber === undefined) {
        const userId = s.user.id
        syncToDb(get, 'Failed to sync watched status to DB:', () => updateTitleInDb(userId, titleId, { status: 'watched' }))
      }

      return withDerivedTitles(titles, s.filters)
    }),

  removeViewing: (titleId, viewingId) =>
    set((s) => {
      const titles = s.titles.map((t) => {
        if (t.id !== titleId) return t
        return { ...t, viewings: t.viewings.filter((v) => v.id !== viewingId) }
      })
      // Rule §5.8: deleting the auto-logged viewing directly from the
      // timeline leaves the outing 'completed' (it's history, not a claim
      // about the library) but ends any pending follow-up — the post-show
      // card/sheet requires the viewing to exist. Stamping
      // followUpDismissedAt (same field the ✕/rating dismissal path uses)
      // is what isFollowUpPending already keys off, so no other surface
      // needs to know the viewing is gone.
      const staleOuting = s.outings.find((o) => o.completedViewingId === viewingId)
      const followUpDismissedAt = new Date().toISOString()
      const outings = staleOuting
        ? s.outings.map((o) =>
            o.id === staleOuting.id ? { ...o, completedViewingId: undefined, followUpDismissedAt } : o
          )
        : s.outings
      if (s.user) {
        const userId = s.user.id
        syncToDb(get, 'Failed to sync deleted viewing to DB:', () => deleteViewingFromDb(userId, viewingId),
          'Couldn\'t remove viewing — check your connection.')
        if (staleOuting) {
          syncToDb(get, 'Failed to sync stale outing follow-up to DB:', () =>
            updateOutingInDb(userId, staleOuting.id, { completedViewingId: undefined, followUpDismissedAt }))
        }
      }
      return { ...withDerivedTitles(titles, s.filters), outings }
    }),

  deleteEpisodeWatchEvent: (titleId, seasonNumber, episodeNumber, watchEventId) =>
    set((s) => {
      if (s.user) {
        const userId = s.user.id
        syncToDb(get, 'Failed to sync deleted episode watch event to DB:', () => deleteEpisodeWatchEventFromDb(userId, watchEventId),
          'Couldn\'t remove watch event — check your connection.')
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
      return withDerivedTitles(titles, s.filters)
    }),

  // ── Ledger ─────────────────────────────────────────────────
  stats: computeLedgerStats(import.meta.env.DEV ? mockTitles : []),

  setStats: (stats) => set({ stats }),

  // ── UI ─────────────────────────────────────────────────────
  viewMode: 'grid',
  theme: getSystemTheme(),
  unlockedThemes: ['dark', 'light'],
  navPrefs: defaultNavPrefs,
  ledgerPrefs: defaultLedgerPrefs,
  viewedLedgerWidgets: null,
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

  addLedgerWidget: (panel) => {
    const widget = createLedgerWidget(panel)
    set((s) => ({ ledgerPrefs: { widgets: [...s.ledgerPrefs.widgets, widget] } }))
    scheduleLedgerLayoutSave(get)
    return widget.id
  },

  duplicateLedgerWidget: (id) => {
    const source = get().ledgerPrefs.widgets.find((w) => w.id === id)
    if (!source) return null
    const copy: LedgerWidget = { ...source, id: newLedgerWidgetId() }
    set((s) => {
      const widgets = [...s.ledgerPrefs.widgets]
      const idx = widgets.findIndex((w) => w.id === id)
      widgets.splice(idx + 1, 0, copy)
      return { ledgerPrefs: { widgets } }
    })
    scheduleLedgerLayoutSave(get)
    return copy.id
  },

  removeLedgerWidget: (id) => {
    set((s) => ({ ledgerPrefs: { widgets: s.ledgerPrefs.widgets.filter((w) => w.id !== id) } }))
    scheduleLedgerLayoutSave(get)
  },

  moveLedgerWidget: (id, direction) => {
    set((s) => {
      const widgets = swapAdjacent(s.ledgerPrefs.widgets, s.ledgerPrefs.widgets.findIndex((w) => w.id === id), direction)
      return widgets ? { ledgerPrefs: { widgets } } : {}
    })
    scheduleLedgerLayoutSave(get)
  },

  reorderLedgerWidgets: (ids) => {
    set((s) => {
      const byId = new Map(s.ledgerPrefs.widgets.map((w) => [w.id, w]))
      const widgets = ids.map((id) => byId.get(id)).filter((w): w is LedgerWidget => Boolean(w))
      // Anything omitted from `ids` (shouldn't happen) is kept rather than dropped.
      for (const w of s.ledgerPrefs.widgets) if (!ids.includes(w.id)) widgets.push(w)
      return { ledgerPrefs: { widgets } }
    })
    scheduleLedgerLayoutSave(get)
  },

  setLedgerWidgetWidth: (id, width) => {
    set((s) => ({
      ledgerPrefs: {
        widgets: s.ledgerPrefs.widgets.map((w) => (w.id === id ? { ...w, width } : w)),
      },
    }))
    scheduleLedgerLayoutSave(get)
  },

  setLedgerWidgetSettings: (id, patch) => {
    set((s) => ({
      ledgerPrefs: {
        widgets: s.ledgerPrefs.widgets.map((w) => {
          if (w.id !== id) return w
          const settings: LedgerWidgetSettings = { ...w.settings, ...patch }
          for (const key of Object.keys(settings) as Array<keyof LedgerWidgetSettings>) {
            if (settings[key] === undefined) delete settings[key]
          }
          if (Object.keys(settings).length === 0) {
            const rest = { ...w }
            delete rest.settings
            return rest
          }
          return { ...w, settings }
        }),
      },
    }))
    scheduleLedgerLayoutSave(get)
  },

  resetLedgerPrefs: () => {
    set({ ledgerPrefs: { widgets: defaultLedgerWidgets() } })
    scheduleLedgerLayoutSave(get)
  },

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

  notificationInbox: [],
  unreadNotificationCount: 0,

  refreshUnreadNotificationCount: async () => {
    if (!get().user) return
    try {
      const count = await fetchUnreadNotificationCount()
      set({ unreadNotificationCount: count })
    } catch (err) {
      console.error('Failed to refresh unread notification count:', err)
    }
  },

  loadNotificationInbox: async (before) => {
    try {
      const page = await fetchNotifications(before)
      set((s) => ({ notificationInbox: before ? [...s.notificationInbox, ...page] : page }))
    } catch (err) {
      console.error('Failed to load notification inbox:', err)
    }
  },

  markOneNotificationRead: async (id) => {
    const prev = get().notificationInbox
    set((s) => ({
      notificationInbox: s.notificationInbox.map((n) => (n.id === id && !n.readAt ? { ...n, readAt: new Date().toISOString() } : n)),
      unreadNotificationCount: Math.max(0, s.unreadNotificationCount - (prev.find((n) => n.id === id)?.readAt ? 0 : 1)),
    }))
    try {
      await markNotificationRead(id)
    } catch (err) {
      console.error('Failed to mark notification read:', err)
      set({ notificationInbox: prev })
      void get().refreshUnreadNotificationCount()
    }
  },

  markAllNotificationsSeen: async () => {
    const prev = get().notificationInbox
    const now = new Date().toISOString()
    set((s) => ({
      notificationInbox: s.notificationInbox.map((n) => (n.readAt ? n : { ...n, readAt: now })),
      unreadNotificationCount: 0,
    }))
    try {
      await markAllNotificationsRead()
    } catch (err) {
      console.error('Failed to mark all notifications read:', err)
      set({ notificationInbox: prev })
      void get().refreshUnreadNotificationCount()
    }
  },

  deleteNotificationItem: async (id) => {
    const prev = get().notificationInbox
    const removed = prev.find((n) => n.id === id)
    set((s) => ({
      notificationInbox: s.notificationInbox.filter((n) => n.id !== id),
      unreadNotificationCount: removed && !removed.readAt ? Math.max(0, s.unreadNotificationCount - 1) : s.unreadNotificationCount,
    }))
    try {
      await deleteNotification(id)
    } catch (err) {
      console.error('Failed to delete notification:', err)
      set({ notificationInbox: prev })
      void get().refreshUnreadNotificationCount()
    }
  },

  // ── Auth ───────────────────────────────────────────────────
  user: null,
  loadingUser: false,
  libraryLoadError: null,
  viewerContext: { kind: 'owner' },

  setUser: (user) => {
    set({ user })
    window.clearInterval(notificationPollTimer)
    notificationPollTimer = undefined
    if (user && isDevMockUser(user)) {
      // Dev-only mock session — no real Supabase auth backs this id, so skip
      // the DB-backed loads below and keep whatever's already on screen
      // (mockTitles in dev) rather than wiping it with an unauthenticated fetch.
      return
    }
    if (user) {
      get().loadUserLibrary()
      get().loadPinnedModes()
      get().refreshUnreadNotificationCount()
      notificationPollTimer = window.setInterval(() => get().refreshUnreadNotificationCount(), NOTIFICATION_POLL_MS)
    } else {
      // Clear on logout — restore mock data only in dev
      const fallback = import.meta.env.DEV ? mockTitles : []
      set((s) => ({ ...withDerivedTitles(fallback, s.filters), pinnedModes: {}, outings: [] }))
    }
  },

  setLoadingUser: (loadingUser) => set({ loadingUser }),

  loadUserLibrary: async () => {
    const user = get().user
    if (!user) return
    set({ loadingUser: true, libraryLoadError: null })
    try {
      // Outings ride along with the owner's own library fetch (rule §9 —
      // owner-private; never fetched for shared/friend views).
      const { titles: dbTitles, outings: dbOutings } = await fetchUserLibrary(user.id)
      // The synced board layout rides along with the library fetch. Server
      // wins; a user who has never synced adopts their local board once.
      void fetchLedgerLayout(user.id)
        .then((widgets) => {
          if (get().isSharedView || get().viewerContext.kind === 'friend') return
          if (widgets) set({ ledgerPrefs: { widgets } })
          else void saveLedgerLayout(user.id, get().ledgerPrefs.widgets).catch(() => {})
        })
        .catch((err) => console.error('Failed to load synced Ledger layout:', err))
      // Guard: if we have local titles but DB returned empty, the session auth
      // may not have fully propagated — skip the wipe rather than hiding data.
      const currentTitles = get().titles
      const hasRealLocalData = currentTitles.some((t) => !t.id.startsWith('mt-'))
      if (dbTitles.length === 0 && hasRealLocalData) {
        console.warn('loadUserLibrary: DB returned 0 titles but local store has user data — skipping replace. Check auth session.')
        return
      }
      set((s) => ({ ...withDerivedTitles(dbTitles, s.filters), outings: dbOutings }))
      // Reconciliation trigger: app load, right after the library lands
      // (plan §4.3) — completes anything that finished while the app was closed.
      void get().reconcileOutings()
    } catch (err) {
      console.error('Failed to load user library from DB:', err)
      set({ libraryLoadError: "Couldn't load your library — check your connection." })
      get().pushNotification({
        message: "Couldn't load your library — check your connection.",
        retry: () => get().loadUserLibrary(),
      })
    } finally {
      set({ loadingUser: false })
    }
  },

  loadSharedLibrary: async (token) => {
    set({ loadingUser: true, isSharedView: true, viewerContext: { kind: 'shared-link', token }, libraryLoadError: null })
    try {
      const { titles: dbTitles, ownerUserId } = await fetchSharedLibrary(token)
      set((s) => withDerivedTitles(dbTitles, s.filters))
      // Show the owner's board arrangement (falls back to the default board
      // when they never synced one). Never written into the viewer's prefs.
      if (ownerUserId) {
        void fetchLedgerLayout(ownerUserId)
          .then((widgets) => set({ viewedLedgerWidgets: widgets }))
          .catch(() => set({ viewedLedgerWidgets: null }))
      }
    } catch (err) {
      console.error('Failed to load shared library from DB:', err)
      set({ libraryLoadError: "Couldn't load this shared library — the link may have expired." })
    } finally {
      set({ loadingUser: false })
    }
  },

  // Reuses isSharedView for the existing read-only gating throughout the app
  // (TitleDetailDrawer, episode-card, Discover, etc.) — viewerContext just adds
  // who's being viewed, for the exit affordance and heading text.
  loadFriendLibrary: async (friendUserId, displayName) => {
    set({
      loadingUser: true,
      isSharedView: true,
      libraryLoadError: null,
      viewerContext: { kind: 'friend', userId: friendUserId, displayName },
      pendingView: 'library',
    })
    try {
      const dbTitles = await fetchFriendLibrary(friendUserId)
      set((s) => withDerivedTitles(dbTitles, s.filters))
      // Show the friend's board arrangement (read-only RLS policy).
      void fetchLedgerLayout(friendUserId)
        .then((widgets) => set({ viewedLedgerWidgets: widgets }))
        .catch(() => set({ viewedLedgerWidgets: null }))
    } catch (err) {
      console.error('Failed to load friend library from DB:', err)
      set({ libraryLoadError: "Couldn't load that friend's library — check your connection." })
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
      viewerContext: { kind: 'owner' },
      isSharedView: false,
      viewedLedgerWidgets: null,
      ...withDerivedTitles([], s.filters),
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

  addOuting: (outing) =>
    set((s) => {
      const outings = [outing, ...s.outings]
      if (s.user) {
        const userId = s.user.id
        syncToDb(get, 'Failed to sync added outing to DB:', () => insertOutingToDb(userId, outing),
          "Couldn't save your tickets — check your connection.")
      }
      return { outings }
    }),

  updateOuting: (id, patch) =>
    set((s) => {
      const outings = s.outings.map((o) => {
        if (o.id !== id) return o
        const merged = { ...o, ...patch }
        // ends_at is a plain column, not generated (timestamptz + interval
        // isn't immutable) — the client keeps it in sync on every edit,
        // mirroring what the schema comment on cinema_outings.ends_at says
        // the RPC/client contract is (plan §6.1).
        const endsAt = new Date(
          new Date(merged.showtime).getTime() + (merged.previewsMinutes + merged.runtimeMinutes) * 60_000
        ).toISOString()
        return { ...merged, endsAt }
      })
      if (s.user) {
        const userId = s.user.id
        const updated = outings.find((o) => o.id === id)
        const dbPatch = updated ? { ...patch, endsAt: updated.endsAt } : patch
        syncToDb(get, 'Failed to sync updated outing to DB:', () => updateOutingInDb(userId, id, dbPatch),
          "Couldn't save ticket changes — check your connection.")
      }
      return { outings }
    }),

  cancelOuting: (id) =>
    set((s) => {
      const outings = s.outings.map((o) => (o.id === id ? { ...o, status: 'cancelled' as const } : o))
      if (s.user) {
        const userId = s.user.id
        syncToDb(get, 'Failed to sync cancelled outing to DB:', () => updateOutingInDb(userId, id, { status: 'cancelled' }),
          "Couldn't cancel — check your connection.")
      }
      return { outings }
    }),

  dismissOutingFollowUp: (id) =>
    set((s) => {
      const followUpDismissedAt = new Date().toISOString()
      const outings = s.outings.map((o) => (o.id === id ? { ...o, followUpDismissedAt } : o))
      if (s.user) {
        const userId = s.user.id
        syncToDb(get, 'Failed to sync dismissed outing follow-up to DB:', () =>
          updateOutingInDb(userId, id, { followUpDismissedAt }))
      }
      return { outings }
    }),

  shareOutingPlans: async (outingId, recipientIds) => {
    try {
      await shareOutingPlansRpc(outingId, recipientIds)
    } catch (err) {
      console.error('Failed to share outing plans:', err)
      get().pushNotification({ message: "Couldn't share your plans — check your connection." })
      throw err
    }
  },

  resolveSharedOutingTitle: (payload) => {
    const s = get()
    const existing = s.titles.find((t) => t.tmdbId === payload.tmdbId && t.type === payload.type)
    if (existing) return existing.id

    // Rule §5.16: abandoning the schedule form afterward still leaves the
    // title on the watchlist — harmless, since tapping this CTA already
    // means they intend to see it.
    const id = crypto.randomUUID()
    s.addTitle({
      id,
      tmdbId: payload.tmdbId,
      type: payload.type,
      title: payload.title,
      year: payload.year ?? 0,
      posterUrl: payload.posterUrl,
      genres: [],
      status: 'watchlist',
      tags: [],
      addedAt: new Date().toISOString(),
      viewings: [],
    })
    return id
  },

  revertOutingCompletion: (outingId) => {
    const s0 = get()
    const outing = s0.outings.find((o) => o.id === outingId)
    if (!outing || outing.status !== 'completed') return

    const viewingId = outing.completedViewingId
    const title = s0.titles.find((t) => t.id === outing.titleId)
    // Rule §5.6: only revert the title's status if it's still 'watched' — if
    // the user changed it manually in the meantime, their choice wins.
    const revertStatus = title?.status === 'watched' ? outing.previousStatus : undefined

    set((s) => {
      const titles = s.titles.map((t) => {
        if (t.id !== outing.titleId) return t
        const next = { ...t }
        if (viewingId) next.viewings = t.viewings.filter((v) => v.id !== viewingId)
        if (revertStatus) next.status = revertStatus
        return next
      })
      const outings = s.outings.map((o) =>
        o.id === outingId ? { ...o, status: 'missed' as const, completedViewingId: undefined } : o
      )
      return { ...withDerivedTitles(titles, s.filters), outings }
    })

    if (s0.user) {
      const userId = s0.user.id
      if (viewingId) {
        syncToDb(get, 'Failed to sync reverted viewing deletion to DB:', () => deleteViewingFromDb(userId, viewingId),
          "Couldn't undo that viewing — check your connection.")
      }
      syncToDb(get, 'Failed to sync reverted outing to DB:', () =>
        updateOutingInDb(userId, outingId, { status: 'missed', completedViewingId: undefined }))
      if (revertStatus) {
        syncToDb(get, 'Failed to sync reverted title status to DB:', () =>
          updateTitleInDb(userId, outing.titleId, { status: revertStatus }))
      }
    }

    // Best-effort: drop the now-stale "how was it?" inbox item (rule §5.6).
    // The notification carries titleId but not outingId (see the RPC in the
    // Phase A migration), so this matches the most recent unread
    // outing_completed item for the title among whatever's already loaded
    // locally — a session that never opened the bell has nothing cached here
    // to clean up, and simply leaves the stale item to be read/dismissed later.
    const stale = s0.notificationInbox
      .filter((n) => n.type === 'outing_completed' && n.titleId === outing.titleId)
      .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))[0]
    if (stale) void get().deleteNotificationItem(stale.id)
  },

  reconcileOutings: async () => {
    const user = get().user
    if (!user) return

    let results: OutingCompletionResult[]
    try {
      const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
      results = await completeDueOutings(tz)
    } catch (err) {
      console.error('Failed to reconcile cinema outings:', err)
      return
    }
    if (results.length === 0) return

    set((s) => {
      let titles = s.titles
      let outings = s.outings

      for (const r of results) {
        const outing = outings.find((o) => o.id === r.outingId)
        const title = titles.find((t) => t.id === r.titleId)
        // Multi-device race (§5.11): another session already applied this
        // transition and our local copy doesn't know about the outing/title —
        // nothing to reconcile locally; the next full load will catch up.
        if (!outing || !title) continue

        const viewing: Viewing = {
          id: r.viewingId,
          titleId: r.titleId,
          // Same calendar date the RPC derived server-side from this same
          // client's IANA zone — see localDateStr's doc comment.
          date: localDateStr(new Date(outing.showtime)),
          venue: outing.venue,
          companions: outing.companions.length > 0 ? outing.companions : undefined,
          outingId: r.outingId,
        }

        titles = titles.map((t) =>
          t.id === r.titleId ? { ...t, status: r.newTitleStatus, viewings: [...t.viewings, viewing] } : t
        )
        outings = outings.map((o) =>
          o.id === r.outingId
            ? { ...o, status: 'completed' as const, previousStatus: r.previousStatus ?? undefined, completedViewingId: r.viewingId }
            : o
        )
      }

      return { ...withDerivedTitles(titles, s.filters), outings }
    })

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
      name: 'cinemarchive-library',
      version: PERSIST_VERSION,
      storage: createJSONStorage(() => localStorage),
      // Only the source of truth is persisted; derived state (filteredTitles,
      // stats) and transient UI flags are recomputed/reset on load. While
      // browsing a friend's library, `titles` holds THEIR data — never persist
      // that to the viewer's localStorage.
      partialize: (s) => ({
        titles: s.viewerContext.kind === 'friend' ? [] : s.titles,
        outings: s.viewerContext.kind === 'friend' ? [] : s.outings,
        filters: s.filters,
        viewMode: s.viewMode,
        theme: s.theme,
        unlockedThemes: s.unlockedThemes,
        navPrefs: s.navPrefs,
        ledgerPrefs: s.ledgerPrefs,
      }),
      onRehydrateStorage: () => (state) => {
        if (!state) return
        // Older persisted payloads may lack newer filter keys — backfill them.
        state.filters = { ...defaultFilters, ...state.filters }
        // One-time migration: the default sort used to be 'addedAt'. Flip
        // still-on-default users over to the new 'lastInteraction' default
        // without touching anyone who has since picked a different sort.
        if (typeof localStorage !== 'undefined' && !localStorage.getItem(SORT_DEFAULT_MIGRATION_KEY)) {
          if (state.filters.sortField === 'addedAt') {
            state.filters.sortField = 'lastInteraction'
          }
          localStorage.setItem(SORT_DEFAULT_MIGRATION_KEY, '1')
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
