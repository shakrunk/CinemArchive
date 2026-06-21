# Spider Noir Viewing Mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-episode easter egg for Spider-Man: Noir that records whether the episode was watched in "Authentic Black & White" or "True-Hue Full Color", shown via a dramatic post-save modal and reflected as an app-wide CSS transformation while the title's detail drawer is open.

**Architecture:** Spider Noir is detected by hardcoded TMDB ID. `colorMode` is threaded through `logEpisode` → `logEpisodeToDb` as an optional field on both watch events and reviews. The modal fires before the actual save (pre-save): when the user clicks Save on an episode log for this title, the modal intercepts, collects the mode choice, then saves with `colorMode` included. The body class effect lives in `TitleDetailDrawer` and derives the active mode from the most recent logged event with a non-null `colorMode`.

**Tech Stack:** React 18, TypeScript, Zustand, Supabase (Postgres + PostgREST), Radix UI Dialog, Tailwind CSS + custom CSS in `index.css`.

## Global Constraints

- Spider Noir TMDB ID constant: `242484` — verify by searching "Spider-Man Noir" in the app after adding to library; if it doesn't trigger the feature, look up the correct ID on tmdb.org and update the constant.
- `colorMode` is nullable everywhere — non–Spider Noir titles never populate it; the feature must be inert for all other titles.
- No new npm packages — use existing Radix UI (`@radix-ui/react-dialog` is already installed via the `cinema-modal.tsx` import).
- Body class cleanup: both `spider-noir-bw` and `spider-noir-color` must be removed when `TitleDetailDrawer` unmounts, regardless of which was active.
- All DB writes are fire-and-forget (match existing pattern in `logEpisodeToDb`).
- Run `npm run build` after all tasks to confirm zero TypeScript errors.

---

### Task 1: DB Migration — add color_mode columns

**Files:**
- Create: `supabase/migrations/20260621000000_spider_noir_color_mode.sql`
- Modify: `schema.sql`

**Interfaces:**
- Produces: nullable `color_mode text check (color_mode in ('bw', 'color'))` column on `episode_watch_events` and `episode_reviews` in Supabase

- [ ] **Step 1: Write the migration file**

Create `supabase/migrations/20260621000000_spider_noir_color_mode.sql` with this exact content:

```sql
-- Spider Noir viewing mode: record whether each watch event / review was
-- experienced in black-and-white or full color. Nullable — only populated
-- for Spider-Man: Noir episodes.
alter table episode_watch_events
  add column color_mode text check (color_mode in ('bw', 'color'));

alter table episode_reviews
  add column color_mode text check (color_mode in ('bw', 'color'));
```

- [ ] **Step 2: Sync schema.sql**

In `schema.sql`, find the `episode_watch_events` table definition and add the column after `notes text`:

```sql
  notes       text,
  color_mode  text check (color_mode in ('bw', 'color')),
  created_at  timestamptz not null default now()
```

Find the `episode_reviews` table definition and add after `review_text text not null`:

```sql
  review_text  text not null,
  color_mode   text check (color_mode in ('bw', 'color')),
  reviewed_at  timestamptz not null default now()
```

- [ ] **Step 3: Push migration to Supabase**

Run from the repo root (requires Supabase CLI linked to the project):

```bash
npx supabase db push
```

Expected output includes: `Applying migration 20260621000000_spider_noir_color_mode.sql`

If you see "already applied", the migration was already run — safe to continue.

- [ ] **Step 4: Verify in Supabase dashboard**

Open the Supabase project → Table Editor → `episode_watch_events`. Confirm the `color_mode` column exists with type `text`. Repeat for `episode_reviews`.

- [ ] **Step 5: Commit**

```bash
git add supabase/migrations/20260621000000_spider_noir_color_mode.sql schema.sql
git commit -m "feat(db): add color_mode to episode watch events and reviews for Spider Noir"
```

---

