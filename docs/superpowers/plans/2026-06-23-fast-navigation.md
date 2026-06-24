# Fast Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every destination addressable and instantly reachable — URL-backed views/drawer with a working mobile back button, plus a ⌘K command palette to jump to any title or action.

**Architecture:** Two composable parts. Part A adds a pure `navigation.ts` (parse/serialize query state) plus a `useNavigationSync` hook that bridges URL ⟷ App `currentView` + store drawer/add flags, classifying modal-opens as `pushState` (so back closes them) and everything else as `replaceState`. Part B adds a pure `commands.ts` ranking module and a Radix-Dialog-based `CommandPalette` opened by ⌘K, whose selections reuse existing store actions (and thus Part A's history behavior).

**Tech Stack:** React 19 + TypeScript, Zustand, `@radix-ui/react-dialog`, History API, Tailwind. No new dependencies. Pure logic verified by standalone `scripts/verify-*.mjs` Node scripts (repo convention — there is no test runner).

## Global Constraints

- No new npm dependencies; reuse `@radix-ui/react-dialog` (already present) for the palette.
- Pure logic lives in `src/lib` or `src/store` and is mirrored by a `scripts/verify-*.mjs` script that runs with `node` (use globals only — `URLSearchParams` is available in Node).
- Keep the Zustand store free of `window.history` calls — all history side-effects live in `useNavigationSync`.
- Preserve the existing `?share=<token>` param across every navigation write.
- Vite `base` is `/CinemArchive/`; use query params only (no path routing).
- Verification commands: `npm run build` (tsc -b && vite build) and `npm run lint` must pass; both verify scripts must exit 0.
- Design tokens/classes already in `index.css`: `.cinema-overlay`, `.cinema-content`, `.chip`, `.seg`, `.kicker`, `animate-view-in`, colors `--amber`, `--paper`, `--paper-faint`, `--line`, `--ink-1`.

---

### Task 1: Pure navigation module (`navigation.ts`) + verify script

**Files:**
- Create: `src/lib/navigation.ts`
- Create: `scripts/verify-navigation-logic.mjs`

**Interfaces:**
- Produces:
  - `type AppView = 'upnext' | 'library' | 'ledger'`
  - `interface NavState { view: AppView; title: string | null; add: boolean }`
  - `parseNav(search: string, fallbackView: AppView): NavState`
  - `serializeNav(nav: NavState, preserved: Record<string, string>): string`
  - `preservedParams(search: string): Record<string, string>`

- [ ] **Step 1: Write `src/lib/navigation.ts`**

```ts
// Query-param navigation state. Pure + framework-free so it can be unit-verified
// by scripts/verify-navigation-logic.mjs. The URL is the source of truth for the
// active view and which modal (detail drawer / add) is open.

export type AppView = 'upnext' | 'library' | 'ledger'

export interface NavState {
  view: AppView
  title: string | null
  add: boolean
}

const APP_VIEWS: AppView[] = ['upnext', 'library', 'ledger']

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
```

- [ ] **Step 2: Write `scripts/verify-navigation-logic.mjs`** (mirrors the logic above)

```js
/**
 * Runtime logic verification for query-param navigation (src/lib/navigation.ts).
 * Run with: node scripts/verify-navigation-logic.mjs
 * Mirrors parseNav / serializeNav / preservedParams (URLSearchParams is a Node global).
 */
let pass = 0, fail = 0
function assert(label, actual, expected) {
  if (actual === expected) { console.log(`  ✓ ${label}: ${JSON.stringify(actual)}`); pass++ }
  else { console.error(`  ✗ ${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`); fail++ }
}

const APP_VIEWS = ['upnext', 'library', 'ledger']
const PRESERVED_KEYS = ['share']
function parseNav(search, fallbackView) {
  const p = new URLSearchParams(search)
  const raw = p.get('view')
  const view = APP_VIEWS.includes(raw) ? raw : fallbackView
  const title = p.get('title')
  return { view, title: title || null, add: p.get('add') === '1' }
}
function preservedParams(search) {
  const p = new URLSearchParams(search)
  const out = {}
  for (const k of PRESERVED_KEYS) { const v = p.get(k); if (v) out[k] = v }
  return out
}
function serializeNav(nav, preserved) {
  const p = new URLSearchParams()
  for (const k of Object.keys(preserved).sort()) p.set(k, preserved[k])
  p.set('view', nav.view)
  if (nav.title) p.set('title', nav.title)
  if (nav.add) p.set('add', '1')
  return `?${p.toString()}`
}

console.log('\n── 1. parseNav ──')
assert('empty → fallback view', parseNav('', 'library').view, 'library')
assert('empty → title null', parseNav('', 'library').title, null)
assert('empty → add false', parseNav('', 'library').add, false)
assert('unknown view → fallback', parseNav('?view=bogus', 'upnext').view, 'upnext')
assert('valid view', parseNav('?view=ledger', 'library').view, 'ledger')
assert('title round-trips', parseNav('?view=library&title=abc123', 'library').title, 'abc123')
assert('add=1 → true', parseNav('?add=1', 'library').add, true)
assert('add=0 → false', parseNav('?add=0', 'library').add, false)

console.log('\n── 2. preservedParams ──')
assert('share extracted', preservedParams('?share=tok&view=ledger').share, 'tok')
assert('no share → undefined', preservedParams('?view=ledger').share, undefined)

console.log('\n── 3. serializeNav ──')
assert('includes view', serializeNav({ view: 'ledger', title: null, add: false }, {}), '?view=ledger')
assert('omits null title/add', serializeNav({ view: 'upnext', title: null, add: false }, {}).includes('title'), false)
const withTitle = serializeNav({ view: 'library', title: 'x1', add: false }, {})
assert('title serialized', withTitle.includes('title=x1'), true)
const withShare = serializeNav({ view: 'ledger', title: null, add: false }, { share: 'tok' })
assert('share preserved + view', withShare.includes('share=tok') && withShare.includes('view=ledger'), true)
assert('determinism', serializeNav({ view: 'library', title: 't', add: true }, { share: 's' }),
                       serializeNav({ view: 'library', title: 't', add: true }, { share: 's' }))

console.log('\n── 4. round-trip identity ──')
for (const n of [
  { view: 'upnext', title: null, add: false },
  { view: 'library', title: 'abc', add: false },
  { view: 'ledger', title: null, add: true },
  { view: 'library', title: 'z9', add: true },
]) {
  const round = parseNav(serializeNav(n, {}), 'library')
  assert(`round-trip ${JSON.stringify(n)}`, JSON.stringify(round), JSON.stringify(n))
}

console.log(`\n${'─'.repeat(50)}`)
console.log(`Result: ${pass} passed, ${fail} failed`)
if (fail > 0) process.exit(1)
```

- [ ] **Step 3: Run the verify script — expect all pass**

Run: `node scripts/verify-navigation-logic.mjs`
Expected: `Result: N passed, 0 failed` (exit 0)

- [ ] **Step 4: Typecheck**

Run: `npx tsc -b`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add src/lib/navigation.ts scripts/verify-navigation-logic.mjs
git commit -m "feat(nav): pure query-param navigation module + verify script"
```

---

### Task 2: `useNavigationSync` hook + lift `currentView` from URL in `App`

**Files:**
- Create: `src/lib/useNavigationSync.ts`
- Modify: `src/App.tsx`

**Interfaces:**
- Consumes: `parseNav`, `serializeNav`, `preservedParams`, `AppView`, `NavState` (Task 1); store actions `openDetailDrawer`, `closeDetailDrawer`, `openAddTitle`, `closeAddTitle` and flags `selectedTitleId`, `isDetailDrawerOpen`, `isAddTitleOpen`.
- Produces: `useNavigationSync({ currentView, setCurrentView }: { currentView: AppView; setCurrentView: (v: AppView) => void }): void`

- [ ] **Step 1: Write `src/lib/useNavigationSync.ts`**

```ts
import { useEffect, useRef } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { parseNav, serializeNav, preservedParams, type AppView, type NavState } from './navigation'

// Bridges the URL <-> (App currentView + store drawer/add flags) without feedback
// loops. Modal opens push a history entry so the browser/mobile back button closes
// them; everything else replaces. A push-depth counter lets close transitions pop
// the entry we added (instead of leaving stale entries), while never calling back()
// when there is no entry of ours to pop (which would leave the app).
export function useNavigationSync({
  currentView,
  setCurrentView,
}: {
  currentView: AppView
  setCurrentView: (v: AppView) => void
}): void {
  const selectedTitleId = useAppStore((s) => s.selectedTitleId)
  const isDetailDrawerOpen = useAppStore((s) => s.isDetailDrawerOpen)
  const isAddTitleOpen = useAppStore((s) => s.isAddTitleOpen)

  const drawerTitle = isDetailDrawerOpen ? selectedTitleId : null
  const desired: NavState = { view: currentView, title: drawerTitle, add: isAddTitleOpen }

  const prevRef = useRef<NavState | null>(null)
  const pushDepth = useRef(0)
  const viewRef = useRef(currentView)
  viewRef.current = currentView

  // ── popstate: URL drives state (this is what makes Back close the drawer) ──
  useEffect(() => {
    function onPop() {
      const s = useAppStore.getState()
      const fromUrl = parseNav(window.location.search, viewRef.current)
      setCurrentView(fromUrl.view)
      if (fromUrl.title) {
        if (!s.isDetailDrawerOpen || s.selectedTitleId !== fromUrl.title) s.openDetailDrawer(fromUrl.title)
      } else if (s.isDetailDrawerOpen) {
        s.closeDetailDrawer()
      }
      if (fromUrl.add) {
        if (!s.isAddTitleOpen) s.openAddTitle()
      } else if (s.isAddTitleOpen) {
        s.closeAddTitle()
      }
      // A pop consumed one of our pushed entries (if any).
      if (pushDepth.current > 0) pushDepth.current -= 1
      prevRef.current = { view: fromUrl.view, title: fromUrl.title, add: fromUrl.add }
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [setCurrentView])

  // ── state -> URL ──
  useEffect(() => {
    const preserved = preservedParams(window.location.search)
    const currentNav = parseNav(window.location.search, desired.view)
    const sameAsUrl =
      currentNav.view === desired.view &&
      currentNav.title === desired.title &&
      currentNav.add === desired.add
    if (sameAsUrl) {
      prevRef.current = desired
      return
    }

    const prev = prevRef.current
    const nextUrl = serializeNav(desired, preserved)
    const wasModal = prev ? !!prev.title || prev.add : false
    const isModal = !!desired.title || desired.add

    if (prev === null) {
      // First reconcile after mount: normalize without adding history.
      window.history.replaceState({}, '', nextUrl)
    } else if (!wasModal && isModal) {
      window.history.pushState({}, '', nextUrl)
      pushDepth.current += 1
    } else if (wasModal && !isModal && pushDepth.current > 0) {
      // Closing a modal we pushed: pop it so history stays clean. popstate's
      // handler then reconciles (state already matches → no-op) and decrements.
      window.history.back()
      prevRef.current = desired
      return
    } else {
      window.history.replaceState({}, '', nextUrl)
    }
    prevRef.current = desired
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [desired.view, desired.title, desired.add])
}
```

- [ ] **Step 2: Wire it into `src/App.tsx`** — replace the `currentView` initializer and mount the hook

Replace the existing import block addition and the `currentView` useState. Change:

```tsx
import { computeUpNextShows } from 'src/store/upNext'
```
to additionally import navigation + the hook, and add `AppView`:

```tsx
import { computeUpNextShows } from 'src/store/upNext'
import { parseNav, type AppView } from 'src/lib/navigation'
import { useNavigationSync } from 'src/lib/useNavigationSync'
```

Replace the `type AppView = ...` line (delete the local type — now imported) and the `useState` initializer:

```tsx
export default function App() {
  // Smart landing unless the URL already names a view (deep link / refresh).
  const [currentView, setCurrentView] = useState<AppView>(() => {
    const smart: AppView =
      computeUpNextShows(useAppStore.getState().titles).length > 0 ? 'upnext' : 'library'
    return parseNav(window.location.search, smart).view
  })

  useNavigationSync({ currentView, setCurrentView })
```

(Keep the rest of `App` unchanged: `isProfileOpen`, the auth `useEffect`, render tree.)

- [ ] **Step 3: Typecheck + lint**

Run: `npx tsc -b && npm run lint`
Expected: no errors (the local `type AppView` removal must not leave dangling references — `currentView`/`setCurrentView` types now come from the import).

- [ ] **Step 4: Manual smoke test (dev server)**

Run: `npm run dev`, then in the browser:
- Switch views → URL `?view=…` updates (replace; no history pile-up).
- Open a title → URL gains `&title=…`; press browser Back → drawer closes, stays in app.
- With a title open, refresh → drawer reopens on the same view.
- Open Add modal → `&add=1`; Back closes it.
Expected: all behaviors hold; no console errors; `?share=` (if present) is never dropped.

- [ ] **Step 5: Commit**

```bash
git add src/lib/useNavigationSync.ts src/App.tsx
git commit -m "feat(nav): URL-sync hook — deep links + back button closes drawer/add"
```

---

### Task 3: Pure command-ranking module (`commands.ts`) + verify script

**Files:**
- Create: `src/store/commands.ts`
- Create: `scripts/verify-command-logic.mjs`

**Interfaces:**
- Produces:
  - `interface Command { id: string; kind: 'title' | 'action'; label: string; hint?: string; keywords?: string }`
  - `scoreCommand(cmd: Command, query: string): number`
  - `rankCommands(commands: Command[], query: string, limit?: number): Command[]`

- [ ] **Step 1: Write `src/store/commands.ts`**

```ts
// Pure command model + ranking for the command palette. Verified by
// scripts/verify-command-logic.mjs. No React / store imports here.

export interface Command {
  id: string
  kind: 'title' | 'action'
  label: string
  hint?: string        // shown muted on the right (e.g. "dir. Villeneuve · 2017")
  keywords?: string    // extra match text (genres, synonyms); ranks below label
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

// Tiered match score; -1 means "no match". Shorter labels get a tiny boost so
// ties resolve to the more specific item. Empty query → 0 (keep input order).
export function scoreCommand(cmd: Command, query: string): number {
  const q = query.trim().toLowerCase()
  if (!q) return 0
  const label = cmd.label.toLowerCase()
  const kw = (cmd.keywords ?? '').toLowerCase()
  const lenAdj = Math.min(label.length, 40) * 0.1
  const boundary = new RegExp(`\\b${escapeRegex(q)}`)

  if (label === q) return 100 - lenAdj
  if (label.startsWith(q)) return 80 - lenAdj
  if (boundary.test(label)) return 60 - lenAdj
  if (label.includes(q)) return 40 - lenAdj
  if (kw) {
    if (boundary.test(kw)) return 20
    if (kw.includes(q)) return 10
  }
  return -1
}

export function rankCommands(commands: Command[], query: string, limit = 8): Command[] {
  if (!query.trim()) return commands.slice(0, limit)
  const scored = commands
    .map((cmd) => ({ cmd, score: scoreCommand(cmd, query) }))
    .filter((x) => x.score >= 0)
  scored.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score
    if (a.cmd.label.length !== b.cmd.label.length) return a.cmd.label.length - b.cmd.label.length
    return a.cmd.id < b.cmd.id ? -1 : a.cmd.id > b.cmd.id ? 1 : 0
  })
  return scored.slice(0, limit).map((x) => x.cmd)
}
```

- [ ] **Step 2: Write `scripts/verify-command-logic.mjs`** (mirrors the logic)

```js
/**
 * Runtime logic verification for command-palette ranking (src/store/commands.ts).
 * Run with: node scripts/verify-command-logic.mjs
 */
let pass = 0, fail = 0
function assert(label, actual, expected) {
  if (actual === expected) { console.log(`  ✓ ${label}: ${JSON.stringify(actual)}`); pass++ }
  else { console.error(`  ✗ ${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`); fail++ }
}
function escapeRegex(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') }
function scoreCommand(cmd, query) {
  const q = query.trim().toLowerCase()
  if (!q) return 0
  const label = cmd.label.toLowerCase()
  const kw = (cmd.keywords ?? '').toLowerCase()
  const lenAdj = Math.min(label.length, 40) * 0.1
  const boundary = new RegExp(`\\b${escapeRegex(q)}`)
  if (label === q) return 100 - lenAdj
  if (label.startsWith(q)) return 80 - lenAdj
  if (boundary.test(label)) return 60 - lenAdj
  if (label.includes(q)) return 40 - lenAdj
  if (kw) { if (boundary.test(kw)) return 20; if (kw.includes(q)) return 10 }
  return -1
}
function rankCommands(commands, query, limit = 8) {
  if (!query.trim()) return commands.slice(0, limit)
  const scored = commands.map((cmd) => ({ cmd, score: scoreCommand(cmd, query) })).filter((x) => x.score >= 0)
  scored.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score
    if (a.cmd.label.length !== b.cmd.label.length) return a.cmd.label.length - b.cmd.label.length
    return a.cmd.id < b.cmd.id ? -1 : a.cmd.id > b.cmd.id ? 1 : 0
  })
  return scored.slice(0, limit).map((x) => x.cmd)
}

const C = (id, label, extra = {}) => ({ id, kind: 'title', label, ...extra })
const cmds = [
  C('a', 'Overblade'),
  C('b', 'Blade Runner', { keywords: 'sci-fi noir villeneuve' }),
  C('c', 'Blade Runner 2049'),
  C('d', 'The Godfather', { keywords: 'crime' }),
]

console.log('\n── 1. empty query keeps input order, capped ──')
const empt = rankCommands(cmds, '', 3)
assert('count = 3', empt.length, 3)
assert('order preserved [0]=a', empt[0].id, 'a')
assert('order preserved [2]=c', empt[2].id, 'c')

console.log('\n── 2. prefix beats mid-substring ──')
const bla = rankCommands(cmds, 'bla', 8)
assert('prefix "Blade Runner" before "Overblade"',
  bla.findIndex((x) => x.id === 'b') < bla.findIndex((x) => x.id === 'a'), true)

console.log('\n── 3. shorter label wins ties (b before c) ──')
assert('"Blade Runner" before "Blade Runner 2049"',
  bla.findIndex((x) => x.id === 'b') < bla.findIndex((x) => x.id === 'c'), true)

console.log('\n── 4. word-boundary match ──')
const run = rankCommands(cmds, 'run', 8)
assert('"Blade Runner" matched via boundary', run.some((x) => x.id === 'b'), true)

console.log('\n── 5. keyword match included, ranks below label ──')
const noir = rankCommands(cmds, 'noir', 8)
assert('keyword "noir" finds Blade Runner', noir.some((x) => x.id === 'b'), true)
assert('keyword-only "crime" finds Godfather', rankCommands(cmds, 'crime', 8).some((x) => x.id === 'd'), true)

console.log('\n── 6. no match excluded ──')
assert('"zzz" → empty', rankCommands(cmds, 'zzz', 8).length, 0)

console.log(`\n${'─'.repeat(50)}`)
console.log(`Result: ${pass} passed, ${fail} failed`)
if (fail > 0) process.exit(1)
```

- [ ] **Step 3: Run verify script — expect all pass**

Run: `node scripts/verify-command-logic.mjs`
Expected: `Result: N passed, 0 failed` (exit 0)

- [ ] **Step 4: Commit**

```bash
git add src/store/commands.ts scripts/verify-command-logic.mjs
git commit -m "feat(palette): pure command ranking module + verify script"
```

---

### Task 4: Store palette flags + `CommandPalette` component + CSS

**Files:**
- Modify: `src/store/useAppStore.ts` (UISlice interface + impl)
- Create: `src/components/CommandPalette.tsx`
- Modify: `src/index.css` (append palette styles)

**Interfaces:**
- Consumes: `Command`, `rankCommands` (Task 3); `@radix-ui/react-dialog`.
- Produces:
  - Store: `isCommandPaletteOpen: boolean`, `openCommandPalette(): void`, `closeCommandPalette(): void`.
  - `CommandPalette` props: `{ open: boolean; onClose: () => void; commands: Command[]; onRun: (cmd: Command) => void }`.

- [ ] **Step 1: Add palette flags to the store UISlice**

In `src/store/useAppStore.ts`, in `interface UISlice` add after `isSharedView: boolean`:

```ts
  isCommandPaletteOpen: boolean
```
and after `setIsSharedView`:
```ts
  openCommandPalette: () => void
  closeCommandPalette: () => void
```

In the store implementation, after the `isSharedView: false,` / `setIsSharedView` block (around line 452-453) add:

```ts
  isCommandPaletteOpen: false,
  openCommandPalette: () => set({ isCommandPaletteOpen: true }),
  closeCommandPalette: () => set({ isCommandPaletteOpen: false }),
```

- [ ] **Step 2: Create `src/components/CommandPalette.tsx`**

```tsx
import { useState, useEffect, useMemo, useRef } from 'react'
import * as DialogPrimitive from '@radix-ui/react-dialog'
import { Search, CornerDownLeft } from 'lucide-react'
import { rankCommands, type Command } from 'src/store/commands'
import { cn } from 'src/lib/utils'

interface CommandPaletteProps {
  open: boolean
  onClose: () => void
  commands: Command[]
  onRun: (cmd: Command) => void
}

export function CommandPalette({ open, onClose, commands, onRun }: CommandPaletteProps) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const listRef = useRef<HTMLDivElement>(null)

  const results = useMemo(() => rankCommands(commands, query, 8), [commands, query])

  // Reset on open; clamp active when results shrink.
  useEffect(() => { if (open) { setQuery(''); setActive(0) } }, [open])
  useEffect(() => { setActive(0) }, [query])

  // Keep the active row scrolled into view.
  useEffect(() => {
    const el = listRef.current?.querySelector<HTMLElement>(`[data-idx="${active}"]`)
    el?.scrollIntoView({ block: 'nearest' })
  }, [active])

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((i) => (results.length ? (i + 1) % results.length : 0))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((i) => (results.length ? (i - 1 + results.length) % results.length : 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const cmd = results[active]
      if (cmd) onRun(cmd)
    }
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="cinema-overlay" />
        <DialogPrimitive.Content
          className="command-content"
          onKeyDown={handleKeyDown}
          aria-label="Command palette"
        >
          <DialogPrimitive.Title className="sr-only">Command palette</DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            Search titles or run an action. Use arrow keys and Enter.
          </DialogPrimitive.Description>

          <div className="command-input-row">
            <Search className="w-[18px] h-[18px] text-paper-faint shrink-0" />
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search titles, jump to a view, add a title…"
              className="command-input"
              autoComplete="off"
              spellCheck={false}
              role="combobox"
              aria-expanded
              aria-controls="command-results"
              aria-activedescendant={results[active] ? `cmd-${results[active].id}` : undefined}
            />
            <kbd className="command-kbd">ESC</kbd>
          </div>

          <div id="command-results" ref={listRef} role="listbox" className="command-list">
            {results.length === 0 ? (
              <div className="command-empty">No matches. Try a different search.</div>
            ) : (
              results.map((cmd, idx) => (
                <button
                  key={cmd.id}
                  id={`cmd-${cmd.id}`}
                  data-idx={idx}
                  role="option"
                  aria-selected={idx === active}
                  onMouseEnter={() => setActive(idx)}
                  onClick={() => onRun(cmd)}
                  className={cn('command-item', idx === active && 'is-active')}
                >
                  <span
                    className={cn(
                      'command-item__kind',
                      cmd.kind === 'action' ? 'text-amber' : 'text-paper-faint'
                    )}
                  >
                    {cmd.kind === 'action' ? '›' : '▸'}
                  </span>
                  <span className="command-item__label">{cmd.label}</span>
                  {cmd.hint && <span className="command-item__hint">{cmd.hint}</span>}
                  {idx === active && (
                    <CornerDownLeft className="w-3.5 h-3.5 text-amber shrink-0 ml-1" />
                  )}
                </button>
              ))
            )}
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
```

- [ ] **Step 3: Append palette styles to `src/index.css`**

```css
/* ── Command palette ─────────────────────────────────────────────── */
.command-content {
  position: fixed;
  left: 50%;
  top: 14vh;
  transform: translateX(-50%);
  z-index: 250;
  width: min(92vw, 560px);
  max-height: 64vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 16px;
  border: 1px solid var(--line);
  background: linear-gradient(180deg, var(--ink-1), rgba(11, 9, 7, 0.96));
  box-shadow: 0 30px 80px -20px rgba(0, 0, 0, 0.8);
}
.command-content[data-state='closed'] { opacity: 0; }
.command-input-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}
.command-input {
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  outline: none;
  color: var(--paper);
  font-family: var(--font-sans, inherit);
  font-size: 15px;
}
.command-input::placeholder { color: var(--paper-faint); }
.command-kbd {
  font-family: var(--font-mono, monospace);
  font-size: 10px;
  letter-spacing: 0.08em;
  color: var(--paper-faint);
  border: 1px solid var(--line);
  border-radius: 5px;
  padding: 2px 6px;
}
.command-list { overflow-y: auto; padding: 8px; }
.command-empty {
  padding: 28px 12px;
  text-align: center;
  color: var(--paper-faint);
  font-size: 13px;
}
.command-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  text-align: left;
  padding: 9px 12px;
  border-radius: 9px;
  transition: background 0.12s ease;
}
.command-item.is-active { background: rgba(233, 178, 102, 0.1); }
.command-item__kind { font-family: var(--font-mono, monospace); font-size: 12px; width: 12px; }
.command-item__label { flex: 1; min-width: 0; color: var(--paper); font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.command-item__hint { color: var(--paper-faint); font-size: 11px; font-family: var(--font-mono, monospace); white-space: nowrap; }
```

(If `--font-sans`/`--font-mono` tokens are not defined, the `inherit`/`monospace` fallbacks apply — verify against the existing `:root` and drop the `var(...)` wrapper if the project uses Tailwind font classes instead. Check `index.css` `:root` first and match the existing font convention.)

- [ ] **Step 4: Typecheck + lint**

Run: `npx tsc -b && npm run lint`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add src/store/useAppStore.ts src/components/CommandPalette.tsx src/index.css
git commit -m "feat(palette): CommandPalette component, store flags, and styles"
```

---

### Task 5: Wire palette into `App` (hotkey + commands + render) and `TopBar` trigger

**Files:**
- Modify: `src/App.tsx`
- Modify: `src/components/TopBar.tsx`

**Interfaces:**
- Consumes: `Command` (Task 3); `CommandPalette` (Task 4); store flags/actions (Task 4); existing `setCurrentView`, `openDetailDrawer`, `openAddTitle`, `setViewMode`, `setIsProfileOpen`.

- [ ] **Step 1: Build commands + handlers and render the palette in `src/App.tsx`**

Add imports:
```tsx
import { useState, useEffect, useMemo } from 'react'
import { CommandPalette } from 'src/components/CommandPalette'
import type { Command } from 'src/store/commands'
```

Inside `App`, after `useNavigationSync(...)`, add store reads and the hotkey + command construction:

```tsx
  const titles = useAppStore((s) => s.titles)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const isCommandPaletteOpen = useAppStore((s) => s.isCommandPaletteOpen)
  const openCommandPalette = useAppStore((s) => s.openCommandPalette)
  const closeCommandPalette = useAppStore((s) => s.closeCommandPalette)
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const openAddTitle = useAppStore((s) => s.openAddTitle)
  const setViewMode = useAppStore((s) => s.setViewMode)

  // ⌘K / Ctrl+K toggles the palette from anywhere.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        const s = useAppStore.getState()
        if (s.isCommandPaletteOpen) s.closeCommandPalette()
        else s.openCommandPalette()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // Build the command list + an id→handler map. Title commands open the drawer
  // (which, via useNavigationSync, becomes a back-button-closable history entry).
  const { commands, runMap } = useMemo(() => {
    const list: Command[] = []
    const map: Record<string, () => void> = {}

    if (!isSharedView) {
      list.push({ id: 'action:add', kind: 'action', label: 'Add a title', hint: 'new', keywords: 'create new movie series' })
      map['action:add'] = () => openAddTitle()
    }
    list.push({ id: 'action:view-upnext', kind: 'action', label: 'Go to Up Next', hint: 'view', keywords: 'continue watching' })
    map['action:view-upnext'] = () => setCurrentView('upnext')
    list.push({ id: 'action:view-library', kind: 'action', label: 'Go to the Library', hint: 'view', keywords: 'collection posters' })
    map['action:view-library'] = () => setCurrentView('library')
    list.push({ id: 'action:view-ledger', kind: 'action', label: 'Go to the Ledger', hint: 'view', keywords: 'stats dashboard' })
    map['action:view-ledger'] = () => setCurrentView('ledger')
    list.push({ id: 'action:layout-grid', kind: 'action', label: 'Library: poster wall', hint: 'layout', keywords: 'grid posters' })
    map['action:layout-grid'] = () => { setCurrentView('library'); setViewMode('grid') }
    list.push({ id: 'action:layout-list', kind: 'action', label: 'Library: ledger list', hint: 'layout', keywords: 'list table' })
    map['action:layout-list'] = () => { setCurrentView('library'); setViewMode('list') }

    for (const t of titles) {
      const id = `title:${t.id}`
      const hint = [t.director ? `dir. ${t.director}` : t.type === 'tv' ? 'series' : 'film', t.year]
        .filter(Boolean)
        .join(' · ')
      list.push({ id, kind: 'title', label: t.title, hint, keywords: t.genres.join(' ') })
      map[id] = () => openDetailDrawer(t.id)
    }
    return { commands: list, runMap: map }
  }, [titles, isSharedView, openAddTitle, openDetailDrawer, setViewMode])

  function runCommand(cmd: Command) {
    closeCommandPalette()
    runMap[cmd.id]?.()
  }
```

Then add the palette to the render tree, next to the other modals (after `<ProfileModal … />`):

```tsx
      <CommandPalette
        open={isCommandPaletteOpen}
        onClose={closeCommandPalette}
        commands={commands}
        onRun={runCommand}
      />
```

- [ ] **Step 2: Add a discoverable trigger to `src/components/TopBar.tsx`**

Add `Search` to the lucide import and read `openCommandPalette` from the store:

```tsx
import { Plus, LayoutGrid, List, BarChart3, User, PlayCircle, Search } from 'lucide-react'
```
```tsx
  const { viewMode, setViewMode, openAddTitle, user, isSharedView, openCommandPalette } = useAppStore()
```

In the Actions block, as the first child of `<div className="flex items-center gap-2 ml-auto shrink-0">` (before the `viewMode` segmented control), insert:

```tsx
          <button
            onClick={openCommandPalette}
            aria-label="Open command palette"
            className="icon-btn h-9 border rounded-md text-paper-dim hover:text-amber transition-colors flex items-center gap-2 px-2.5 sm:px-3"
            style={{ borderColor: 'var(--line)', background: 'rgba(0,0,0,0.3)' }}
          >
            <Search className="w-[17px] h-[17px]" />
            <span className="hidden lg:inline font-sans text-[13px] text-paper-faint">Search</span>
            <kbd className="hidden lg:inline font-mono text-[10px] tracking-[0.06em] text-paper-faint border rounded px-1.5 py-0.5" style={{ borderColor: 'var(--line)' }}>
              ⌘K
            </kbd>
          </button>
```

- [ ] **Step 3: Typecheck + lint**

Run: `npx tsc -b && npm run lint`
Expected: no errors.

- [ ] **Step 4: Manual smoke test**

Run: `npm run dev`:
- Press ⌘K / Ctrl+K → palette opens, input focused.
- Type part of a title → it ranks to the top; ↑/↓ moves highlight; Enter opens the drawer; URL gains `&title=…`; Back closes it.
- ⌘K → "Go to the Ledger" → Enter switches view; URL `?view=ledger`.
- Esc closes the palette. Click the TopBar Search button → opens too.
Expected: all pass; no console errors.

- [ ] **Step 5: Commit**

```bash
git add src/App.tsx src/components/TopBar.tsx
git commit -m "feat(palette): ⌘K hotkey, command wiring, and TopBar trigger"
```

---

### Task 6: Full verification pass + docs

**Files:**
- Modify: `README.md` (brief feature note, if a features list exists)

- [ ] **Step 1: Run both verify scripts**

Run: `node scripts/verify-navigation-logic.mjs && node scripts/verify-command-logic.mjs`
Expected: both `0 failed`, exit 0.

- [ ] **Step 2: Production build + lint**

Run: `npm run build && npm run lint`
Expected: build succeeds (tsc -b && vite build), lint clean.

- [ ] **Step 3: Manual regression of existing flows**

- Shared view (`?share=…` if available, else simulate via `isSharedView`): palette excludes "Add a title"; views still navigable; share token survives navigation.
- Deleting the open title closes the drawer and Back doesn't reopen a phantom.
- Mobile viewport (devtools): TopBar Search button shows icon-only; ⌘K not needed; back gesture closes drawer.

- [ ] **Step 4: Commit any doc updates**

```bash
git add README.md
git commit -m "docs: note ⌘K palette and URL deep-linking"
```

---

## Self-Review

**Spec coverage:**
- URL shape `view/title/add/share` → Task 1 (`navigation.ts`) + Task 2 (wiring). ✓
- Refresh restores view+title → Task 2 (currentView init + popstate/initial reconcile). ✓
- Back closes drawer/add, never leaves app → Task 2 (push-on-open + pushDepth-guarded back). ✓
- `share` preserved → Task 1 `serializeNav(preserved)` + Task 2 reads `preservedParams`. ✓
- Command palette open/close + ⌘K → Task 4 (store flags) + Task 5 (hotkey). ✓
- Title search + action commands + ranking → Task 3 (`commands.ts`) + Task 5 (construction). ✓
- Radix-based accessible overlay (focus trap/Esc/portal) → Task 4 component. ✓
- Discoverable trigger → Task 5 TopBar button. ✓
- Shared-view safety (no Add) → Task 5 `isSharedView` guard. ✓
- Verify scripts + build/lint → Tasks 1, 3, 6. ✓

**Placeholder scan:** No TBD/TODO; all code shown inline. The one conditional note (font tokens in Task 4 Step 3) includes the exact fallback action. ✓

**Type consistency:** `AppView`, `NavState`, `parseNav/serializeNav/preservedParams` consistent across Tasks 1–2. `Command`, `scoreCommand`, `rankCommands` consistent across Tasks 3–5. Store flag names `isCommandPaletteOpen/openCommandPalette/closeCommandPalette` consistent across Tasks 4–5. `CommandPalette` prop names consistent Task 4↔5. ✓
