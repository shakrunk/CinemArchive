# Delete Watch Event Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add inline two-click delete for both movie viewings (ViewingTimeline) and TV episode watch events (EpisodePanel), hidden in shared view.

**Architecture:** Optimistic store actions update Zustand state immediately and fire async DB deletes; UI shows a per-entry pending-delete state that replaces the entry with a compact [Delete forever] / [Cancel] confirm bar on first click.

**Tech Stack:** React + TypeScript, Zustand, Supabase (supabase-js), Tailwind CSS, lucide-react

## Global Constraints

- No automated test runner — verification is `npm run lint` + manual dev server smoke test
- Optimistic UI: store updates first, DB call is fire-and-forget with `console.error` on failure (matching all existing patterns in the codebase)
- Hidden in shared view: any delete UI is gated by `isSharedView` prop (already threaded through both surfaces)
- `Trash2` icon from lucide-react is already imported in `TitleDetailDrawer.tsx` — do not add a duplicate import
- CSS custom properties: `--ember: #d76a49` (destructive red), `--paper-faint` (muted text), `--amber` (highlight)
- No new files — all changes are in the three existing files listed below

---

## File Map

| File | Change |
|---|---|
| `src/lib/db.ts` | Add `deleteViewingFromDb` and `deleteEpisodeWatchEventFromDb` |
| `src/store/useAppStore.ts` | Add `removeViewing` and `deleteEpisodeWatchEvent` actions |
| `src/components/TitleDetailDrawer.tsx` | Add delete UI to `ViewingTimeline` and `EpisodePanel` |

---

### Task 1: DB delete functions

**Files:**
- Modify: `src/lib/db.ts` (append to end of file)

**Interfaces:**
- Produces:
  - `deleteViewingFromDb(userId: string, viewingId: string): Promise<void>`
  - `deleteEpisodeWatchEventFromDb(userId: string, watchEventId: string): Promise<void>`

- [ ] **Step 1: Append the two DB functions to the end of `src/lib/db.ts`**

Add after the last existing export (`upsertEpisodeCrewInDb`):

```typescript
export async function deleteViewingFromDb(userId: string, viewingId: string): Promise<void> {
  if (!supabase) return
  const { error } = await supabase
    .from('viewings')
    .delete()
    .eq('id', viewingId)
    .eq('user_id', userId)
  if (error) {
    console.error('Error deleting viewing:', error)
    throw error
  }
}

export async function deleteEpisodeWatchEventFromDb(userId: string, watchEventId: string): Promise<void> {
  if (!supabase) return
  const { error } = await supabase
    .from('episode_watch_events')
    .delete()
    .eq('id', watchEventId)
    .eq('user_id', userId)
  if (error) {
    console.error('Error deleting episode watch event:', error)
    throw error
  }
}
```

- [ ] **Step 2: Verify no type errors**

```bash
npm run lint
```

Expected: no new errors.

- [ ] **Step 3: Commit**

```bash
git add src/lib/db.ts
git commit -m "feat(db): add deleteViewingFromDb and deleteEpisodeWatchEventFromDb"
```

---

### Task 2: Store actions

**Files:**
- Modify: `src/store/useAppStore.ts`

**Interfaces:**
- Consumes:
  - `deleteViewingFromDb(userId: string, viewingId: string): Promise<void>` (from Task 1)
  - `deleteEpisodeWatchEventFromDb(userId: string, watchEventId: string): Promise<void>` (from Task 1)
- Produces:
  - `removeViewing(titleId: string, viewingId: string): void`
  - `deleteEpisodeWatchEvent(titleId: string, seasonNumber: number, episodeNumber: number, watchEventId: string): void`

- [ ] **Step 1: Update the import line at the top of `src/store/useAppStore.ts`**

Find the existing import (line 6):
```typescript
import { fetchUserLibrary, fetchSharedLibrary, insertTitleToDb, updateTitleInDb, deleteTitleFromDb, logEpisodeToDb } from '../lib/db'
```

Replace with:
```typescript
import { fetchUserLibrary, fetchSharedLibrary, insertTitleToDb, updateTitleInDb, deleteTitleFromDb, logEpisodeToDb, deleteViewingFromDb, deleteEpisodeWatchEventFromDb } from '../lib/db'
```

- [ ] **Step 2: Add the two new actions to the `LibrarySlice` interface**

Find the `LibrarySlice` interface (around line 37). Add the two new action signatures after `logEpisode`:

```typescript
  logEpisode: (titleId: string, seasonNumber: number, episodeNumber: number, opts: EpisodeLogOpts) => void
  removeViewing: (titleId: string, viewingId: string) => void
  deleteEpisodeWatchEvent: (titleId: string, seasonNumber: number, episodeNumber: number, watchEventId: string) => void
```

- [ ] **Step 3: Implement `removeViewing` in the store body**

In the store `(set, get) => ({` block, after the closing of the `logEpisode` implementation (around line 324), add:

```typescript
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
```

- [ ] **Step 4: Implement `deleteEpisodeWatchEvent` in the store body**

