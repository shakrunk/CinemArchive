# Title Drawer Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the `TitleDetailDrawer` to match a streaming-platform aesthetic: full-bleed TV backdrop hero, episode thumbnail carousel with inline logging panel, smart season selector, larger cast avatars, and a trailers row.

**Architecture:** Component extraction (Option A from the spec) — three new files (`hero-backdrop.tsx`, `episode-card.tsx`, `trailer-row.tsx`) extracted from or added alongside `TitleDetailDrawer.tsx`. The drawer becomes a thinner orchestrator. No DB schema changes; trailer videos are fetched lazily and stored in component state only.

**Tech Stack:** React 18 + TypeScript + Tailwind CSS, Zustand, Supabase Edge Functions, TMDB API, lucide-react icons.

## Global Constraints

- No new DB columns or Supabase migrations — trailer video data is ephemeral component state only
- No changes to `Title`, `Season`, or `Episode` types in `src/store/mockData.ts`
- Verify with `rtk tsc` + `rtk lint` after every task; `npm run build` only in the final task
- Spider-Noir easter egg flow must remain intact — do not remove `SpiderNoirModeModal` usage in `EpisodePanel`
- All image URLs from TMDB go through the existing base URLs (`TMDB_STILL_BASE = 'https://image.tmdb.org/t/p/w300'` for stills, `https://img.youtube.com/vi/{key}/hqdefault.jpg` for YouTube thumbnails)
- Follow existing Tailwind + CSS variable patterns: `var(--amber)`, `var(--paper-faint)`, `var(--line)`, `var(--inset)`, `var(--card)`, `var(--paper-dim)`, `var(--ember)`, `font-mono` / `font-sans` / `font-serif`

---

## File Map

| Action | Path | Purpose |
|---|---|---|
| Modify | `supabase/functions/media-proxy/index.ts` | Add `action=videos` handler |
| Modify | `src/lib/utils.ts` | Export `fmtDate` + `fmtDateTime` helpers |
| Modify | `src/lib/media.ts` | Add `TitleVideo` interface + `fetchTitleVideos()` |
| Create | `src/components/ui/hero-backdrop.tsx` | TV series full-bleed backdrop hero |
| Create | `src/components/ui/episode-card.tsx` | Episode card visual + `EpisodePanel` (moved from drawer) |
| Create | `src/components/ui/trailer-row.tsx` | Horizontal YouTube trailer row |
| Modify | `src/components/TitleDetailDrawer.tsx` | Wire new components, replace episode accordion, upgrade hero + cast + trailers |

---

## Task 1: Edge Function — add `action=videos`

**Files:**
- Modify: `supabase/functions/media-proxy/index.ts`

**Interfaces:**
- Produces: `GET media-proxy?action=videos&id={tmdbId}&type={movie|tv}` → `{ results: Array<{ key, name, type, site, official }> }`

- [ ] **Step 1: Add `getTMDBVideos` handler function**

In `supabase/functions/media-proxy/index.ts`, add after `getOMDbRatings` (around line 121), before the `// ─── Router` comment:

```ts
async function getTMDBVideos(tmdbId: number, type: 'movie' | 'tv') {
  const cacheKey = `tmdb:videos:${type}:${tmdbId}`
  const cached = await getCached(cacheKey)
  if (cached) return cached

  const url = `${TMDB_BASE}/${type}/${tmdbId}/videos?api_key=${TMDB_API_KEY}&language=en-US`
  const res = await fetch(url)
  const data = await res.json()

  await setCached(cacheKey, data)
  return data
}
```

- [ ] **Step 2: Add `case 'videos'` to the router switch**

In the `switch (action)` block (around line 136), add before the `default:` case:

```ts
case 'videos': {
  const id = parseInt(url.searchParams.get('id') ?? '0', 10)
  const type = parseMediaType(url.searchParams.get('type'))
  if (!id) throw new Error('Missing id parameter')
  result = await getTMDBVideos(id, type)
  break
}
```

- [ ] **Step 3: Deploy the Edge Function**

```bash
supabase functions deploy media-proxy
```

