# Source Reference

A comprehensive map of source files, their responsibilities, and key exports. Use this for quick navigation when modifying code.

---

## Frontend (React + TypeScript)

### Core Application

| File | Responsibility | Key Exports |
|------|-----------------|--------------|
| `src/main.tsx` | Vite entry point; mounts App to DOM | N/A |
| `src/App.tsx` | Root component; route handler, auth check, keyboard shortcuts | App (default export) |
| `src/index.css` | Global styles, CSS variables, theme definitions, atmospheric effects | N/A |
| `src/App.css` | App-level styles, utility classes | N/A |

### Views (Pages)

| File | Responsibility | Key Exports |
|------|-----------------|--------------|
| `src/views/Library.tsx` | Poster wall / list view of library; rendering, filtering UI | Library (default) |
| `src/views/Discover.tsx` | TMDB search browser; "New to me" feature | Discover (default) |
| `src/views/Ledger.tsx` | Stats dashboard router | Ledger (default) |
| `src/views/ledger/` | Individual widget panels | RatingDistribution, TheRun, TheEnsemble, etc. |
| `src/views/UpNext.tsx` | Upcoming releases + unwatched episodes | UpNext (default) |
| `src/views/Friends.tsx` | Friend list, activity feed, blocking, recommendations inbox | Friends (default) |
| `src/views/Profile.tsx` | Account settings, sharing, invites, themes, nav prefs | Profile (default) |

### Components

| File | Responsibility | Key Exports |
|------|-----------------|--------------|
| `src/components/AddTitleWorkflow.tsx` | Search + add title form | AddTitleWorkflow (default) |
| `src/components/TitleDetailDrawer.tsx` | Title detail view, episode logging, viewing history (LARGE FILE) | TitleDetailDrawer (default) |
| `src/components/AppCommandPalette.tsx` | Command palette (⌘K / Ctrl+K) | AppCommandPalette (default) |
| `src/components/CommandPalette.tsx` | Generic command palette (used by App Command Palette) | CommandPalette (default) |
| `src/components/TopBar.tsx` | Header with nav pills + icons | TopBar (default) |
| `src/components/BottomNav.tsx` | Mobile bottom navigation | BottomNav (default) |
| `src/components/ProfileModal.tsx` | Account dropdown menu | ProfileModal (default) |
| `src/components/NotificationCenter.tsx` | Notification inbox | NotificationCenter (default) |
| `src/components/NotificationStack.tsx` | Toast-style ephemeral notifications | NotificationStack (default) |
| `src/components/SendRecommendationPanel.tsx` | Recommendation send UI | SendRecommendationPanel (default) |
| `src/components/TitleCommentsPanel.tsx` | Comments & reactions on titles | TitleCommentsPanel (default) |
| `src/components/ShareScopeEditor.tsx` | Scope selector for share links/friends | ShareScopeEditor (default) |
| `src/components/RefreshMetadataModal.tsx` | Metadata refresh from TMDB | RefreshMetadataModal (default) |
| `src/components/InviteRedeemForm.tsx` | Signup form | InviteRedeemForm (default) |
| `src/components/KeyboardShortcutsHelp.tsx` | Keyboard shortcuts help modal | KeyboardShortcutsHelp (default) |
| `src/components/LandingScreen.tsx` | Pre-auth landing page | LandingScreen (default) |
| `src/components/MatrixPillModal.tsx` | Easter egg matrix theme selector | MatrixPillModal (default) |
| `src/components/SpiderNoirModeModal.tsx` | Easter egg noir theme selector | SpiderNoirModeModal (default) |
| `src/components/ui/` | Atomic shadcn components | Button, Dialog, Dropdown, Tabs, Slider, etc. |

### Store (Zustand)