### Task 2: Type + DB layer — colorMode field

**Files:**
- Modify: `src/store/mockData.ts`
- Modify: `src/lib/db.ts`

**Interfaces:**
- Consumes: `color_mode` column on both tables (from Task 1)
- Produces:
  - `EpisodeWatchEvent.colorMode?: 'bw' | 'color'`
  - `EpisodeReview.colorMode?: 'bw' | 'color'`
  - `logEpisodeToDb(userId, episodeId, opts)` where `opts` gains `colorMode?: 'bw' | 'color'`

- [ ] **Step 1: Add colorMode to EpisodeWatchEvent type**

In `src/store/mockData.ts`, find `EpisodeWatchEvent` and add the field:

```ts
export interface EpisodeWatchEvent {
  id: string
  watchedAt: string  // ISO date (YYYY-MM-DD)
  notes?: string
  colorMode?: 'bw' | 'color'  // Spider Noir only
}
```

- [ ] **Step 2: Add colorMode to EpisodeReview type**

In `src/store/mockData.ts`, find `EpisodeReview` and add the field:

```ts
export interface EpisodeReview {
  id: string
  reviewText: string
  reviewedAt: string  // ISO datetime — standalone timestamp
  colorMode?: 'bw' | 'color'  // Spider Noir only
}
```

- [ ] **Step 3: Map color_mode in mapDbTitleToLocal**

In `src/lib/db.ts`, find the `mapDbTitleToLocal` function. Inside the `watchEvents` map (around line 59), add `colorMode`:

```ts
.map((we: any) => ({
  id: we.id,
  watchedAt: we.watched_at,
  notes: we.notes || undefined,
  colorMode: we.color_mode || undefined,
})),
```

Inside the `reviews` map (around line 79), add `colorMode`:

```ts
.map((rv: any) => ({
  id: rv.id,
  reviewText: rv.review_text,
  reviewedAt: rv.reviewed_at,
  colorMode: rv.color_mode || undefined,
})),
```

- [ ] **Step 4: Extend logEpisodeToDb opts type and insert statements**

In `src/lib/db.ts`, find `logEpisodeToDb`. The `opts` parameter currently has `watchedAt`, `watchNotes`, `rating`, `reviewText`. Add `colorMode`:

```ts
export async function logEpisodeToDb(
  userId: string,
  episodeId: string,
  opts: {
    watchedAt?: string
    watchNotes?: string
    rating?: number
    reviewText?: string
    colorMode?: 'bw' | 'color'
  }
): Promise<void> {
```

In the `episode_watch_events` insert block, add `color_mode`:

```ts
if (opts.watchedAt) {
  const { error } = await supabase.from('episode_watch_events').insert({
    episode_id: episodeId,
    user_id: userId,
    watched_at: opts.watchedAt,
    notes: opts.watchNotes || undefined,
    color_mode: opts.colorMode ?? null,
  })
  if (error) {
    console.error('Error inserting episode watch event:', error)
    throw error
  }
}
```

In the `episode_reviews` insert block, add `color_mode`:

```ts
if (opts.reviewText?.trim()) {
  const { error } = await supabase.from('episode_reviews').insert({
    episode_id: episodeId,
    user_id: userId,
    review_text: opts.reviewText.trim(),
    color_mode: opts.colorMode ?? null,
  })
  if (error) {
    console.error('Error inserting episode review:', error)
    throw error
  }
}
```

- [ ] **Step 5: Verify TypeScript compiles**

```bash
npm run build
```

Expected: no errors. Fix any type errors before continuing.

- [ ] **Step 6: Commit**

```bash
git add src/store/mockData.ts src/lib/db.ts
git commit -m "feat(types): add colorMode to EpisodeWatchEvent and EpisodeReview; thread through db layer"
```

---

### Task 3: Store action — thread colorMode through logEpisode

**Files:**
- Modify: `src/store/useAppStore.ts`

