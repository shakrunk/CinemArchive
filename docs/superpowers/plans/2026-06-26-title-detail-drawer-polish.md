# TitleDetailDrawer Polish Pass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Polish three clusters of rough edges in `TitleDetailDrawer.tsx` — timestamp precision, silent-save feedback, and adaptive episode history layout.

**Architecture:** All changes are in a single component file. No new dependencies, no schema changes, no store changes. Each task is a self-contained diff with a lint+build gate.

**Tech Stack:** React 18, TypeScript, Tailwind CSS, Vite, lucide-react

## Global Constraints

- No test runner — verification is `npm run lint` + `npm run build` (tsc + vite) + visual check in `npm run dev`
- No new npm packages
- Follow existing inline-style conventions for colours and font sizes (`var(--amber)`, `var(--paper-faint)`, pixel values as strings)
- `Check` icon from `lucide-react` is the only new import needed

---

### Task 1: Move timestamp helpers to module level + make `fmtDateTime` show time-of-day + fix movie "Last Seen"

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx`

**Context:**
`fmtDate` and `fmtDateTime` are currently defined inside `EpisodePanel` (lines 377–380) and are byte-for-byte identical. `fmtDate` is fine as-is; `fmtDateTime` should surface the time component present in `ratedAt`/`reviewedAt` ISO strings (e.g. `"2026-01-10T21:30:00Z"`). The movie "Last Seen" stat (inside the main `TitleDetailDrawer` component) calls `.getFullYear()` which should be `fmtDate` instead.

Moving both helpers to module level makes them available to both `EpisodePanel` and the main component without duplication.

**Interfaces:**
- Produces: module-level `fmtDate(iso: string): string` and `fmtDateTime(iso: string): { date: string; time: string }`

- [ ] **Step 1: Remove the two helpers from inside `EpisodePanel` and add them at module level**

In `TitleDetailDrawer.tsx`, find and delete lines 377–380 (the two identical helpers inside `EpisodePanel`):
```tsx
  const fmtDate = (iso: string) =>
    new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  const fmtDateTime = (iso: string) =>
    new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
```

Add these at module level, just above the `// ─── Main drawer ───` comment (around line 1059):
```tsx
function fmtDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

function fmtDateTime(iso: string): { date: string; time: string } {
  const d = new Date(iso)
  return {
    date: d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }),
    time: d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true }),
  }
}
```

- [ ] **Step 2: Update the rating timestamp call site**

Find the rating map inside `EpisodePanel` (the `episode.ratings.map` block). Replace:
```tsx
<div key={er.id} className="font-mono" style={{ color: 'var(--amber)', fontSize: '11px' }}>
  ★ {er.rating}
  <div className="font-sans mt-0.5" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>
    {fmtDateTime(er.ratedAt)}
  </div>
</div>
```
With:
```tsx
<div key={er.id} className="font-mono" style={{ color: 'var(--amber)', fontSize: '11px' }}>
  ★ {er.rating}
  <div className="mt-0.5">
    <div className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>
      {fmtDateTime(er.ratedAt).date}
    </div>
    <div className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '9px' }}>
      {fmtDateTime(er.ratedAt).time}
    </div>
  </div>
</div>
```

- [ ] **Step 3: Update the review timestamp call site**

Find the review map inside `EpisodePanel` (the `episode.reviews.map` block). Replace:
```tsx
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
```
With:
```tsx
<div className="mt-0.5" style={{ color: 'var(--paper-faint)' }}>
  <div className="font-mono flex items-center gap-1.5" style={{ fontSize: '10px' }}>
    <span>{fmtDateTime(rv.reviewedAt).date}</span>
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
  <div className="font-mono" style={{ fontSize: '9px' }}>
    {fmtDateTime(rv.reviewedAt).time}
  </div>
</div>
```

- [ ] **Step 4: Fix movie "Last Seen" stat**

Inside the main `TitleDetailDrawer` return, find the "Last Seen" stat card (inside `title.type === 'movie'`). Replace:
```tsx
<StatNumber className="text-xl">
  {title.viewings.length > 0
    ? new Date(
        title.viewings
          .slice()
          .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())[0]
          .date
      ).getFullYear()
    : '—'}
</StatNumber>
```
With:
```tsx
<StatNumber className="text-base leading-tight">
  {title.viewings.length > 0
    ? fmtDate(
        title.viewings
          .slice()
          .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())[0]
          .date
      )
    : '—'}
</StatNumber>
```