| File | Responsibility | Key Exports |
|------|-----------------|--------------|
| `src/store/useAppStore.ts` | Main Zustand store; library, ledger, ui slices | useAppStore (hook) |
| `src/store/mockData.ts` | Type definitions + seed data | Title, Season, Episode, interfaces; mockTitles data |
| `src/store/episodeUtils.ts` | Episode → season → series rollup computation | nextUnwatchedEpisode, rollup helpers |
| `src/store/ledgerDerive.ts` | Stat computation + per-widget derivations | computeLedgerStats, ledgerStats computation |
| `src/store/ledgerStats.ts` | Aggregation helpers | countBy, avgOf, topX functions |
| `src/store/upNext.ts` | UpNext + upcoming computation | computeUpNextShows, computeUpcomingTitles |
| `src/store/commands.ts` | Command palette commands | commandActions, searchQueryCommands |

### Libraries & Utilities

| File | Responsibility | Key Exports |
|------|-----------------|--------------|
| `src/lib/db.ts` | All Supabase read/write operations (LARGE FILE) | fetchUserLibrary, insertTitleToDb, logEpisodeToDb, etc. |
| `src/lib/auth.ts` | Supabase Auth + passkey/WebAuthn | signUpWithOtp, redeemInvite, signInWithPasskey, logout, etc. |
| `src/lib/media.ts` | TMDB/OMDb API wrapper (calls media-proxy Edge Function) | searchMedia, getMediaDetails, omdbFetch |
| `src/lib/export-import.ts` | JSON backup/restore | exportLibraryAsJSON, importLibraryFromJSON |
| `src/lib/navigation.ts` | URL ↔ AppView parser | parseNav, serializeNav, preservedParams |
| `src/lib/useNavigationSync.ts` | URL ↔ store synchronization hook | useNavigationSync (hook) |
| `src/lib/useKeyboardShortcuts.ts` | Single-key shortcuts (1-6 for nav, ? for help) | useKeyboardShortcuts (hook) |
| `src/lib/theme.ts` | Theme switching + CSS variable updates | toggleTheme, applyTheme |
| `src/lib/ledgerPanels.ts` | Ledger widget definitions + layout helpers | LedgerPanelId, LedgerWidget, defaultLedgerWidgets |
| `src/lib/utils.ts` | Utility functions | cn (Tailwind merge), date helpers |

---

## Backend (Supabase)

### Database Schema

| File | Content |
|------|---------|
| `/schema.sql` | **Canonical schema + RLS policies** (human-readable reference) |

**Tables** (see schema.sql for details):
- **Core**: `titles`, `seasons`, `episodes`
- **Tracking**: `episode_watch_events`, `episode_ratings`, `episode_reviews`, `viewings`
- **Metadata**: `title_cast`, `title_crew`, `season_cast`, `episode_crew`
- **Social**: `friendships`, `friend_activity_feed`, `title_comments`, `title_reactions`, `recommendations`
- **Sharing**: `shared_access_keys`, `share_scopes`
- **Notifications**: `notifications`
- **Auth**: `invite_codes`, `invite_redeem_attempts` (custom tables; users in auth.users)
- **Config**: `user_prefs`, `profiles`
- **Cache**: `api_cache` (for media-proxy Edge Function)

### Migrations

Located in `/supabase/migrations/`, timestamped.

Recent migrations (last 10):
- `20260706100000_invite_redeemed_notification.sql` — Invite redeemed trigger + notification
- `20260706090000_notifications.sql` — Notification table + event triggers
- `20260706080000_activity_feed_pagination.sql` — Activity feed pagination logic
- `20260706070000_title_comments_reactions.sql` — Comments & reactions
- `20260706060000_share_scopes.sql` — Per-link/per-friend scoping
- `20260706050000_invite_redeem_attempts.sql` — Rate limiting
- `20260706040000_owner_flag.sql` — Owner flag for invites
- `20260706030000_friendship_unblock.sql` — Unblock state
- `20260706020000_shared_key_hardening.sql` — RLS hardening
- `20260706010000_recommendation_notes.sql` — Personal notes on recommendations

Browse `/supabase/migrations/` for complete history.

### Edge Functions

| File | Purpose | Endpoint |
|------|---------|----------|
| `supabase/functions/media-proxy/index.ts` | Proxy TMDB/OMDb API with caching | `POST /functions/v1/media-proxy` |
| `supabase/functions/redeem-invite/index.ts` | Server-side invite validation + user creation | `POST /functions/v1/redeem-invite` |

