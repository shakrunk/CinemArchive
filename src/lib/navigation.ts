// Query-param navigation state. Pure + framework-free so it can be unit-verified
// by scripts/verify-navigation-logic.mjs. The URL is the source of truth for the
// active view and which modal (detail drawer / add) is open.

export type AppView = 'upnext' | 'library' | 'ledger' | 'discover' | 'profile'

// The four destinations that appear in the customizable nav (TopBar pill nav +
// BottomNav). 'profile' is excluded — it's the account icon, not a nav tab.
export type NavItemId = 'discover' | 'library' | 'upnext' | 'ledger'

export const DEFAULT_NAV_ORDER: NavItemId[] = ['discover', 'library', 'upnext', 'ledger']

// Canonical labels for Settings → Navigation. TopBar/BottomNav keep their own
// (shorter, space-constrained) copy locally rather than importing this.
export const NAV_ITEM_LABELS: Record<NavItemId, string> = {
  discover: 'Discover',
  library: 'The Library',
  upnext: 'Up Next',
  ledger: 'The Ledger',
}

export interface NavState {
  view: AppView
  title: string | null
  add: boolean
}

const APP_VIEWS: AppView[] = ['upnext', 'library', 'ledger', 'discover', 'profile']

// Params that are not part of NavState but must survive every navigation write.
const PRESERVED_KEYS = ['share']

export function parseNav(search: string, fallbackView: AppView): NavState {
  const params = new URLSearchParams(search)
  const rawView = params.get('view')
  const view = APP_VIEWS.includes(rawView as AppView) ? (rawView as AppView) : fallbackView
  const title = params.get('title')
  return { view, title: title || null, add: params.get('add') === '1' }
}

export function preservedParams(search: string): Record<string, string> {
  const params = new URLSearchParams(search)
  const out: Record<string, string> = {}
  for (const key of PRESERVED_KEYS) {
    const v = params.get(key)
    if (v) out[key] = v
  }
  return out
}

export function serializeNav(nav: NavState, preserved: Record<string, string>): string {
  const params = new URLSearchParams()
  // Deterministic key order → stable strings → no spurious history writes.
  for (const key of Object.keys(preserved).sort()) params.set(key, preserved[key])
  params.set('view', nav.view)
  if (nav.title) params.set('title', nav.title)
  if (nav.add) params.set('add', '1')
  return `?${params.toString()}`
}