Note: `text-base` instead of `text-xl` because the full date string (`Jan 10, 2026`) is longer than a bare year; `text-xl` would overflow the stat card on narrow screens.

- [ ] **Step 5: Lint and build**

```bash
npm run lint && npm run build
```
Expected: no errors. TypeScript will catch any remaining `fmtDateTime(x)` calls that still treat the return value as a string (they'd fail with "object is not a valid React child").

- [ ] **Step 6: Visual check**

Run `npm run dev`, open a TV series in the drawer, expand an episode that has a rating and/or review. Confirm:
- Rating timestamp shows two lines: `Jan 10, 2026` then `9:30 PM` below in lighter text
- Review timestamp shows date + colorMode badge on one line, time on the line below
- Open a movie with viewings; the "Last Seen" stat card shows `Jan 10, 2026` matching the timeline below

- [ ] **Step 7: Commit**

```bash
git add src/components/TitleDetailDrawer.tsx
git commit -m "polish(drawer): show time-of-day on rating/review timestamps, fix Last Seen precision"
```

---

### Task 2: Episode log — fresh date on open + save confirmation

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx`

**Context:**
`EMPTY_EP_LOG` is a module-level constant whose `watchedAt` is evaluated at app load time. If the app is open overnight, the pre-filled date is stale. Fix: refresh the date field when the form opens. Separately, `doSave` currently collapses the form silently; add a 1.5 s "✓ Logged" state on the trigger button. Requires adding `Check` to the lucide-react import.

**Interfaces:**
- Consumes: `EpisodePanel` component (Task 1 already complete)

- [ ] **Step 1: Add `Check` to the lucide-react import**

Find the existing lucide-react import line (near the top of the file):
```tsx
import {
  Calendar, Clock, Film, Tv, Plus, FileText, Trash2, Star,
  ChevronDown, ChevronRight, Eye, MessageSquare, RefreshCw, Tag, X,
} from 'lucide-react'
```
Replace with:
```tsx
import {
  Calendar, Check, Clock, Film, Tv, Plus, FileText, Trash2, Star,
  ChevronDown, ChevronRight, Eye, MessageSquare, RefreshCw, Tag, X,
} from 'lucide-react'
```

- [ ] **Step 2: Add `showSaved` state to `EpisodePanel`**

Inside `EpisodePanel`, find the existing `useState` declarations at the top of the function body:
```tsx
  const [log, setLog] = useState<EpLogState>(EMPTY_EP_LOG)
  const [showForm, setShowForm] = useState(false)
  const [pendingLog, setPendingLog] = useState<EpLogState | null>(null)
  const [showNoirModal, setShowNoirModal] = useState(false)
```
Replace with:
```tsx
  const [log, setLog] = useState<EpLogState>(EMPTY_EP_LOG)
  const [showForm, setShowForm] = useState(false)
  const [showSaved, setShowSaved] = useState(false)
  const [pendingLog, setPendingLog] = useState<EpLogState | null>(null)
  const [showNoirModal, setShowNoirModal] = useState(false)
```

- [ ] **Step 3: Update `doSave` to trigger the saved feedback**

Find the `doSave` function inside `EpisodePanel`:
```tsx
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
```
Replace with:
```tsx
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
    setShowSaved(true)
    setTimeout(() => setShowSaved(false), 1500)
  }
```

- [ ] **Step 4: Update the trigger button to refresh the date on open and show saved feedback**

Find the trigger button at the bottom of `EpisodePanel` (the `!showForm` branch):
```tsx
        ) : (
          <button
            onClick={() => setShowForm(true)}
            className="flex items-center gap-1.5 text-xs font-mono transition-colors"
            style={{ color: 'var(--amber-deep)' }}
            onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--amber)')}
            onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--amber-deep)')}
          >
            <Plus className="w-3 h-3" />
            {watched ? 'Add rating, review, or re-watch' : 'Log watch event, rating, or review'}
          </button>
        )