---

## Configuration & Build

| File | Purpose |
|------|---------|
| `package.json` | Dependencies, scripts (dev, build, lint, preview) |
| `tsconfig.json` | TypeScript configuration |
| `tsconfig.app.json` | App-specific TypeScript config |
| `vite.config.ts` | Vite build config + PWA plugin |
| `.eslintrc.js` | ESLint rules |
| `.prettierrc` | Prettier formatter config |
| `tailwind.config.js` | Tailwind CSS config (theme, plugins) |
| `components.json` | shadcn component config |
| `postcss.config.js` | PostCSS config (autoprefixer) |
| `index.html` | Entry HTML; includes FOUC prevention script |

---

## Scripts & Verification

| File | Purpose |
|------|---------|
| `scripts/verify-episode-logic.mjs` | Verify episode → season → series rollups |
| `scripts/verify-navigation-logic.mjs` | Verify URL ↔ view parsing |
| `scripts/verify-friendship-state-machine.mjs` | Verify friendship state machine |
| `scripts/verify-share-scope-logic.mjs` | Verify share scope filtering |
| `scripts/verify-recommendation-inbox-logic.mjs` | Verify recommendation lifecycle |
| `scripts/verify-activity-feed-merge-logic.mjs` | Verify activity feed denormalization |
| `benchmark.js` | Measure library size impact on performance |
| `benchmark-seasons-upsert.js` | Measure Supabase upsert performance |
| `test-zustand.js` | Test Zustand store behavior |
| `test-zustand-shallow.js` | Test Zustand shallow selector |

Run verification scripts before deploying:
```bash
node scripts/verify-*.mjs
```

---

## CI/CD

| File | Purpose |
|------|---------|
| `.github/workflows/deploy.yml` | Frontend deploy to GitHub Pages |
| `.github/workflows/db-migrate.yml` | Database migration deployment |
| `.github/workflows/deploy-functions.yml` | Edge Functions deployment |

---

## Documentation