**Interfaces:**
- Consumes: `EpisodeWatchEvent.colorMode` and `EpisodeReview.colorMode` (from Task 2)
- Produces: `EpisodeLogOpts.colorMode?: 'bw' | 'color'`; `logEpisode` writes `colorMode` into local state for watch events and reviews

- [ ] **Step 1: Add colorMode to EpisodeLogOpts**

In `src/store/useAppStore.ts`, find `interface EpisodeLogOpts` and add the field:

```ts
interface EpisodeLogOpts {
  watchedAt?: string
  watchNotes?: string
  rating?: number
  reviewText?: string
  colorMode?: 'bw' | 'color'
}
```

- [ ] **Step 2: Write colorMode into local watch event state**

In the `logEpisode` action, find where `updated.watchEvents` is built. Add `colorMode`:

```ts
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
```

- [ ] **Step 3: Write colorMode into local review state**

In the same `logEpisode` action, find where `updated.reviews` is built. Add `colorMode`:

```ts
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
```

- [ ] **Step 4: Verify TypeScript compiles**

```bash
npm run build
```

Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add src/store/useAppStore.ts
git commit -m "feat(store): thread colorMode through logEpisode action"
```

---

### Task 4: SpiderNoirModeModal component

**Files:**
- Create: `src/components/SpiderNoirModeModal.tsx`

**Interfaces:**
- Consumes: nothing from earlier tasks (standalone component)
- Produces:
  ```ts
  // exported from src/components/SpiderNoirModeModal.tsx
  export function SpiderNoirModeModal(props: {
    open: boolean
    onSelect: (mode: 'bw' | 'color') => void
    onSkip: () => void
  }): JSX.Element
  ```

- [ ] **Step 1: Create the component file**

Create `src/components/SpiderNoirModeModal.tsx`:

```tsx
import * as DialogPrimitive from '@radix-ui/react-dialog'

interface SpiderNoirModeModalProps {
  open: boolean
  onSelect: (mode: 'bw' | 'color') => void
  onSkip: () => void
}