Expected: `✓ Function media-proxy deployed.` (If you don't have Supabase CLI access, note this step for later and continue — the client-side fetch will gracefully return an empty array if the action is unknown.)

- [ ] **Step 4: Commit**

```bash
rtk git add supabase/functions/media-proxy/index.ts
rtk git commit -m "feat(edge): add action=videos endpoint for TMDB trailer data"
```

---

## Task 2: Utils date helpers + `fetchTitleVideos`

**Files:**
- Modify: `src/lib/utils.ts`
- Modify: `src/lib/media.ts`

**Interfaces:**
- Produces: `export function fmtDate(iso: string): string` in `src/lib/utils.ts`
- Produces: `export function fmtDateTime(iso: string): { date: string; time: string }` in `src/lib/utils.ts`
- Produces: `export interface TitleVideo { key: string; name: string; type: string; official: boolean }` in `src/lib/media.ts`
- Produces: `export async function fetchTitleVideos(tmdbId: number, type: MediaType): Promise<TitleVideo[]>` in `src/lib/media.ts`

- [ ] **Step 1: Add date helpers to `src/lib/utils.ts`**

Open `src/lib/utils.ts` and append at the end of the file:

```ts
export function fmtDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

export function fmtDateTime(iso: string): { date: string; time: string } {
  const d = new Date(iso)
  return {
    date: d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }),
    time: d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true }),
  }
}
```

- [ ] **Step 2: Add `TitleVideo` and `fetchTitleVideos` to `src/lib/media.ts`**

Append at the end of `src/lib/media.ts`:

```ts
export interface TitleVideo {
  key: string
  name: string
  type: string
  official: boolean
}

export async function fetchTitleVideos(tmdbId: number, type: MediaType): Promise<TitleVideo[]> {
  if (!(isSupabaseConfigured && supabase)) return []

  try {
    const { data, error } = await supabase.functions.invoke(
      `media-proxy?action=videos&id=${tmdbId}&type=${type}`
    )
    if (error) throw error

    const results: TitleVideo[] = ((data?.results ?? []) as any[])
      .filter((v) => v.site === 'YouTube' && ['Trailer', 'Teaser'].includes(v.type))
      .map((v) => ({
        key: v.key as string,
        name: v.name as string,
        type: v.type as string,
        official: v.official ?? false,
      }))

    results.sort((a, b) => {
      const score = (v: TitleVideo) => (v.official ? 2 : 0) + (v.type === 'Trailer' ? 1 : 0)
      return score(b) - score(a)
    })

    return results.slice(0, 4)
  } catch (e) {
    console.error('Error fetching title videos:', e)
    return []
  }
}
```

- [ ] **Step 3: Verify TypeScript**

```bash
rtk tsc
```

Expected: no new errors.

- [ ] **Step 4: Commit**

```bash
rtk git add src/lib/utils.ts src/lib/media.ts
rtk git commit -m "feat: add fmtDate/fmtDateTime to utils, add TitleVideo + fetchTitleVideos to media"
```

---

## Task 3: `HeroBackdrop` component

**Files:**
- Create: `src/components/ui/hero-backdrop.tsx`

**Interfaces:**
- Consumes: `DynamicPoster` from `src/components/ui/dynamic-poster`; `Title` from `src/store/mockData`
- Produces: `export function HeroBackdrop({ title, onPosterClick, children }: HeroBackdropProps): JSX.Element`

- [ ] **Step 1: Create `src/components/ui/hero-backdrop.tsx`**

```tsx
import type { Title } from 'src/store/mockData'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'

interface HeroBackdropProps {
  title: Title
  onPosterClick: () => void
  children: React.ReactNode
}

export function HeroBackdrop({ title, onPosterClick, children }: HeroBackdropProps) {
  return (
    <div className="relative overflow-hidden shrink-0">
      {title.backdropUrl && (
        <img
          src={title.backdropUrl}
          alt=""
          aria-hidden="true"
          className="absolute inset-0 w-full h-full object-cover"
          style={{ objectPosition: 'center top' }}
        />
      )}
      <div
        className="absolute inset-0"
        style={{
          background: 'linear-gradient(to bottom, rgba(0,0,0,0.15) 0%, rgba(0,0,0,0.55) 50%, var(--card) 100%)',
        }}
      />

      {/* Spacer that establishes the backdrop height */}
      <div className="relative z-10 h-28" />

      {/* Poster + title info row — sits at the bottom of the backdrop */}
      <div className="relative z-10 flex gap-5 px-6 pb-6">
        <div className="w-28 sm:w-36 shrink-0 -mt-10">
          {title.posterUrl ? (
            <button
              type="button"
              onClick={onPosterClick}
              aria-label={`View full poster for ${title.title}`}
              className="block w-full rounded-lg overflow-hidden shadow-2xl transition-opacity hover:opacity-90 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
            >
              <DynamicPoster title={title} />
            </button>
          ) : (
            <DynamicPoster title={title} />
          )}
        </div>
        <div className="flex-1 min-w-0 space-y-2 pt-4">
          {children}
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 2: Verify TypeScript**

```bash
rtk tsc
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
rtk git add src/components/ui/hero-backdrop.tsx
rtk git commit -m "feat: add HeroBackdrop component for TV series full-bleed backdrop"
```

---

## Task 4: `EpisodeCard` + `EpisodePanel` components

**Files:**
- Create: `src/components/ui/episode-card.tsx`

**Interfaces:**
- Consumes: `Episode`, `Season` from `src/store/mockData`; `fmtDate`, `fmtDateTime` from `src/lib/utils`; `avgEpisodeRating` from `src/store/episodeUtils`; `useAppStore` from `src/store/useAppStore`; `StarRating` from `src/components/ui/star-rating`; `Input` from `src/components/ui/input`; `SpiderNoirModeModal` from `src/components/SpiderNoirModeModal`
- Produces:
  - `export function EpisodeCard(props: EpisodeCardProps): JSX.Element`
  - `export function EpisodePanel(props: EpisodePanelProps): JSX.Element`

The `EpisodePanel` is a direct extraction of the existing private `EpisodePanel` function from `TitleDetailDrawer.tsx` (lines 352–717). The `EpisodeCard` is the new card visual component.

- [ ] **Step 1: Create `src/components/ui/episode-card.tsx`**

```tsx
import { useState } from 'react'
import { Eye, Check, Plus, Trash2, ChevronDown } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { avgEpisodeRating } from 'src/store/episodeUtils'
import { StarRating } from 'src/components/ui/star-rating'
import { Input } from 'src/components/ui/input'
import { SpiderNoirModeModal } from 'src/components/SpiderNoirModeModal'
import { cn, fmtDate, fmtDateTime } from 'src/lib/utils'
import type { Episode, Season } from 'src/store/mockData'

const TMDB_STILL_BASE = 'https://image.tmdb.org/t/p/w300'

// ─── EpisodeCard ─────────────────────────────────────────────────────────────

export interface EpisodeCardProps {
  episode: Episode
  season: Season
  titleId: string
  isSelected: boolean
  onSelect: () => void
  isSharedView: boolean
  isSpiderNoir: boolean
}

export function EpisodeCard({
  episode,
  season,
  titleId,
  isSelected,
  onSelect,
  isSharedView,
}: EpisodeCardProps) {
  const logEpisode = useAppStore((s) => s.logEpisode)
  const watched = episode.watchEvents.length > 0

  function handleQuickWatch(e: React.MouseEvent) {
    e.stopPropagation()
    if (isSharedView) return
    logEpisode(titleId, season.seasonNumber, episode.episodeNumber, {
      watchedAt: new Date().toISOString().slice(0, 10),
    })
  }

  const stillSrc = episode.stillUrl
    ? episode.stillUrl.startsWith('http')
      ? episode.stillUrl
      : `${TMDB_STILL_BASE}${episode.stillUrl}`
    : null

  return (
    <button
      type="button"
      onClick={onSelect}
      aria-expanded={isSelected}
      aria-label={`${episode.episodeName ?? `Episode ${episode.episodeNumber}`} — click to ${isSelected ? 'collapse' : 'expand'} details`}
      className={cn(
        'group shrink-0 w-[240px] text-left rounded-lg overflow-hidden border transition-all focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60',
        isSelected ? 'border-amber/50' : 'border-[var(--line)] hover:border-amber/30',
      )}
      style={{ background: 'var(--inset)' }}
    >
      {/* Still image area */}
      <div className="relative w-full" style={{ paddingBottom: '56.25%' /* 16:9 */ }}>
        {stillSrc ? (
          <img
            src={stillSrc}
            alt={episode.episodeName ?? `Episode ${episode.episodeNumber}`}
            className="absolute inset-0 w-full h-full object-cover"
          />
        ) : (
          <div
            className="absolute inset-0 flex items-center justify-center"
            style={{ background: 'var(--wash)' }}
          >
            <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
              No preview
            </span>
          </div>
        )}

        {/* Episode number badge */}
        <div
          className="absolute top-1.5 left-1.5 font-mono px-1.5 py-0.5 rounded text-white"
          style={{ fontSize: '10px', background: 'rgba(0,0,0,0.65)' }}
        >
          E{String(episode.episodeNumber).padStart(2, '0')}
        </div>

        {/* Watch eye icon */}
        {!isSharedView && (
          <button
            type="button"
            onClick={handleQuickWatch}
            aria-label={watched ? 'Mark as watched again' : 'Mark as watched'}
            className="absolute top-1.5 right-1.5 rounded-full p-1 transition-opacity hover:opacity-80 focus:outline-none"
            style={{ background: 'rgba(0,0,0,0.55)' }}
          >
            <Eye
              className="w-3.5 h-3.5"
              style={{ color: watched ? 'var(--amber)' : 'rgba(255,255,255,0.55)' }}
            />
          </button>
        )}

        {/* Watched progress bar */}
        {watched && (
          <div
            className="absolute bottom-0 left-0 right-0 h-0.5"
            style={{ background: 'var(--amber)' }}
          />
        )}
      </div>

      {/* Card body */}
      <div className="px-2.5 py-2 space-y-0.5">
        <div
          className="font-sans font-medium line-clamp-1"
          style={{ fontSize: '13px', color: 'var(--paper)' }}
        >
          {episode.episodeName ?? `Episode ${episode.episodeNumber}`}
        </div>
        {(episode.airDate || episode.runtime) && (
          <div className="font-mono" style={{ fontSize: '10px', color: 'var(--paper-faint)' }}>
            {episode.airDate ? new Date(episode.airDate).getFullYear() : ''}
            {episode.airDate && episode.runtime ? ' · ' : ''}
            {episode.runtime ? `${episode.runtime}m` : ''}
          </div>
        )}
        {episode.synopsis && (
          <p
            className="font-sans line-clamp-2 leading-snug"
            style={{ fontSize: '11px', color: 'var(--paper-dim)' }}
          >
            {episode.synopsis}
          </p>
        )}
      </div>
    </button>
  )
}

// ─── EpisodePanel ─────────────────────────────────────────────────────────────
// Extracted from TitleDetailDrawer.tsx — full logging panel shown below the carousel.

interface EpLogState {
  includeWatch: boolean
  watchedAt: string
  watchNotes: string
  rating: number
  reviewText: string
}

const EMPTY_EP_LOG: EpLogState = {
  includeWatch: true,
  watchedAt: new Date().toISOString().slice(0, 10),
  rating: 0,
  watchNotes: '',
  reviewText: '',
}

export interface EpisodePanelProps {
  episode: Episode
  season: Season
  titleId: string
  isSharedView: boolean
  isSpiderNoir: boolean
}

export function EpisodePanel({ episode, season, titleId, isSharedView, isSpiderNoir }: EpisodePanelProps) {
  const logEpisode = useAppStore((s) => s.logEpisode)
  const deleteEpisodeWatchEvent = useAppStore((s) => s.deleteEpisodeWatchEvent)
  const [pendingDeleteWeId, setPendingDeleteWeId] = useState<string | null>(null)
  const [log, setLog] = useState<EpLogState>(EMPTY_EP_LOG)
  const [showForm, setShowForm] = useState(false)
  const [showSaved, setShowSaved] = useState(false)
  const [pendingLog, setPendingLog] = useState<EpLogState | null>(null)
  const [showNoirModal, setShowNoirModal] = useState(false)

  const avg = avgEpisodeRating(episode)
  const watched = episode.watchEvents.length > 0
  const hasRatings = episode.ratings.length > 0
  const hasReviews = episode.reviews.length > 0
  const histCols = [watched, hasRatings, hasReviews].filter(Boolean).length

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

  function handleSubmit() {
    if (!log.includeWatch && log.rating === 0 && !log.reviewText.trim()) return
    const hasWatchOrReview = (log.includeWatch && !!log.watchedAt) || !!log.reviewText.trim()
    if (isSpiderNoir && hasWatchOrReview) {
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

  const stillSrc = episode.stillUrl
    ? episode.stillUrl.startsWith('http')
      ? episode.stillUrl
      : `${TMDB_STILL_BASE}${episode.stillUrl}`
    : null

  return (
    <div className="ep-panel px-3 py-3 space-y-3 rounded-lg" style={{ borderTop: '1px solid var(--line)', background: 'var(--inset)' }}>
      {/* Director / writers */}
      {(episode.director || (episode.writers && episode.writers.length > 0)) && (
        <div className="flex flex-wrap gap-x-3 gap-y-0.5" style={{ fontSize: '11px' }}>
          {episode.director && (
            <span>
              <span className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '9px' }}>Dir. </span>
              <span className="font-sans" style={{ color: 'var(--paper-dim)' }}>{episode.director}</span>
            </span>
          )}
          {episode.writers && episode.writers.length > 0 && (
            <span>
              <span className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '9px' }}>Written by </span>
              <span className="font-sans" style={{ color: 'var(--paper-dim)' }}>{episode.writers.join(', ')}</span>
            </span>
          )}
        </div>
      )}

      {/* Still + full synopsis */}
      {(stillSrc || episode.synopsis) && (
        <div className="flex gap-3">
          {stillSrc && (
            <img
              src={stillSrc}
              alt={episode.episodeName ?? `Episode ${episode.episodeNumber}`}
              className="rounded-md object-cover shrink-0"
              style={{ width: '120px', height: '68px', objectPosition: 'center' }}
            />
          )}
          {episode.synopsis && (
            <p className="font-sans text-xs leading-relaxed flex-1 min-w-0" style={{ color: 'var(--paper-dim)' }}>
              {episode.synopsis}
            </p>
          )}
        </div>
      )}

      {/* History: watch events / ratings / reviews */}
      {(episode.watchEvents.length > 0 || episode.ratings.length > 0 || episode.reviews.length > 0) && (
        <div className={cn('grid gap-2 text-xs', histCols === 1 ? 'grid-cols-1' : histCols === 2 ? 'grid-cols-2' : 'grid-cols-3')}>
          {watched && (
            <div>
              <div className="font-mono mb-1.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>
                Watched
              </div>
              {episode.watchEvents.map((we) => (
                <div key={we.id}>
                  {pendingDeleteWeId === we.id ? (
                    <div>
                      <div className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>Remove?</div>
                      <div className="flex gap-2 mt-0.5">
                        <button
                          onClick={() => { deleteEpisodeWatchEvent(titleId, season.seasonNumber, episode.episodeNumber, we.id); setPendingDeleteWeId(null) }}
                          className="font-mono transition-opacity hover:opacity-80"
                          style={{ color: 'var(--ember)', fontSize: '10px' }}
                        >Delete</button>
                        <button onClick={() => setPendingDeleteWeId(null)} className="font-mono transition-opacity hover:opacity-80" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>Cancel</button>
                      </div>
                    </div>
                  ) : (
                    <div className="flex items-start gap-1">
                      <div className="flex-1 font-mono" style={{ color: 'var(--amber)', fontSize: '11px' }}>
                        {fmtDate(we.watchedAt)}
                        {we.colorMode && (
                          <span className="font-mono ml-1.5 px-1 rounded" style={{ fontSize: '9px', letterSpacing: '0.06em', background: we.colorMode === 'bw' ? 'rgba(200,200,200,0.12)' : 'rgba(233,178,102,0.15)', color: we.colorMode === 'bw' ? '#aaa' : 'var(--amber)', border: `1px solid ${we.colorMode === 'bw' ? 'rgba(200,200,200,0.2)' : 'rgba(233,178,102,0.3)'}` }}>
                            {we.colorMode === 'bw' ? '◐ B&W' : '◈ Color'}
                          </span>
                        )}
                        {we.notes && <div className="font-sans italic mt-0.5" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>"{we.notes}"</div>}
                      </div>
                      {!isSharedView && (
                        <button onClick={() => setPendingDeleteWeId(we.id)} style={{ color: 'var(--paper-faint)', opacity: 0.45, flexShrink: 0, marginTop: '1px' }} onMouseEnter={(e) => (e.currentTarget.style.opacity = '1')} onMouseLeave={(e) => (e.currentTarget.style.opacity = '0.45')} aria-label="Delete watch event">
                          <Trash2 className="w-2.5 h-2.5" />
                        </button>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {hasRatings && (
            <div>
              <div className="font-mono mb-1.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Ratings</div>
              {episode.ratings.map((er) => (
                <div key={er.id} className="font-mono" style={{ color: 'var(--amber)', fontSize: '11px' }}>
                  ★ {er.rating}
                  <div className="mt-0.5">
                    <div className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>{fmtDateTime(er.ratedAt).date}</div>
                    <div className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '9px' }}>{fmtDateTime(er.ratedAt).time}</div>
                  </div>
                </div>
              ))}
              {episode.ratings.length > 1 && avg !== null && (
                <div className="font-mono mt-1" style={{ color: 'var(--amber-deep)', fontSize: '10px' }}>avg ★ {avg.toFixed(1)}</div>
              )}
            </div>
          )}

          {hasReviews && (
            <div>
              <div className="font-mono mb-1.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Reviews</div>
              {episode.reviews.map((rv) => (
                <div key={rv.id}>
                  <div className="font-sans italic leading-snug" style={{ color: 'var(--paper-dim)', fontSize: '11px' }}>"{rv.reviewText}"</div>
                  <div className="mt-0.5" style={{ color: 'var(--paper-faint)' }}>
                    <div className="font-mono flex items-center gap-1.5" style={{ fontSize: '10px' }}>
                      <span>{fmtDateTime(rv.reviewedAt).date}</span>
                      {rv.colorMode && (
                        <span className="font-mono px-1 rounded" style={{ fontSize: '9px', letterSpacing: '0.06em', background: rv.colorMode === 'bw' ? 'rgba(200,200,200,0.12)' : 'rgba(233,178,102,0.15)', color: rv.colorMode === 'bw' ? '#aaa' : 'var(--amber)', border: `1px solid ${rv.colorMode === 'bw' ? 'rgba(200,200,200,0.2)' : 'rgba(233,178,102,0.3)'}` }}>
                          {rv.colorMode === 'bw' ? '◐ B&W' : '◈ Color'}
                        </span>
                      )}
                    </div>
                    <div className="font-mono" style={{ fontSize: '9px' }}>{fmtDateTime(rv.reviewedAt).time}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Log form */}
      {!isSharedView && (
        showForm ? (
          <div className="space-y-3 pt-2" style={{ borderTop: episode.watchEvents.length > 0 || episode.ratings.length > 0 || episode.reviews.length > 0 ? '1px solid var(--line)' : 'none' }}>
            <label className="flex items-center gap-2 cursor-pointer">
              <input type="checkbox" checked={log.includeWatch} onChange={(e) => setLog((l) => ({ ...l, includeWatch: e.target.checked }))} className="accent-amber w-3.5 h-3.5" />
              <span className="font-sans text-xs" style={{ color: 'var(--paper-dim)' }}>Log a watch event</span>
            </label>
            {log.includeWatch && (
              <div className="space-y-2 pl-5">
                <Input aria-label="Date watched" type="date" value={log.watchedAt} onChange={(e) => setLog((l) => ({ ...l, watchedAt: e.target.value }))} className="h-8 text-xs font-mono bg-secondary/50 border-border" />
                <Input aria-label="Watch notes" value={log.watchNotes} onChange={(e) => setLog((l) => ({ ...l, watchNotes: e.target.value }))} placeholder="Watch notes (optional)" className="h-8 text-xs bg-secondary/50 border-border" />
              </div>
            )}
            <div>
              <div className="font-sans text-xs mb-1.5" style={{ color: 'var(--paper-faint)' }}>Rating <span style={{ fontSize: '10px' }}>(optional · logged independently)</span></div>
              <StarRating value={log.rating} onChange={(r) => setLog((l) => ({ ...l, rating: r }))} size="sm" />
            </div>
            <div>
              <div className="font-sans text-xs mb-1.5" style={{ color: 'var(--paper-faint)' }}>Review <span style={{ fontSize: '10px' }}>(optional · logged independently)</span></div>
              <textarea aria-label="Episode review" value={log.reviewText} onChange={(e) => setLog((l) => ({ ...l, reviewText: e.target.value }))} placeholder="Your thoughts on this episode…" rows={2} className="w-full text-xs font-sans resize-none rounded-md px-2.5 py-2 focus:outline-none focus:ring-1 focus:ring-amber/40" style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }} />
            </div>
            <div className="flex gap-2">
              <button onClick={handleSubmit} disabled={!log.includeWatch && log.rating === 0 && !log.reviewText.trim()} className="flex-1 h-8 rounded-md text-xs font-sans font-medium transition-all disabled:opacity-40" style={{ background: 'var(--amber)', color: '#1a0e06' }}>Save</button>
              <button onClick={() => { setShowForm(false); setLog(EMPTY_EP_LOG) }} className="h-8 px-3 rounded-md text-xs font-sans border transition-colors" style={{ borderColor: 'var(--line)', color: 'var(--paper-faint)' }}>Cancel</button>
            </div>
          </div>
        ) : (
          <button
            onClick={() => { if (showSaved) return; setLog((l) => ({ ...l, watchedAt: new Date().toISOString().slice(0, 10) })); setShowForm(true) }}
            className="flex items-center gap-1.5 text-xs font-mono transition-colors"
            style={{ color: showSaved ? 'var(--amber)' : 'var(--amber-deep)' }}
            onMouseEnter={(e) => { if (!showSaved) e.currentTarget.style.color = 'var(--amber)' }}
            onMouseLeave={(e) => { if (!showSaved) e.currentTarget.style.color = 'var(--amber-deep)' }}
          >
            {showSaved ? <><Check className="w-3 h-3" />Logged</> : <><Plus className="w-3 h-3" />{watched ? 'Add rating, review, or re-watch' : 'Log watch event, rating, or review'}</>}
          </button>
        )
      )}
      <SpiderNoirModeModal open={showNoirModal} onSelect={handleNoirSelect} onSkip={handleNoirSkip} />
    </div>
  )
}
```

- [ ] **Step 2: Verify TypeScript**

```bash
rtk tsc
```

Expected: no errors (the file imports all its own dependencies).

- [ ] **Step 3: Commit**

```bash
rtk git add src/components/ui/episode-card.tsx
rtk git commit -m "feat: add EpisodeCard and EpisodePanel components (extracted from drawer)"
```

---

## Task 5: `TrailerRow` component

**Files:**
- Create: `src/components/ui/trailer-row.tsx`

**Interfaces:**
- Consumes: `TitleVideo` from `src/lib/media`
- Produces: `export function TrailerRow({ videos }: { videos: TitleVideo[] }): JSX.Element | null`

- [ ] **Step 1: Create `src/components/ui/trailer-row.tsx`**

```tsx
import { Play } from 'lucide-react'
import type { TitleVideo } from 'src/lib/media'

export function TrailerRow({ videos }: { videos: TitleVideo[] }) {
  if (videos.length === 0) return null

  return (
    <div>
      <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">
        Trailers
      </h4>
      <div className="flex gap-3 overflow-x-auto scrollbar-none -mx-1 px-1 pb-1">
        {videos.map((v) => (
          <a
            key={v.key}
            href={`https://www.youtube.com/watch?v=${v.key}`}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={`Watch ${v.name} on YouTube`}
            className="group shrink-0 w-[160px] focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 rounded-lg"
          >
            <div className="relative w-full rounded-lg overflow-hidden" style={{ paddingBottom: '56.25%' }}>
              <img
                src={`https://img.youtube.com/vi/${v.key}/hqdefault.jpg`}
                alt={v.name}
                className="absolute inset-0 w-full h-full object-cover transition-opacity group-hover:opacity-80"
              />
              <div className="absolute inset-0 flex items-center justify-center">
                <div
                  className="rounded-full p-2 transition-transform group-hover:scale-110"
                  style={{ background: 'rgba(0,0,0,0.55)' }}
                >
                  <Play className="w-4 h-4 text-white" fill="white" />
                </div>
              </div>
            </div>
            <div className="mt-1.5 px-0.5">
              <div
                className="font-sans line-clamp-1"
                style={{ fontSize: '11px', color: 'var(--paper)' }}
              >
                {v.name}
              </div>
              <div
                className="font-mono uppercase"
                style={{ fontSize: '9px', color: 'var(--paper-faint)', letterSpacing: '0.1em' }}
              >
                {v.type}
              </div>
            </div>
          </a>
        ))}
      </div>
    </div>
  )
}
```

- [ ] **Step 2: Verify TypeScript**

```bash
rtk tsc
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
rtk git add src/components/ui/trailer-row.tsx
rtk git commit -m "feat: add TrailerRow component for YouTube trailer thumbnails"
```

---

## Task 6: `TVSeriesSection` — smart season selector + episode carousel

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx` — `TVSeriesSection` function and its props interface only (lines ~812–1006)

**Interfaces:**
- Consumes: `EpisodeCard`, `EpisodePanel` from `src/components/ui/episode-card`
- The `TVSeriesSectionProps` interface and the external call site (`TitleDetailDrawer` render at line ~1641) remain unchanged — no prop additions needed

- [ ] **Step 1: Add imports to `TitleDetailDrawer.tsx`**

Near the top of the file, add to the existing import block:

```ts
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { EpisodeCard, EpisodePanel } from 'src/components/ui/episode-card'
```

(Keep all existing imports — they are still used by other parts of the file. `ChevronDown` and `ChevronRight` are already imported; only add `ChevronLeft` and the episode-card imports.)

- [ ] **Step 2: Replace the `TVSeriesSection` function**

Find the entire `TVSeriesSection` function (from `// ─── TV: Season tab bar + episode list` at line ~812 through its closing `}` at line ~1006) and replace it with:

```tsx
// ─── TV: Season selector + episode carousel ───────────────────────────────────

interface TVSeriesSectionProps {
  titleId: string
  seasons: Season[]
  isSharedView: boolean
  isSpiderNoir: boolean
  onPersonClick: (person: PersonDetailTarget) => void
}

function TVSeriesSection({ titleId, seasons, isSharedView, isSpiderNoir, onPersonClick }: TVSeriesSectionProps) {
  const [selectedSeason, setSelectedSeason] = useState(seasons[0]?.seasonNumber ?? 1)
  const [selectedEpId, setSelectedEpId] = useState<string | null>(null)
  const [canScrollLeft, setCanScrollLeft] = useState(false)
  const [canScrollRight, setCanScrollRight] = useState(false)
  const carouselRef = useRef<HTMLDivElement>(null)

  const season = seasons.find((s) => s.seasonNumber === selectedSeason)
  const hasEpisodes = (s: Season) => (s.episodes?.length ?? 0) > 0
  const selectedEp = season?.episodes?.find((e) => e.id === selectedEpId) ?? null

  const totalWatched = totalEpisodesWatched(seasons)
  const totalCount = totalEpisodeCount(seasons)
  const seriesAvg = avgSeriesRating(seasons)

  const CARD_WIDTH = 252 // 240px card + 12px gap

  function handleCarouselScroll() {
    const el = carouselRef.current
    if (!el) return
    setCanScrollLeft(el.scrollLeft > 4)
    setCanScrollRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 4)
  }

  function scrollCarousel(dir: 'left' | 'right') {
    carouselRef.current?.scrollBy({ left: dir === 'right' ? CARD_WIDTH : -CARD_WIDTH, behavior: 'smooth' })
  }

  // Reset carousel + selection on season change
  useEffect(() => {
    setSelectedEpId(null)
    const el = carouselRef.current
    if (el) el.scrollLeft = 0
    setCanScrollLeft(false)
    const t = setTimeout(() => {
      const el2 = carouselRef.current
      if (el2) setCanScrollRight(el2.scrollWidth > el2.clientWidth + 4)
    }, 50)
    return () => clearTimeout(t)
  }, [selectedSeason])

  function handleSeasonChange(seasonNumber: number) {
    setSelectedSeason(seasonNumber)
    setSelectedEpId(null)
  }

  return (
    <div className="space-y-5">
      {/* Series-level stats */}
      <div className="grid grid-cols-3 gap-3">
        <div className="rounded-lg p-3 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--paper)', fontVariationSettings: '"opsz" 30' }}>
            {totalWatched}<span className="text-sm font-mono ml-0.5" style={{ color: 'var(--paper-faint)' }}>/{totalCount}</span>
          </div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Episodes</div>
        </div>
        <div className="rounded-lg p-3 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--amber)', fontVariationSettings: '"opsz" 30' }}>
            {seriesAvg !== null ? `★ ${seriesAvg.toFixed(1)}` : '—'}
          </div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Avg Rating</div>
        </div>
        <div className="rounded-lg p-3 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--paper)', fontVariationSettings: '"opsz" 30' }}>{seasons.length}</div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Seasons</div>
        </div>
      </div>

      {/* Series Graph heatmap */}
      {seasons.some(hasEpisodes) && (
        <div>
          <h4 className="font-mono mb-3" style={{ fontSize: '10px', letterSpacing: '0.18em', textTransform: 'uppercase', color: 'var(--paper-faint)' }}>
            Series Graph
          </h4>
          <SeriesGraph
            seasons={seasons}
            getEpisode={(sn, en) => {
              const ep = seasons.find((s) => s.seasonNumber === sn)?.episodes?.find((e) => e.episodeNumber === en)
              return ep ?? null
            }}
          />
        </div>
      )}

      {/* Smart season selector */}
      {seasons.length <= 3 ? (
        <div className="flex gap-2 overflow-x-auto scrollbar-none">
          {seasons.map((s) => {
            const watched = episodesWatchedInSeason(s)
            const pct = s.episodeCount > 0 ? Math.round((watched / s.episodeCount) * 100) : 0
            const seasonAvg = avgSeasonRating(s)
            return (
              <button
                key={s.seasonNumber}
                onClick={() => handleSeasonChange(s.seasonNumber)}
                aria-label={`Season ${s.seasonNumber}`}
                aria-current={selectedSeason === s.seasonNumber ? 'true' : undefined}
                className={cn(
                  'shrink-0 px-3 py-2 rounded-lg text-left transition-all border',
                  selectedSeason === s.seasonNumber
                    ? 'border-amber/40 bg-amber/10'
                    : 'border-transparent hover:border-[var(--line)] hover:bg-[var(--wash)]'
                )}
              >
                <div className="font-mono" style={{ fontSize: '11px', color: selectedSeason === s.seasonNumber ? 'var(--amber)' : 'var(--paper-dim)' }}>
                  S{s.seasonNumber}
                </div>
                <div className="font-mono" style={{ fontSize: '9px', color: 'var(--paper-faint)' }}>
                  {pct}%{seasonAvg !== null ? ` · ★${seasonAvg.toFixed(1)}` : ''}
                </div>
              </button>
            )
          })}
        </div>
      ) : (
        <select
          value={selectedSeason}
          onChange={(e) => handleSeasonChange(parseInt(e.target.value, 10))}
          aria-label="Select season"
          className="font-mono text-sm rounded-lg px-3 py-2 bg-secondary border border-amber/30 focus:outline-none focus:border-amber/60"
          style={{ color: 'var(--amber)' }}
        >
          {seasons.map((s) => {
            const watched = episodesWatchedInSeason(s)
            const pct = s.episodeCount > 0 ? Math.round((watched / s.episodeCount) * 100) : 0
            const seasonAvg = avgSeasonRating(s)
            return (
              <option key={s.seasonNumber} value={s.seasonNumber}>
                {`Season ${s.seasonNumber} · ${pct}%${seasonAvg !== null ? ` · ★${seasonAvg.toFixed(1)}` : ''}`}
              </option>
            )
          })}
        </select>
      )}

      {/* Season cast */}
      {season?.cast && season.cast.length > 0 && (
        <div>
          <div className="font-mono mb-2" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>
            Season Cast
          </div>
          <div className="flex gap-3 overflow-x-auto scrollbar-none pb-1 -mx-1 px-1">
            {season.cast.map((member) => (
              <button
                key={member.tmdbPersonId}
                type="button"
                onClick={() => onPersonClick({ tmdbPersonId: member.tmdbPersonId, name: member.name, profileUrl: member.profileUrl, character: member.character })}
                aria-label={`View details for ${member.name}`}
                className="group shrink-0 w-12 text-center focus:outline-none"
              >
                <div className="w-12 h-12 rounded-full overflow-hidden mb-1 mx-auto flex items-center justify-center transition-colors group-hover:border-amber/60" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
                  {member.profileUrl ? (
                    <img src={member.profileUrl} alt={member.name} className="w-full h-full object-cover" />
                  ) : (
                    <span className="font-mono text-base" style={{ color: 'var(--paper-faint)' }}>{member.name.charAt(0).toUpperCase()}</span>
                  )}
                </div>
                <div className="font-sans line-clamp-2 text-paper transition-colors group-hover:text-amber" style={{ fontSize: '9px', lineHeight: 1.3 }}>{member.name}</div>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Episode carousel */}
      {season && hasEpisodes(season) && (
        <div>
          <div className="relative">
            {/* Left arrow */}
            {canScrollLeft && (
              <button
                type="button"
                onClick={() => scrollCarousel('left')}
                aria-label="Scroll episodes left"
                className="absolute left-0 top-1/2 -translate-y-1/2 z-10 -translate-x-2 rounded-full p-1.5 transition-opacity hover:opacity-100 opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                style={{ background: 'var(--card)', border: '1px solid var(--line)' }}
              >
                <ChevronLeft className="w-4 h-4" style={{ color: 'var(--paper)' }} />
              </button>
            )}

            {/* Carousel */}
            <div
              ref={carouselRef}
              onScroll={handleCarouselScroll}
              className="flex gap-3 overflow-x-auto scrollbar-none pb-1"
            >
              {season.episodes!.map((ep) => (
                <EpisodeCard
                  key={ep.id}
                  episode={ep}
                  season={season}
                  titleId={titleId}
                  isSelected={selectedEpId === ep.id}
                  onSelect={() => setSelectedEpId(selectedEpId === ep.id ? null : ep.id)}
                  isSharedView={isSharedView}
                  isSpiderNoir={isSpiderNoir}
                />
              ))}
            </div>

            {/* Right arrow */}
            {canScrollRight && (
              <button
                type="button"
                onClick={() => scrollCarousel('right')}
                aria-label="Scroll episodes right"
                className="absolute right-0 top-1/2 -translate-y-1/2 z-10 translate-x-2 rounded-full p-1.5 transition-opacity hover:opacity-100 opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                style={{ background: 'var(--card)', border: '1px solid var(--line)' }}
              >
                <ChevronRight className="w-4 h-4" style={{ color: 'var(--paper)' }} />
              </button>
            )}
          </div>

          {/* Inline logging panel — renders below the carousel when a card is selected */}
          {selectedEp && (
            <div className="mt-3">
              <EpisodePanel
                episode={selectedEp}
                season={season}
                titleId={titleId}
                isSharedView={isSharedView}
                isSpiderNoir={isSpiderNoir}
              />
            </div>
          )}
        </div>
      )}

      {/* Fallback: coarse progress bar when season has no episode-level data */}
      {season && !hasEpisodes(season) && (() => {
        const pct = season.episodeCount > 0 ? (season.episodesWatched / season.episodeCount) * 100 : 0
        return (
          <div className="flex items-center gap-3 px-2 py-3">
            <span className="font-mono text-xs text-muted-foreground w-8 shrink-0">S{season.seasonNumber}</span>
            <div className="flex-1 h-1.5 bg-secondary rounded-full overflow-hidden">
              <div className="h-full bg-amber rounded-full transition-all" style={{ width: `${pct}%` }} />
            </div>
            <span className="font-mono text-xs text-muted-foreground w-12 text-right shrink-0">
              {season.episodesWatched}/{season.episodeCount}
            </span>
          </div>
        )
      })()}
    </div>
  )
}
```

- [ ] **Step 3: Remove the old `EpisodePanel` and `EpisodeRow` functions from `TitleDetailDrawer.tsx`**

Delete everything from `// ─── TV: Episode panel` (around line 320) through the end of `EpisodeRow` (around line 811, just before `// ─── TV: Season tab bar`). This removes:
- The `EpLogState` interface and `EMPTY_EP_LOG` constant
- The `EpisodePanelProps` interface and `EpisodePanel` function
- The `EpisodeRowProps` interface and `EpisodeRow` function

These are now in `src/components/ui/episode-card.tsx`.

- [ ] **Step 4: Verify TypeScript and lint**

```bash
rtk tsc && rtk lint
```

Expected: no errors. If lint flags unused imports (e.g. `Eye`, `MessageSquare`, `ChevronDown`), remove them from the import line at the top of `TitleDetailDrawer.tsx`.

- [ ] **Step 5: Commit**

```bash
rtk git add src/components/TitleDetailDrawer.tsx
rtk git commit -m "feat: replace episode accordion with carousel + smart season selector"
```

---

## Task 7: `TitleDetailDrawer` — hero, cast, trailers, cleanup

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx`

**Interfaces:**
- Consumes: `HeroBackdrop` from `src/components/ui/hero-backdrop`; `TrailerRow` from `src/components/ui/trailer-row`; `fetchTitleVideos`, `TitleVideo` from `src/lib/media`; `fmtDate`, `fmtDateTime` from `src/lib/utils`

- [ ] **Step 1: Update imports at the top of `TitleDetailDrawer.tsx`**

Add these imports:

```ts
import { HeroBackdrop } from 'src/components/ui/hero-backdrop'
import { TrailerRow } from 'src/components/ui/trailer-row'
import { fetchTitleVideos, type TitleVideo } from 'src/lib/media'
import { fmtDate, fmtDateTime } from 'src/lib/utils'
```

Then delete the local `fmtDate` and `fmtDateTime` function definitions (around lines 1117–1127 in the original file, will be at a different line after Task 6 edits) — they are now imported from `src/lib/utils`.

Also remove `TMDB_STILL_BASE` from the top of `TitleDetailDrawer.tsx` (line 36: `const TMDB_STILL_BASE = 'https://image.tmdb.org/t/p/w300'`) — it is now only used in `episode-card.tsx`.

- [ ] **Step 2: Add `videos` state and fetch effect inside `TitleDetailDrawer`**

Inside the `TitleDetailDrawer` function body, after the existing `useState` declarations, add:

```ts
const [videos, setVideos] = useState<TitleVideo[]>([])

useEffect(() => {
  if (!isDetailDrawerOpen || !title?.tmdbId) {
    setVideos([])
    return
  }
  let cancelled = false
  fetchTitleVideos(title.tmdbId, title.type).then((v) => {
    if (!cancelled) setVideos(v)
  })
  return () => { cancelled = true }
}, [isDetailDrawerOpen, title?.tmdbId, title?.type])
```

- [ ] **Step 3: Replace the movie hero blur to use `backdropUrl`**

Find the movie/shared hero section (the `<div className="relative overflow-hidden shrink-0">` block that contains the blurred background, around line 1480 in the original). It currently does:

```tsx
backgroundImage: `url(${title.posterUrl})`,
```

Change that one line to:

```tsx
backgroundImage: `url(${title.backdropUrl ?? title.posterUrl})`,
```

- [ ] **Step 4: Wrap the TV hero in `HeroBackdrop`**

The current hero renders identically for movies and TV. After Step 3, split the render: when `title.type === 'tv'` and `title.backdropUrl` is set, render `HeroBackdrop` instead of the default blurred-poster block.

Replace the entire hero `<div className="relative overflow-hidden shrink-0">...</div>` block (ends after the `</div>` closing the poster + info row, around line 1557) with:

```tsx
{/* Hero: cinematic backdrop (TV) or blurred-poster (movie / TV without backdrop) */}
{title.type === 'tv' && title.backdropUrl ? (
  <HeroBackdrop title={title} onPosterClick={() => setPosterLightboxOpen(true)}>
    <div className="flex items-center gap-2">
      <Tv className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
      <span className="font-mono text-xs text-muted-foreground uppercase tracking-wider">Series</span>
      {title.network && (
        <span className="font-mono text-xs text-muted-foreground">· {title.network}</span>
      )}
    </div>
    <CardTitle className="text-xl leading-tight">{title.title}</CardTitle>
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
      <span className="font-mono text-sm text-amber">{title.year}</span>
    </div>
    {isSpiderNoir && (
      <SpiderNoirModeSelector
        unlockedModes={unlockedModes}
        earnedModes={earnedModes}
        selected={manualMode}
        pinned={pinnedModeRaw}
        onSelect={handleModeSelect}
        onTogglePin={handleTogglePin}
      />
    )}
    <StarRating
      value={title.rating ?? 0}
      size="sm"
      onChange={isSharedView ? undefined : (rating) => updateTitle(title.id, { rating })}
    />
  </HeroBackdrop>
) : (
  <div className="relative overflow-hidden shrink-0">
    {(title.backdropUrl ?? title.posterUrl) && (
      <div
        className="absolute inset-0"
        style={{
          backgroundImage: `url(${title.backdropUrl ?? title.posterUrl})`,
          backgroundSize: 'cover',
          backgroundPosition: 'center top',
          filter: 'blur(20px)',
          transform: 'scale(1.3)',
          opacity: 0.18,
        }}
      />
    )}
    <div className="absolute inset-0 bg-gradient-to-b from-secondary/30 via-card/70 to-card" />
    <div className="relative z-10 flex gap-5 px-6 pt-10 pb-6">
      <div className="w-28 sm:w-36 shrink-0">
        {title.posterUrl ? (
          <button
            type="button"
            onClick={() => setPosterLightboxOpen(true)}
            aria-label={`View full poster for ${title.title}`}
            className="block w-full rounded-lg overflow-hidden transition-opacity hover:opacity-90 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
            title="View full poster"
          >
            <DynamicPoster title={title} />
          </button>
        ) : (
          <DynamicPoster title={title} />
        )}
      </div>
      <div className="flex-1 min-w-0 space-y-2 pt-6">
        <div className="flex items-center gap-2">
          {title.type === 'movie' ? (
            <Film className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
          ) : (
            <Tv className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
          )}
          <span className="font-mono text-xs text-muted-foreground uppercase tracking-wider">
            {title.type === 'tv' ? 'Series' : 'Film'}
          </span>
          {title.network && (
            <span className="font-mono text-xs text-muted-foreground">· {title.network}</span>
          )}
        </div>
        <CardTitle className="text-xl leading-tight">{title.title}</CardTitle>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <span className="font-mono text-sm text-amber">{title.year}</span>
          {title.director && (
            <span className="text-xs text-muted-foreground font-sans">dir. {title.director}</span>
          )}
          {title.runtime && title.type === 'movie' && (
            <div className="flex items-center gap-1 text-xs text-muted-foreground">
              <Clock className="w-3 h-3" />
              <span className="font-mono">{title.runtime}m</span>
            </div>
          )}
        </div>
        {isSpiderNoir && (
          <SpiderNoirModeSelector
            unlockedModes={unlockedModes}
            earnedModes={earnedModes}
            selected={manualMode}
            pinned={pinnedModeRaw}
            onSelect={handleModeSelect}
            onTogglePin={handleTogglePin}
          />
        )}
        <StarRating
          value={title.rating ?? 0}
          size="sm"
          onChange={isSharedView ? undefined : (rating) => updateTitle(title.id, { rating })}
        />
      </div>
    </div>
  </div>
)}
```

- [ ] **Step 5: Upgrade cast avatar sizes in `CastCrewSection`**

Find the title-level cast section inside `CastCrewSection` (around line 221 in the original — the `<button>` loop under the `Cast` label). Make two changes:

1. Change outer button width from `w-14` to `w-[72px]`:
   ```tsx
   // Before
   className="group shrink-0 w-14 text-center focus:outline-none"
   // After
   className="group shrink-0 w-[72px] text-center focus:outline-none"
   ```

2. Change avatar circle from `w-14 h-14` to `w-16 h-16`:
   ```tsx
   // Before
   className="w-14 h-14 rounded-full overflow-hidden mb-1 mx-auto ..."
   // After
   className="w-16 h-16 rounded-full overflow-hidden mb-1 mx-auto ..."
   ```

3. Change character name font size from `9px` to `10px`:
   ```tsx
   // Before (the character span)
   style={{ fontSize: '9px', color: 'var(--paper-faint)', lineHeight: 1.3 }}
   // After
   style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3 }}
   ```

- [ ] **Step 6: Add `TrailerRow` to the drawer body**

In the scrollable body `<div className="px-6 pb-6 space-y-5">`, add the `TrailerRow` immediately before the maintenance actions section (the `{!isSharedView && <div className="pt-2 border-t"...>` block):

```tsx
{/* Trailers */}
<TrailerRow videos={videos} />
```

- [ ] **Step 7: Verify TypeScript, lint, and build**

```bash
rtk tsc && rtk lint && npm run build
```

Expected: clean output from all three. If lint flags unused imports (`Eye`, `MessageSquare`, `ChevronDown`, etc.), remove them from the import line.

- [ ] **Step 8: Start the dev server and verify visually**

```bash
npm run dev
```

Open the drawer on a TV series (e.g. Avatar: The Last Airbender):
- [ ] Full backdrop hero visible at the top with poster inset
- [ ] Episode carousel scrolls horizontally; stills render
- [ ] Arrow buttons appear at carousel edges
- [ ] Clicking a card expands the logging panel below the carousel
- [ ] Eye icon on a card logs a quick watch event without opening the panel
- [ ] Season selector shows pills (≤3 seasons) or dropdown (>3 seasons)
- [ ] SeriesGraph still visible above the season selector
- [ ] Trailers section renders at the bottom (if videos are returned)

Open the drawer on a movie:
- [ ] Hero uses blurred backdrop (not poster) for atmospheric bg
- [ ] Layout otherwise unchanged

- [ ] **Step 9: Commit**

```bash
rtk git add src/components/TitleDetailDrawer.tsx
rtk git commit -m "feat: wire HeroBackdrop, TrailerRow, cast upgrade into TitleDetailDrawer"
```
