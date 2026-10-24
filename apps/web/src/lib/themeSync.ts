import { fetchThemePrefs, saveThemePrefs } from './db'
import { isDevMockUser } from './devAuth'
import { isSupabaseConfigured } from './auth'
import { applyTheme } from './theme'
import { getSystemTheme, isLightTheme, isThemeAvailable, useAppStore, type Theme, type ThemeMode } from 'src/store/useAppStore'

// Opt-in appearance sync across a user's browsers and devices. The payload,
// the cross-client vocabulary and the apply rules are the contract in
// docs/android-contracts/theme-sync.md (cases pinned in fixtures/theme-sync.json).

export const SYNCED_PALETTES = [
  'brand', 'material-you', 'noir', 'matrix',
  'catppuccin-latte', 'catppuccin-frappe', 'catppuccin-macchiato', 'catppuccin-mocha',
] as const
export type SyncedPalette = (typeof SYNCED_PALETTES)[number]

export interface ThemePrefs {
  palette: SyncedPalette
  mode: ThemeMode
  pureBlack: boolean
  updatedAt: string
}

export interface WebAppearance {
  theme: Theme
  themeMode: ThemeMode
  pureBlack: boolean
}

export function encodeWebAppearance(a: WebAppearance): Omit<ThemePrefs, 'updatedAt'> {
  const palette: SyncedPalette = a.theme === 'dark' || a.theme === 'light' ? 'brand' : a.theme
  return { palette, mode: a.themeMode, pureBlack: a.pureBlack }
}

/** Validates a stored payload; anything malformed or from a newer client's
 *  vocabulary is rejected whole (null) rather than half-applied. */
export function parseThemePrefs(value: unknown): ThemePrefs | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const v = value as Record<string, unknown>
  if (typeof v.palette !== 'string' || !(SYNCED_PALETTES as readonly string[]).includes(v.palette)) return null
  if (v.mode !== 'system' && v.mode !== 'light' && v.mode !== 'dark') return null
  if (typeof v.pureBlack !== 'boolean') return null
  if (typeof v.updatedAt !== 'string' || Number.isNaN(Date.parse(v.updatedAt))) return null
  return { palette: v.palette as SyncedPalette, mode: v.mode, pureBlack: v.pureBlack, updatedAt: v.updatedAt }
}

/** The web appearance a remote payload resolves to on this device. A palette
 *  the web lacks (material-you) or hasn't unlocked keeps the current theme. */
export function applyThemePrefsToWeb(
  prefs: ThemePrefs,
  current: { theme: Theme; themeMode: ThemeMode; unlockedThemes: readonly Theme[] },
  systemTheme: Theme,
): WebAppearance {
  const keep = { theme: current.theme, themeMode: current.themeMode, pureBlack: prefs.pureBlack }
  if (prefs.palette === 'material-you') return keep
  if (prefs.palette === 'brand') {
    return {
      theme: prefs.mode === 'system' ? systemTheme : prefs.mode,
      themeMode: prefs.mode,
      pureBlack: prefs.pureBlack,
    }
  }
  const theme: Theme = prefs.palette
  if (!isThemeAvailable(theme, current.unlockedThemes)) return keep
  return { theme, themeMode: isLightTheme(theme) ? 'light' : 'dark', pureBlack: prefs.pureBlack }
}

// ─── Runtime ──────────────────────────────────────────────────────────────────

// The stamp this device last pushed or applied, so applying a remote payload
// (which copies its stamp into the store) never echoes straight back.
let lastSyncedAt: string | null = null
let started = false

function syncUserId(): string | null {
  const { user, themeSyncEnabled } = useAppStore.getState()
  if (!themeSyncEnabled || !isSupabaseConfigured || !user || isDevMockUser(user)) return null
  return user.id
}

async function push(userId: string): Promise<void> {
  const s = useAppStore.getState()
  const updatedAt = s.themeUpdatedAt ?? new Date().toISOString()
  // Before the setState below, so the subscriber sees this stamp as already synced.
  lastSyncedAt = updatedAt
  // Never changed on this device: stamp the current appearance so it can be compared.
  if (!s.themeUpdatedAt) useAppStore.setState({ themeUpdatedAt: updatedAt })
  const prefs: ThemePrefs = { ...encodeWebAppearance(s), updatedAt }
  try {
    await saveThemePrefs(userId, prefs)
  } catch {
    // Best effort: the next reconcile pushes again while local stays newer.
  }
}

/** Pull-then-decide (theme-sync.md → Reconcile). Safe to call any time; a
 *  failed fetch or an invalid payload leaves both sides untouched. */
export async function reconcileThemeSync(): Promise<void> {
  const userId = syncUserId()
  if (!userId) return
  let raw: unknown
  try {
    raw = await fetchThemePrefs(userId)
  } catch {
    return
  }
  // The account or the opt-in may have changed while the fetch was in flight.
  if (syncUserId() !== userId) return
  const remote = raw == null ? null : parseThemePrefs(raw)
  if (raw != null && !remote) return
  const s = useAppStore.getState()
  const local = s.themeUpdatedAt ? Date.parse(s.themeUpdatedAt) : null
  if (remote && (local === null || Date.parse(remote.updatedAt) > local)) {
    const next = applyThemePrefsToWeb(remote, s, getSystemTheme())
    lastSyncedAt = remote.updatedAt
    useAppStore.setState({ ...next, themeUpdatedAt: remote.updatedAt })
    applyTheme(next.theme)
  } else if (!remote || (local !== null && local > Date.parse(remote.updatedAt))) {
    await push(userId)
  }
}

/** Starts pushing user-made appearance changes (anything that moves the
 *  store's themeUpdatedAt stamp) and reconciling on sign-in and on opting in.
 *  Idempotent; call once on app mount. */
export function startThemeSync(): void {
  if (started) return
  started = true
  useAppStore.subscribe((s, prev) => {
    if (s.user?.id !== prev.user?.id) {
      lastSyncedAt = null
      void reconcileThemeSync()
      return
    }
    if (s.themeSyncEnabled && !prev.themeSyncEnabled) {
      void reconcileThemeSync()
      return
    }
    if (s.themeUpdatedAt !== prev.themeUpdatedAt && s.themeUpdatedAt && s.themeUpdatedAt !== lastSyncedAt) {
      const userId = syncUserId()
      if (userId) void push(userId)
    }
  })
  void reconcileThemeSync()
}