export function SpiderNoirModeModal({ open, onSelect, onSkip }: SpiderNoirModeModalProps) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={(o) => { if (!o) onSkip() }}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 10000,
            background: 'rgba(11, 9, 7, 0.92)',
            backdropFilter: 'blur(8px)',
            animation: 'spider-noir-fade-in 300ms ease',
          }}
        />
        <DialogPrimitive.Content
          aria-describedby={undefined}
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 10001,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            padding: '24px',
          }}
          onOpenAutoFocus={(e) => e.preventDefault()}
        >
          <DialogPrimitive.Title
            style={{
              fontFamily: 'var(--serif)',
              fontSize: 'clamp(20px, 5vw, 28px)',
              color: 'var(--paper)',
              marginBottom: '8px',
              textAlign: 'center',
              letterSpacing: '-0.01em',
            }}
          >
            How did you experience this?
          </DialogPrimitive.Title>

          <p
            style={{
              fontFamily: 'var(--mono)',
              fontSize: '11px',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              color: 'var(--paper-faint)',
              marginBottom: '32px',
              textAlign: 'center',
            }}
          >
            Spider-Man: Noir
          </p>

          {/* Choice cards */}
          <div style={{ display: 'flex', gap: '16px', width: '100%', maxWidth: '480px' }}>
            {/* B&W card */}
            <button
              onClick={() => onSelect('bw')}
              style={{
                flex: 1,
                border: '1px solid rgba(255,255,255,0.15)',
                borderRadius: '12px',
                padding: '24px 16px',
                cursor: 'pointer',
                transition: 'transform 0.18s, border-color 0.18s',
                filter: 'grayscale(1)',
                background: 'linear-gradient(160deg, rgba(80,80,80,0.3), rgba(20,20,20,0.5))',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '12px',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.transform = 'translateY(-3px)'
                e.currentTarget.style.borderColor = 'rgba(255,255,255,0.4)'
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.transform = ''
                e.currentTarget.style.borderColor = 'rgba(255,255,255,0.15)'
              }}
            >
              <span style={{ fontSize: '36px', lineHeight: 1, color: '#ccc' }}>◐</span>
              <div style={{ textAlign: 'center' }}>
                <div
                  style={{
                    fontFamily: 'var(--serif)',
                    fontSize: '15px',
                    color: '#e0e0e0',
                    marginBottom: '4px',
                    fontVariationSettings: '"opsz" 24',
                  }}
                >
                  Authentic
                </div>
                <div
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: '10px',
                    color: '#999',
                    letterSpacing: '0.1em',
                    textTransform: 'uppercase',
                  }}
                >
                  Black & White
                </div>
              </div>
            </button>

            {/* Color card */}
            <button
              onClick={() => onSelect('color')}
              style={{
                flex: 1,
                border: '1px solid rgba(233, 178, 102, 0.3)',
                borderRadius: '12px',
                padding: '24px 16px',
                cursor: 'pointer',
                transition: 'transform 0.18s, border-color 0.18s, box-shadow 0.18s',
                background: 'linear-gradient(160deg, rgba(192, 57, 43, 0.35), rgba(233, 178, 102, 0.2))',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '12px',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.transform = 'translateY(-3px)'
                e.currentTarget.style.borderColor = 'rgba(233, 178, 102, 0.7)'
                e.currentTarget.style.boxShadow = '0 8px 32px -8px rgba(233, 178, 102, 0.4)'
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.transform = ''
                e.currentTarget.style.borderColor = 'rgba(233, 178, 102, 0.3)'
                e.currentTarget.style.boxShadow = ''
              }}
            >
              <span style={{ fontSize: '36px', lineHeight: 1, color: 'var(--amber)' }}>◈</span>
              <div style={{ textAlign: 'center' }}>
                <div
                  style={{
                    fontFamily: 'var(--serif)',
                    fontSize: '15px',
                    color: 'var(--amber)',
                    marginBottom: '4px',
                    fontVariationSettings: '"opsz" 24',
                  }}
                >
                  True-Hue
                </div>
                <div
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: '10px',
                    color: 'var(--amber-deep)',
                    letterSpacing: '0.1em',
                    textTransform: 'uppercase',
                  }}
                >
                  Full Color
                </div>
              </div>
            </button>
          </div>

          {/* Skip */}
          <button
            onClick={onSkip}
            style={{
              marginTop: '24px',
              fontFamily: 'var(--mono)',
              fontSize: '11px',
              color: 'var(--paper-faint)',
              background: 'none',
              border: 'none',
              cursor: 'pointer',
              letterSpacing: '0.08em',
              transition: 'color 0.15s',
            }}
            onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--paper-dim)')}
            onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--paper-faint)')}
          >
            not now
          </button>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
```

- [ ] **Step 2: Add fade-in keyframe to index.css**

In `src/index.css`, at the bottom of the `@keyframes` section (after the existing keyframes like `grain`, `drift`, `flicker`), add:

```css
@keyframes spider-noir-fade-in {
  from { opacity: 0; }
  to   { opacity: 1; }
}
```

- [ ] **Step 3: Temporarily verify modal renders**

In `src/components/TitleDetailDrawer.tsx`, at the very top of the main export component's return JSX, temporarily add:

```tsx
<SpiderNoirModeModal
  open={true}
  onSelect={(m) => console.log('mode:', m)}
  onSkip={() => console.log('skip')}