```
Replace with:
```tsx
        ) : (
          <button
            onClick={() => {
              if (showSaved) return
              setLog((l) => ({ ...l, watchedAt: new Date().toISOString().slice(0, 10) }))
              setShowForm(true)
            }}
            className="flex items-center gap-1.5 text-xs font-mono transition-colors"
            style={{ color: showSaved ? 'var(--amber)' : 'var(--amber-deep)' }}
            onMouseEnter={(e) => { if (!showSaved) e.currentTarget.style.color = 'var(--amber)' }}
            onMouseLeave={(e) => { if (!showSaved) e.currentTarget.style.color = 'var(--amber-deep)' }}
          >
            {showSaved ? (
              <>
                <Check className="w-3 h-3" />
                Logged
              </>
            ) : (
              <>
                <Plus className="w-3 h-3" />
                {watched ? 'Add rating, review, or re-watch' : 'Log watch event, rating, or review'}
              </>
            )}
          </button>
        )
```

- [ ] **Step 5: Lint and build**

```bash
npm run lint && npm run build
```
Expected: no errors.

- [ ] **Step 6: Visual check**

Run `npm run dev`, open a TV series, expand an episode. Fill in the log form and save. Confirm:
- The trigger button briefly shows a checkmark and reads "Logged" in solid amber for ~1.5 s
- After 1.5 s it reverts to the normal `+` prompt
- The pre-filled date when opening the form is today's date (not the date the app was loaded)

- [ ] **Step 7: Commit**

```bash
git add src/components/TitleDetailDrawer.tsx
git commit -m "polish(drawer): fix stale episode log date, add save confirmation feedback"
```

---

### Task 3: Movie viewing form — save confirmation

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx`

**Context:**
The movie viewing form's "Save Viewing" button dismisses silently. Apply the same 1.5 s confirmed-state pattern: button reads "✓ Saved", then the form collapses and fields reset. `Check` was already added to the import in Task 2.

- [ ] **Step 1: Add `showMovieSaved` state to the main `TitleDetailDrawer` component**

Inside `TitleDetailDrawer` (the main component function), find the existing `useState` declarations:
```tsx
  const [showLogForm, setShowLogForm] = useState(false)
  const [logDate, setLogDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [logRating, setLogRating] = useState(0)
  const [logNotes, setLogNotes] = useState('')
  const [pendingDeleteTitle, setPendingDeleteTitle] = useState(false)
```
Replace with:
```tsx
  const [showLogForm, setShowLogForm] = useState(false)
  const [logDate, setLogDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [logRating, setLogRating] = useState(0)
  const [logNotes, setLogNotes] = useState('')
  const [showMovieSaved, setShowMovieSaved] = useState(false)
  const [pendingDeleteTitle, setPendingDeleteTitle] = useState(false)
```

- [ ] **Step 2: Update `logViewing` to show saved feedback before closing**

Find the `logViewing` function inside `TitleDetailDrawer`:
```tsx
  function logViewing() {
    if (!title || !logDate) return
    const viewing: Viewing = {
      id: crypto.randomUUID(),
      titleId: title.id,
      date: logDate,
      rating: logRating > 0 ? logRating : undefined,
      notes: logNotes || undefined,
    }
    updateTitle(title.id, {
      viewings: [...title.viewings, viewing],
      status: 'watched',
      rating: logRating > 0 ? logRating : title.rating,
    })
    setShowLogForm(false)
    setLogDate(new Date().toISOString().slice(0, 10))
    setLogRating(0)
    setLogNotes('')
  }
```
Replace with:
```tsx
  function logViewing() {
    if (!title || !logDate) return
    const viewing: Viewing = {
      id: crypto.randomUUID(),
      titleId: title.id,
      date: logDate,
      rating: logRating > 0 ? logRating : undefined,
      notes: logNotes || undefined,
    }
    updateTitle(title.id, {
      viewings: [...title.viewings, viewing],
      status: 'watched',
      rating: logRating > 0 ? logRating : title.rating,
    })
    setShowMovieSaved(true)
    setTimeout(() => {
      setShowMovieSaved(false)
      setShowLogForm(false)
      setLogDate(new Date().toISOString().slice(0, 10))
      setLogRating(0)
      setLogNotes('')
    }, 1500)
  }
```

- [ ] **Step 3: Update the "Save Viewing" button**