Directly after `removeViewing`, add:

```typescript
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
```

- [ ] **Step 5: Verify no type errors**

```bash
npm run lint
```

Expected: no new errors.

- [ ] **Step 6: Commit**

```bash
git add src/store/useAppStore.ts
git commit -m "feat(store): add removeViewing and deleteEpisodeWatchEvent actions"
```

---

### Task 3: ViewingTimeline delete UI

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx` — `ViewingTimeline` component and its call site

**Interfaces:**
- Consumes: `removeViewing(titleId: string, viewingId: string): void` from store (Task 2)
- Produces: updated `ViewingTimeline` component with inline delete confirm

- [ ] **Step 1: Update `ViewingTimeline` props interface and add pending-delete state**

Find the `ViewingTimeline` function signature (around line 57):
```typescript
function ViewingTimeline({ viewings }: { viewings: Viewing[] }) {
```

Replace with:
```typescript
function ViewingTimeline({
  viewings,
  onDeleteViewing,
  isSharedView,
}: {
  viewings: Viewing[]
  onDeleteViewing?: (viewingId: string) => void
  isSharedView?: boolean
}) {
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null)
```

> Note: `useState` is already imported at the top of the file — no import change needed.

- [ ] **Step 2: Add the inline confirm UI to each viewing entry**

The `viewings.map` block currently renders (lines 72–97):
```typescript
          .map((v) => (
            <div key={v.id} className="relative">
              <div className="absolute -left-[18px] top-1 w-3 h-3 rounded-full bg-amber/70 border-2 border-void" />
              <div className="bg-secondary/50 rounded-lg p-3">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-mono text-xs text-amber">
                    {new Date(v.date).toLocaleDateString('en-US', {
                      month: 'short', day: 'numeric', year: 'numeric',
                    })}
                  </span>
                  {v.rating && (
                    <span className="font-mono text-xs text-amber">★ {v.rating}</span>
                  )}
                </div>
                {v.notes && (
                  <p className="text-xs text-muted-foreground font-sans italic leading-relaxed">
                    "{v.notes}"
                  </p>
                )}
              </div>
            </div>
          ))}
```

Replace the entire `.map((v) => (...))` block with:

```typescript
          .map((v) => (
            <div key={v.id} className="relative">
              <div className="absolute -left-[18px] top-1 w-3 h-3 rounded-full bg-amber/70 border-2 border-void" />
              <div className="bg-secondary/50 rounded-lg p-3">
                {pendingDeleteId === v.id ? (
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
                      Remove this viewing?
                    </span>
                    <div className="flex items-center gap-3">
                      <button
                        onClick={() => {
                          onDeleteViewing?.(v.id)
                          setPendingDeleteId(null)
                        }}
                        className="font-mono text-xs transition-opacity hover:opacity-80"
                        style={{ color: 'var(--ember)' }}
                      >
                        Delete forever
                      </button>
                      <button
                        onClick={() => setPendingDeleteId(null)}
                        className="font-mono text-xs transition-opacity hover:opacity-80"
                        style={{ color: 'var(--paper-faint)' }}
                      >
                        Cancel
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="flex items-center justify-between mb-1">
                      <span className="font-mono text-xs text-amber">
                        {new Date(v.date).toLocaleDateString('en-US', {
                          month: 'short', day: 'numeric', year: 'numeric',
                        })}
                      </span>
                      <div className="flex items-center gap-2">
                        {v.rating && (
                          <span className="font-mono text-xs text-amber">★ {v.rating}</span>
                        )}
                        {!isSharedView && onDeleteViewing && (
                          <button
                            onClick={() => setPendingDeleteId(v.id)}
                            style={{ color: 'var(--paper-faint)', opacity: 0.45 }}
                            onMouseEnter={(e) => (e.currentTarget.style.opacity = '1')}
                            onMouseLeave={(e) => (e.currentTarget.style.opacity = '0.45')}
                            aria-label="Delete viewing"
                          >
                            <Trash2 className="w-3 h-3" />
                          </button>
                        )}
                      </div>
                    </div>
                    {v.notes && (
                      <p className="text-xs text-muted-foreground font-sans italic leading-relaxed">
                        "{v.notes}"
                      </p>
                    )}
                  </>
                )}
              </div>
            </div>
          ))}
```

- [ ] **Step 3: Update the `ViewingTimeline` call site inside the main drawer**

Find the `<ViewingTimeline viewings={title.viewings} />` line (around line 1273). Replace with:

```typescript
                <ViewingTimeline
                  viewings={title.viewings}
                  isSharedView={isSharedView}
                  onDeleteViewing={(viewingId) => removeViewing(title.id, viewingId)}
                />
```

And destructure `removeViewing` from `useAppStore` in the `TitleDetailDrawer` function body. Find:
```typescript
  const { isDetailDrawerOpen, closeDetailDrawer, updateTitle, removeTitle, openRefreshMetadata, isSharedView } = useAppStore()
```
Replace with:
```typescript
  const { isDetailDrawerOpen, closeDetailDrawer, updateTitle, removeTitle, removeViewing, openRefreshMetadata, isSharedView } = useAppStore()