/>
```

Run `npm run dev`, open any title's drawer. The modal should appear full-screen with two cards. Click B&W → console logs `mode: bw`. Click color → console logs `mode: color`. Click "not now" → console logs `skip`.

Remove the temporary addition before committing.

- [ ] **Step 4: Commit**

```bash
git add src/components/SpiderNoirModeModal.tsx src/index.css
git commit -m "feat(ui): add SpiderNoirModeModal component with bw/color choice cards"
```

---

### Task 5: EpisodePanel wiring — pre-save modal + isSpiderNoir prop + history badges

This task hooks the modal into the episode log flow and adds the `◐ B&W` / `◈ Color` badges to the history display.

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx`

**Interfaces:**
- Consumes:
  - `SpiderNoirModeModal(props: { open, onSelect, onSkip })` from Task 4
  - `EpisodeLogOpts.colorMode` from Task 3
  - `EpisodeWatchEvent.colorMode` and `EpisodeReview.colorMode` from Task 2
- Produces:
  - `EpisodePanelProps.isSpiderNoir: boolean`
  - `EpisodeRowProps.isSpiderNoir: boolean`
  - `TVSeriesSectionProps.isSpiderNoir: boolean`

- [ ] **Step 1: Add SPIDER_NOIR_TMDB_ID constant**

Near the top of `src/components/TitleDetailDrawer.tsx`, after the imports, add:

```ts
const SPIDER_NOIR_TMDB_ID = 242484
```

- [ ] **Step 2: Add isSpiderNoir to EpisodePanelProps**

Find `interface EpisodePanelProps` and add the field:

```ts
interface EpisodePanelProps {
  episode: Episode
  season: Season
  titleId: string
  isSharedView: boolean
  isSpiderNoir: boolean
}
```

- [ ] **Step 3: Wire up pre-save modal in EpisodePanel**

Find `function EpisodePanel({ episode, season, titleId, isSharedView }: EpisodePanelProps)` and replace the full function signature and its `handleSubmit` logic:

```ts
function EpisodePanel({ episode, season, titleId, isSharedView, isSpiderNoir }: EpisodePanelProps) {
  const logEpisode = useAppStore((s) => s.logEpisode)
  const [log, setLog] = useState<EpLogState>(EMPTY_EP_LOG)
  const [showForm, setShowForm] = useState(false)
  const [pendingLog, setPendingLog] = useState<EpLogState | null>(null)
  const [showNoirModal, setShowNoirModal] = useState(false)

  const avg = avgEpisodeRating(episode)
  const watched = episode.watchEvents.length > 0

  function doSave(epLog: EpLogState, colorMode?: 'bw' | 'color') {
    if (!epLog.includeWatch && epLog.rating === 0 && !epLog.reviewText.trim()) return
    logEpisode(titleId, season.seasonNumber, episode.episodeNumber, {
      watchedAt: epLog.includeWatch ? epLog.watchedAt : undefined,
      watchNotes: epLog.includeWatch ? epLog.watchNotes : undefined,
      rating: epLog.rating > 0 ? epLog.rating : undefined,
      reviewText: epLog.reviewText.trim() || undefined,
      colorMode,
    })
    setLog(EMPTY_EP_LOG)
    setShowForm(false)
  }

  function handleSubmit() {
    if (!log.includeWatch && log.rating === 0 && !log.reviewText.trim()) return
    if (isSpiderNoir) {
      setPendingLog(log)
      setShowNoirModal(true)
    } else {
      doSave(log)
    }
  }

  function handleNoirSelect(mode: 'bw' | 'color') {
    setShowNoirModal(false)
    if (pendingLog) doSave(pendingLog, mode)
    setPendingLog(null)
  }

  function handleNoirSkip() {
    setShowNoirModal(false)
    if (pendingLog) doSave(pendingLog)
    setPendingLog(null)
  }
```

- [ ] **Step 4: Render modal in EpisodePanel JSX**

Inside the `return (...)` of `EpisodePanel`, add the modal just before the closing `</div>` of the component:

```tsx
<SpiderNoirModeModal
  open={showNoirModal}
  onSelect={handleNoirSelect}
  onSkip={handleNoirSkip}
/>
```