Find the save button inside the `showLogForm` block:
```tsx
                    <Button
                      className="flex-1 bg-amber hover:bg-amber-muted text-void font-sans font-medium"
                      onClick={logViewing}
                    >
                      <Star className="w-4 h-4 mr-2" />
                      Save Viewing
                    </Button>
```
Replace with:
```tsx
                    <Button
                      className="flex-1 bg-amber hover:bg-amber-muted text-void font-sans font-medium"
                      onClick={logViewing}
                      disabled={showMovieSaved}
                    >
                      {showMovieSaved ? (
                        <>
                          <Check className="w-4 h-4 mr-2" />
                          Saved
                        </>
                      ) : (
                        <>
                          <Star className="w-4 h-4 mr-2" />
                          Save Viewing
                        </>
                      )}
                    </Button>
```

- [ ] **Step 4: Lint and build**

```bash
npm run lint && npm run build
```
Expected: no errors.

- [ ] **Step 5: Visual check**

Run `npm run dev`, open a movie, click "Log a viewing", fill in the form, save. Confirm:
- "Save Viewing" button briefly shows "✓ Saved" (amber, checkmark) for ~1.5 s
- After 1.5 s the form collapses and all fields reset

- [ ] **Step 6: Commit**

```bash
git add src/components/TitleDetailDrawer.tsx
git commit -m "polish(drawer): add save confirmation to movie viewing form"
```

---

### Task 4: Adaptive episode history columns

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx`

**Context:**
The episode history grid always renders all three columns (Watched / Ratings / Reviews) even when only one has content. A freshly-watched episode shows one date and two "—" columns. Render only columns that have data, adjusting the grid class accordingly.

- [ ] **Step 1: Add derived booleans near the top of `EpisodePanel`**

Inside `EpisodePanel`, the existing top-of-function derivations are:
```tsx
  const avg = avgEpisodeRating(episode)
  const watched = episode.watchEvents.length > 0
```
Replace with:
```tsx
  const avg = avgEpisodeRating(episode)
  const watched = episode.watchEvents.length > 0
  const hasRatings = episode.ratings.length > 0
  const hasReviews = episode.reviews.length > 0
  const histCols = [watched, hasRatings, hasReviews].filter(Boolean).length