```

- [ ] **Step 4: Verify no type errors**

```bash
npm run lint
```

Expected: no new errors.

- [ ] **Step 5: Smoke test in the dev server**

```bash
npm run dev
```

1. Open a movie's detail drawer
2. Confirm a trash icon (faint, subtle) appears on each viewing entry
3. Click the trash icon — confirm it transforms to the "Remove this viewing? / Delete forever / Cancel" bar
4. Click **Cancel** — confirm the entry returns to normal
5. Click trash again, then **Delete forever** — confirm the entry disappears from the timeline and the viewings count stat updates

- [ ] **Step 6: Commit**

```bash
git add src/components/TitleDetailDrawer.tsx
git commit -m "feat(ui): add inline delete confirm to ViewingTimeline"
```

---

### Task 4: EpisodePanel watch event delete UI

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx` — `EpisodePanel` component only

**Interfaces:**
- Consumes: `deleteEpisodeWatchEvent(titleId: string, seasonNumber: number, episodeNumber: number, watchEventId: string): void` from store (Task 2)

- [ ] **Step 1: Add pending-delete state and store action to `EpisodePanel`**

Find the `EpisodePanel` function body. The existing state declarations start around line 260:
```typescript
  const logEpisode = useAppStore((s) => s.logEpisode)
  const [log, setLog] = useState<EpLogState>(EMPTY_EP_LOG)
  const [showForm, setShowForm] = useState(false)
  const [pendingLog, setPendingLog] = useState<EpLogState | null>(null)
  const [showNoirModal, setShowNoirModal] = useState(false)
```

Add two new lines after `const logEpisode = useAppStore((s) => s.logEpisode)`:
```typescript
  const deleteEpisodeWatchEvent = useAppStore((s) => s.deleteEpisodeWatchEvent)
  const [pendingDeleteWeId, setPendingDeleteWeId] = useState<string | null>(null)
```

- [ ] **Step 2: Replace the watch events render block with delete-aware version**

Find the "Watch events" column inside the 3-column grid (around line 362). The current render is:

```typescript
            {episode.watchEvents.length === 0 ? (
              <span style={{ color: 'var(--paper-faint)' }}>—</span>
            ) : (
              episode.watchEvents.map((we) => (
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
              ))
            )}
```

Replace with:

```typescript
            {episode.watchEvents.length === 0 ? (
              <span style={{ color: 'var(--paper-faint)' }}>—</span>
            ) : (
              episode.watchEvents.map((we) => (
                <div key={we.id}>
                  {pendingDeleteWeId === we.id ? (
                    <div>
                      <div className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>
                        Remove?
                      </div>
                      <div className="flex gap-2 mt-0.5">
                        <button
                          onClick={() => {
                            deleteEpisodeWatchEvent(titleId, season.seasonNumber, episode.episodeNumber, we.id)
                            setPendingDeleteWeId(null)
                          }}
                          className="font-mono transition-opacity hover:opacity-80"
                          style={{ color: 'var(--ember)', fontSize: '10px' }}
                        >
                          Delete
                        </button>
                        <button
                          onClick={() => setPendingDeleteWeId(null)}
                          className="font-mono transition-opacity hover:opacity-80"
                          style={{ color: 'var(--paper-faint)', fontSize: '10px' }}
                        >
                          Cancel
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div className="flex items-start gap-1">
                      <div className="flex-1 font-mono" style={{ color: 'var(--amber)', fontSize: '11px' }}>
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
                      {!isSharedView && (
                        <button
                          onClick={() => setPendingDeleteWeId(we.id)}
                          style={{ color: 'var(--paper-faint)', opacity: 0.45, flexShrink: 0, marginTop: '1px' }}
                          onMouseEnter={(e) => (e.currentTarget.style.opacity = '1')}
                          onMouseLeave={(e) => (e.currentTarget.style.opacity = '0.45')}
                          aria-label="Delete watch event"
                        >
                          <Trash2 className="w-2.5 h-2.5" />
                        </button>
                      )}
                    </div>
                  )}
                </div>
              ))
            )}
```

- [ ] **Step 3: Verify no type errors**

```bash
npm run lint
```

Expected: no new errors.

- [ ] **Step 4: Smoke test in the dev server**

1. Open a TV series detail drawer, expand a season, expand a watched episode
2. In the "Watched" column, confirm a tiny trash icon (faint) appears beside each watch event
3. Click the trash icon — confirm it transforms to "Remove? / Delete / Cancel"
4. Click **Cancel** — confirm the entry returns to normal
5. Click trash again, then **Delete** — confirm the watch event disappears; if it was the only watch event, confirm the Eye icon on the episode row disappears and the season's watched count decrements
6. Confirm the trash icon is NOT visible when viewing in shared mode

- [ ] **Step 5: Commit**

```bash
git add src/components/TitleDetailDrawer.tsx
git commit -m "feat(ui): add inline delete confirm to episode watch events in EpisodePanel"
```