Import `SpiderNoirModeModal` at the top of `TitleDetailDrawer.tsx`:

```ts
import { SpiderNoirModeModal } from './SpiderNoirModeModal'
```

- [ ] **Step 5: Add colorMode badges to watch event history**

In `EpisodePanel`'s JSX, find the "Watch events" display section (around the `episode.watchEvents.map((we) =>` block). After the date span and optional notes, add the badge:

```tsx
{episode.watchEvents.map((we) => (
  <div key={we.id} className="font-mono" style={{ color: 'var(--amber)', fontSize: '11px' }}>
    {fmtDate(we.watchedAt)}
    {we.colorMode && (
      <span
        className="font-mono ml-1.5 px-1 rounded"
        style={{
          fontSize: '9px',
          letterSpacing: '0.06em',
          background: we.colorMode === 'bw' ? 'rgba(200,200,200,0.12)' : 'rgba(233,178,102,0.15)',
          color: we.colorMode === 'bw' ? '#aaa' : 'var(--amber)',
          border: `1px solid ${we.colorMode === 'bw' ? 'rgba(200,200,200,0.2)' : 'rgba(233,178,102,0.3)'}`,
        }}
      >
        {we.colorMode === 'bw' ? '◐ B&W' : '◈ Color'}
      </span>
    )}
    {we.notes && (
      <div className="font-sans italic mt-0.5" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>
        {we.notes}
      </div>
    )}
  </div>
))}
```

- [ ] **Step 6: Add colorMode badges to review history**

In the same `EpisodePanel` JSX, find the `episode.reviews.map((rv) =>` block. After the review text, add a badge:

```tsx
{episode.reviews.map((rv) => (
  <div key={rv.id}>
    <div className="font-sans italic leading-snug" style={{ color: 'var(--paper-dim)', fontSize: '11px' }}>
      "{rv.reviewText}"
    </div>
    <div className="font-mono mt-0.5 flex items-center gap-1.5" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>
      {fmtDateTime(rv.reviewedAt)}
      {rv.colorMode && (
        <span
          className="font-mono px-1 rounded"
          style={{
            fontSize: '9px',
            letterSpacing: '0.06em',
            background: rv.colorMode === 'bw' ? 'rgba(200,200,200,0.12)' : 'rgba(233,178,102,0.15)',
            color: rv.colorMode === 'bw' ? '#aaa' : 'var(--amber)',
            border: `1px solid ${rv.colorMode === 'bw' ? 'rgba(200,200,200,0.2)' : 'rgba(233,178,102,0.3)'}`,
          }}
        >
          {rv.colorMode === 'bw' ? '◐ B&W' : '◈ Color'}
        </span>
      )}
    </div>
  </div>
))}
```

- [ ] **Step 7: Add isSpiderNoir to EpisodeRowProps and thread down**

Find `interface EpisodeRowProps` and add:

```ts
interface EpisodeRowProps {
  episode: Episode
  season: Season
  titleId: string
  expanded: boolean
  onToggle: () => void
  isSharedView: boolean
  isSpiderNoir: boolean
}
```

Find `function EpisodeRow(...)` and update the signature to destructure `isSpiderNoir`, then pass it to `EpisodePanel`:

```tsx
function EpisodeRow({ episode, season, titleId, expanded, onToggle, isSharedView, isSpiderNoir }: EpisodeRowProps) {
  // ... existing body ...

  {expanded && (
    <EpisodePanel
      episode={episode}
      season={season}
      titleId={titleId}
      isSharedView={isSharedView}
      isSpiderNoir={isSpiderNoir}
    />
  )}
```

- [ ] **Step 8: Add isSpiderNoir to TVSeriesSectionProps and thread down**

Find `interface TVSeriesSectionProps` and add:

```ts
interface TVSeriesSectionProps {
  titleId: string
  seasons: Season[]
  isSharedView: boolean
  isSpiderNoir: boolean
}
```