```

- [ ] **Step 2: Update the history grid to use adaptive columns and skip empty ones**

Find the history grid block (the `{(episode.watchEvents.length > 0 || ...` conditional):
```tsx
      {(episode.watchEvents.length > 0 || episode.ratings.length > 0 || episode.reviews.length > 0) && (
        <div className="grid grid-cols-3 gap-2 text-xs">
          {/* Watch events */}
          <div>
            <div ...>Watched</div>
            {episode.watchEvents.length === 0 ? (
              <span style={{ color: 'var(--paper-faint)' }}>—</span>
            ) : (
              episode.watchEvents.map(...)
            )}
          </div>

          {/* Ratings */}
          <div>
            <div ...>Ratings</div>
            {episode.ratings.length === 0 ? (
              <span style={{ color: 'var(--paper-faint)', fontSize: '11px' }}>—</span>
            ) : (
              episode.ratings.map(...)
            )}
            ...
          </div>

          {/* Reviews */}
          <div>
            <div ...>Reviews</div>
            {episode.reviews.length === 0 ? (
              <span style={{ color: 'var(--paper-faint)', fontSize: '11px' }}>—</span>
            ) : (
              episode.reviews.map(...)
            )}
          </div>
        </div>
      )}
```

Make two targeted changes to this block:

**2a.** Change the outer grid class from `"grid grid-cols-3 gap-2 text-xs"` to use `histCols`:
```tsx
        <div className={cn('grid gap-2 text-xs', histCols === 1 ? 'grid-cols-1' : histCols === 2 ? 'grid-cols-2' : 'grid-cols-3')}>
```

**2b.** Wrap each column `<div>` in a conditional so empty columns don't render. The three columns become:

```tsx
          {/* Watch events — only when watched */}
          {watched && (
            <div>
              <div
                className="font-mono mb-1.5"
                style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
              >
                Watched
              </div>
              {episode.watchEvents.map((we) => (
                /* existing watch event JSX — unchanged */
              ))}
            </div>
          )}

          {/* Ratings — only when rated */}
          {hasRatings && (
            <div>
              <div
                className="font-mono mb-1.5"
                style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
              >
                Ratings
              </div>
              {episode.ratings.map((er) => (
                /* existing rating JSX — already updated in Task 1 */
              ))}
              {episode.ratings.length > 1 && avg !== null && (
                <div className="font-mono mt-1" style={{ color: 'var(--amber-deep)', fontSize: '10px' }}>
                  avg ★ {avg.toFixed(1)}
                </div>
              )}
            </div>
          )}

          {/* Reviews — only when reviewed */}
          {hasReviews && (
            <div>
              <div
                className="font-mono mb-1.5"
                style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
              >
                Reviews
              </div>
              {episode.reviews.map((rv) => (
                /* existing review JSX — already updated in Task 1 */
              ))}
            </div>
          )}
```

The key changes: remove the `episode.X.length === 0 ? <span>—</span> : ...` branches (the whole column is skipped when empty), and add `{watched && ...}`, `{hasRatings && ...}`, `{hasReviews && ...}` guards. The inner JSX for each column is otherwise identical to what already exists.

- [ ] **Step 3: Lint and build**

```bash
npm run lint && npm run build
```
Expected: no errors.

- [ ] **Step 4: Visual check**

Run `npm run dev`. Open a TV series:
- Expand an episode that has only a watch event: confirm only the "Watched" column renders (full width, no "—" siblings)
- Expand an episode that has watch + rating: confirm two columns
- Expand an episode that has watch + rating + review: confirm three columns (same as before)

- [ ] **Step 5: Commit**

```bash
git add src/components/TitleDetailDrawer.tsx
git commit -m "polish(drawer): adaptive episode history columns — hide empty record types"
```

---

### Task 5: Re-watch count badge on EpisodeRow

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx`

**Context:**
`EpisodeRow` shows a single Eye icon whenever `episode.watchEvents.length > 0`, regardless of how many times the episode has been watched. When `watchEvents.length > 1`, append a `×N` count beside the icon.

- [ ] **Step 1: Update the Eye icon block in `EpisodeRow`**

Inside `EpisodeRow`, find:
```tsx
          {watched && (
            <Eye className="w-3 h-3" style={{ color: 'var(--amber)', opacity: 0.8 }} />
          )}
```
Replace with:
```tsx
          {watched && (
            <span className="flex items-center gap-0.5">
              <Eye className="w-3 h-3" style={{ color: 'var(--amber)', opacity: 0.8 }} />
              {episode.watchEvents.length > 1 && (
                <span className="font-mono" style={{ fontSize: '10px', color: 'var(--amber)', opacity: 0.8 }}>
                  ×{episode.watchEvents.length}
                </span>
              )}
            </span>
          )}
```

- [ ] **Step 2: Lint and build**

```bash
npm run lint && npm run build
```
Expected: no errors.

- [ ] **Step 3: Visual check**

Run `npm run dev`. Open a TV series where at least one episode has multiple watch events. In the episode list, confirm:
- Episodes watched once show only the Eye icon (no count)
- Episodes watched more than once show `👁 ×3` (or the appropriate count) in amber beside the icon

- [ ] **Step 4: Commit**

```bash
git add src/components/TitleDetailDrawer.tsx
git commit -m "polish(drawer): show re-watch count badge on episode rows"
```

---

## Self-Review

**Spec coverage:**
- ✅ `fmtDate` stays unchanged — Task 1
- ✅ `fmtDateTime` returns `{ date, time }`, shows two-line timestamp — Task 1
- ✅ Movie "Last Seen" uses `fmtDate` — Task 1
- ✅ Stale episode log date fixed on form open — Task 2
- ✅ Episode save confirmation "✓ Logged" — Task 2
- ✅ Movie save confirmation "✓ Saved" — Task 3
- ✅ Adaptive history columns — Task 4
- ✅ Re-watch count badge — Task 5

**Placeholder scan:** None found.

**Type consistency:**
- `fmtDateTime` returns `{ date: string; time: string }` — used as `.date` and `.time` at both call sites in Task 1 ✅
- `showSaved` / `showMovieSaved` — boolean state, consistent across declaration and usage ✅
- `histCols` — number 1/2/3, used in ternary for `grid-cols-*` class ✅
- `Check` import added in Task 2, used in Tasks 2 and 3 ✅