| File | Purpose |
|------|---------|
| `README.md` | Main readme: overview, features, tech stack, setup |
| `CLAUDE.md` | Guidance for Claude Code (this repo's AI conventions) |
| `AGENTS.md` | Agent instructions (you are here, OpenWiki reference) |
| `CODE_OF_CONDUCT.md` | Community guidelines |
| `LICENSE` | MIT license |
| `/docs/` | Human-written docs (known-problems, planned features, superpowers) |
| `/openwiki/` | **This documentation (AI-generated architecture & workflow guide)** |

---

## Public Assets

| Directory | Content |
|-----------|---------|
| `public/` | Static files (CNAME for custom domain, PWA manifest, etc.) |
| `src/assets/` | App icons, fonts, images |

---

## Key Data Flows

### Adding a Title

```
Component: AddTitleWorkflow.tsx
  → searchMedia() [lib/media.ts]
    → Edge Function: media-proxy
      → TMDB API
  → insertTitleToDb() [lib/db.ts]
    → Supabase: titles INSERT
  → Zustand: useAppStore.insertTitle()
    → Optimize: add to titles[]
    → Recompute: ledger stats
```

### Logging an Episode

```
Component: TitleDetailDrawer.tsx
  → Zustand: useAppStore.logEpisode()
    → Optimistic: update episode.watchEvents
    → logEpisodeToDb() [lib/db.ts]
      → Supabase: episode_watch_events INSERT
      → Update: seasons.episodes_watched
    → Recompute: series rollups, ledger stats
```

### Sharing a Library

```
Component: Profile.tsx → Sharing tab
  → createSharedAccessKey() [lib/db.ts]
    → Supabase: shared_access_keys INSERT
    → Optional: share_scopes INSERT (filtering)
  → Generate token, display link
Visitor clicks link with ?share=<token>
  → set_shared_token() RPC [lib/auth.ts]
    → Postgres: SET app.shared_token
  → RLS policies check: is_valid_shared_token()
  → Visitor sees filtered library (read-only)
```

---

## Most-Modified Files

Based on recent git history:

1. **`src/components/NotificationCenter.tsx`** — Notification center UI
2. **`src/lib/db.ts`** — DB operations (frequent schema changes)
3. **`src/store/useAppStore.ts`** — Store state + actions (frequently updated)
4. **`src/views/Discover.tsx`** — Discovery view
5. **`src/components/TitleDetailDrawer.tsx`** — Detail view (large file, many features)
6. **`schema.sql`** — Schema reference (updated with each migration)
7. **`src/views/Friends.tsx`** — Social features (recent additions)
8. **`supabase/migrations/*.sql`** — DB changes (frequent)

When modifying core features, expect these files to need updates.

---

## Finding Code by Feature

### Library Management
- `/src/views/Library.tsx` (view)
- `/src/store/useAppStore.ts` (filters, CRUD)
- `/src/lib/db.ts` (DB operations)

### Episode Tracking
- `/src/components/TitleDetailDrawer.tsx` (UI)
- `/src/store/episodeUtils.ts` (rollup logic)
- `/src/lib/db.ts` (DB operations)

### Ledger / Stats
- `/src/views/Ledger.tsx` + `/src/views/ledger/` (views)
- `/src/store/ledgerDerive.ts` (computation)
- `/src/lib/ledgerPanels.ts` (definitions)

### Sharing & Access Control
- `/src/views/Profile.tsx` (sharing tab)
- `/src/lib/auth.ts` (token setting)
- `/schema.sql` (RLS policies)

### Social Features
- `/src/views/Friends.tsx` (friend list + activity)
- `/src/components/SendRecommendationPanel.tsx` (recommendations)
- `/src/components/TitleCommentsPanel.tsx` (comments & reactions)
- `/src/lib/db.ts` (friend CRUD)

### Notifications
- `/src/components/NotificationCenter.tsx` (inbox)
- `/src/store/useAppStore.ts` (notifications slice)
- `/src/lib/db.ts` (fetch, read, dismiss)

### Auth
- `/src/lib/auth.ts` (passkey, signup, login)
- `/supabase/functions/redeem-invite/` (server-side invite validation)
- `/schema.sql` (invite tables)

---

## Quick Navigation Cheat Sheet

| Task | File(s) to Check |
|------|------------------|
| "I want to understand how filtering works" | `/src/store/useAppStore.ts` (LibraryFilters), `/src/views/Library.tsx` |
| "I need to add a ledger panel" | `/src/lib/ledgerPanels.ts`, `/src/views/ledger/`, `/src/store/ledgerDerive.ts` |
| "How do episodes roll up to series?" | `/src/store/episodeUtils.ts` |
| "What happens when I share a link?" | `/src/views/Profile.tsx`, `/src/lib/auth.ts`, `/schema.sql` (RLS) |
| "How do notifications work?" | `/src/components/NotificationCenter.tsx`, `/src/lib/db.ts` (fetchNotifications) |
| "How is the DB structured?" | `/schema.sql` |
| "How do I add a new feature?" | See [Workflows](workflows/index.md) for data flow |
| "I'm getting a type error" | Run `npm run build` (full type context), check `/tsconfig.json` |
| "Something isn't persisting" | Check `/src/lib/db.ts` for the DB operation, check RLS in `/schema.sql` |
| "The URL isn't syncing with the view" | Check `/src/lib/useNavigationSync.ts` and `/src/lib/navigation.ts` |

---

## For Future Agents

When modifying code:

1. **Always check the store first** — `/src/store/useAppStore.ts` is the nerve center
2. **Trace async DB calls** — Follow optimistic updates → DB write pattern in `/src/lib/db.ts`
3. **Verify RLS policies** — Check `/schema.sql` to understand data access rules
4. **Run verification scripts** — After logic changes, run `node scripts/verify-*.mjs`
5. **Test type-check** — Run `npm run build` to catch full TypeScript errors
6. **Check git history** — Understand why code exists before changing it

---

See [Workflows](workflows/index.md) for detailed data flows, [Architecture](architecture/overview.md) for system design, and [Features](features/index.md) for feature-specific details.