Find `function TVSeriesSection(...)`, update the destructure, then pass `isSpiderNoir` to each `EpisodeRow` call. The `EpisodeRow` is rendered inside `TVSeriesSection`'s season episode list — find that render and add the prop:

```tsx
<EpisodeRow
  key={ep.id}
  episode={ep}
  season={season}
  titleId={titleId}
  expanded={expandedEpId === ep.id}
  onToggle={() => setExpandedEpId(expandedEpId === ep.id ? null : ep.id)}
  isSharedView={isSharedView}
  isSpiderNoir={isSpiderNoir}
/>
```

- [ ] **Step 9: Compute isSpiderNoir at the TitleDetailDrawer call site and pass to TVSeriesSection**

Find the `<TVSeriesSection>` render in the main `TitleDetailDrawer` component body (around line 1074). Replace it with:

```tsx
<TVSeriesSection
  titleId={title.id}
  seasons={title.seasons}
  isSharedView={isSharedView}
  isSpiderNoir={title.tmdbId === SPIDER_NOIR_TMDB_ID}
/>
```

- [ ] **Step 10: Verify TypeScript compiles**

```bash
npm run build
```

Expected: no errors. Fix any missing prop type errors.

- [ ] **Step 11: Manual test**

Run `npm run dev`. Add Spider-Man: Noir to the library (or check that it exists). Open its detail drawer, expand an episode, click "Log watch event, rating, or review", fill in a date, and click Save. The `SpiderNoirModeModal` should appear. Select "Authentic Black & White". The form should close. Expand the same episode again — the watch event should show a `◐ B&W` pill next to the date.

Repeat with a non–Spider Noir title: clicking Save should save directly without showing the modal.

- [ ] **Step 12: Commit**

```bash
git add src/components/TitleDetailDrawer.tsx
git commit -m "feat(ui): wire SpiderNoirModeModal into episode log flow; add bw/color badges to history"
```

---

### Task 6: App-wide CSS transformation — body class effect + CSS rules

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx`
- Modify: `src/index.css`

**Interfaces:**
- Consumes:
  - `EpisodeWatchEvent.colorMode` and `EpisodeReview.colorMode` from Task 2
  - `SPIDER_NOIR_TMDB_ID` constant from Task 5

- [ ] **Step 1: Add getSpiderNoirActiveMode helper**

In `src/components/TitleDetailDrawer.tsx`, add this helper function near the top (after the `SPIDER_NOIR_TMDB_ID` constant):

```ts
function getSpiderNoirActiveMode(title: Title): 'bw' | 'color' | null {
  const allEvents: Array<{ date: string; colorMode: 'bw' | 'color' }> = []
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) {
      for (const we of ep.watchEvents) {
        if (we.colorMode) allEvents.push({ date: we.watchedAt, colorMode: we.colorMode })
      }
      for (const rv of ep.reviews) {
        if (rv.colorMode) allEvents.push({ date: rv.reviewedAt, colorMode: rv.colorMode })
      }
    }
  }
  if (allEvents.length === 0) return null
  allEvents.sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())
  return allEvents[0].colorMode
}
```

- [ ] **Step 2: Add the body class useEffect**

Inside the main `TitleDetailDrawer` component function, after the existing `const title = ...` and `const isSharedView = ...` lines, add:

```ts
const isSpiderNoir = title?.tmdbId === SPIDER_NOIR_TMDB_ID
const activeSpiderNoirMode = isSpiderNoir && title ? getSpiderNoirActiveMode(title) : null

useEffect(() => {
  document.body.classList.remove('spider-noir-bw', 'spider-noir-color')
  if (isSpiderNoir && activeSpiderNoirMode) {
    document.body.classList.add(
      activeSpiderNoirMode === 'bw' ? 'spider-noir-bw' : 'spider-noir-color'
    )
  }
  return () => {
    document.body.classList.remove('spider-noir-bw', 'spider-noir-color')
  }
}, [isSpiderNoir, activeSpiderNoirMode])
```

Make sure `useEffect` is imported at the top of the file (it should already be).

- [ ] **Step 3: Add B&W mode CSS**

In `src/index.css`, at the end of the file (after the series graph styles, before or after the `@media (prefers-reduced-motion)` block), add:

```css
/* ════════════════════════════════════════════════════════════════════════
   SPIDER NOIR VIEWING MODE — app-wide transformation
   Active while Spider-Man: Noir's detail drawer is open.
   Body class is applied/removed by TitleDetailDrawer's useEffect.
   ════════════════════════════════════════════════════════════════════════ */

body.spider-noir-bw #root {
  filter: grayscale(1) contrast(1.15);
  transition: filter 600ms ease;
}

body.spider-noir-bw .grain {
  opacity: 0.10;
}

body.spider-noir-color #root {
  filter: saturate(2.2) contrast(1.05) hue-rotate(-8deg);
  transition: filter 600ms ease;
}

body.spider-noir-color .btn-amber {
  animation: spider-noir-shimmer 3s ease-in-out infinite;
}

body.spider-noir-color .projector-beam {
  animation: flicker 7s ease-in-out infinite,
             spider-noir-beam-hue 8s ease-in-out infinite;
}

@keyframes spider-noir-shimmer {
  0%, 100% { box-shadow: 0 6px 20px -8px rgba(233, 178, 102, 0.7); }
  50%       { box-shadow: 0 6px 28px -6px rgba(233, 100, 40, 0.95); }
}

@keyframes spider-noir-beam-hue {
  0%, 100% { filter: blur(30px) hue-rotate(0deg); }
  33%       { filter: blur(30px) hue-rotate(25deg); }
  66%       { filter: blur(30px) hue-rotate(-90deg); }
}
```

Note: `.grain` opacity defaults to `0.05`; the B&W rule doubles it to `0.10` for a heavier silver-nitrate feel.

- [ ] **Step 4: Verify TypeScript compiles**

```bash
npm run build
```

Expected: no errors.

- [ ] **Step 5: Manual test — B&W transformation**

Run `npm run dev`. Open Spider-Man: Noir's detail drawer (you should have at least one episode logged with `◐ B&W` from Task 5 testing). The entire app should desaturate to grayscale. Close the drawer — the app returns to normal immediately (transition handles the fade). Open any other title's drawer — no transformation.

- [ ] **Step 6: Manual test — Color transformation**

Log another episode of Spider-Man: Noir, this time selecting "◈ True-Hue Full Color". Re-open the drawer. The app should show boosted saturation — ambers become vivid orange-gold, any blues deepen, the projector beam cycles through amber → crimson → violet over ~8 seconds.

- [ ] **Step 7: Final build check**

```bash
npm run build && npm run lint
```

Expected: no errors, no lint warnings.

- [ ] **Step 8: Commit**

```bash
git add src/components/TitleDetailDrawer.tsx src/index.css
git commit -m "feat(ui): app-wide grayscale/vivid-color transformation when Spider Noir drawer is open"
```

---

## Final Verification Checklist

- [ ] `npm run build` passes with zero TypeScript errors
- [ ] `npm run lint` passes clean
- [ ] Spider Noir episode log → Save → modal appears with two cards
- [ ] Selecting B&W: modal closes, episode history shows `◐ B&W` badge, app goes grayscale while drawer is open
- [ ] Selecting Color: modal closes, episode history shows `◈ Color` badge, app shows vivid saturation + beam animation
- [ ] Selecting "not now": modal closes, episode saves without colorMode, no transformation activates
- [ ] Closing the Spider Noir drawer: transformation resets to default immediately
- [ ] Opening any other title's drawer: no transformation, body class is absent
- [ ] Review logs also show colorMode badges when mode was set at review-save time
