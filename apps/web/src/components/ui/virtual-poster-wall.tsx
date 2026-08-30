import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useWindowVirtualizer } from '@tanstack/react-virtual'
import { useAppStore } from 'src/store/useAppStore'
import { POSTER_GRID_DENSITY } from 'src/lib/posterGridDensity'
import { staggerDelays } from 'src/lib/utils'
import { DynamicPoster } from './dynamic-poster'
import type { Title } from 'src/store/mockData'
import type { GridSize } from 'src/store/useAppStore'

// Mirrors index.css's .poster-wall breakpoint where auto-fill columns give
// way to an explicit --poster-cols count.
const MOBILE_BREAKPOINT_PX = 640

// Mirrors index.css's `.poster-wall` gap: `clamp(14px, 1.6vw, 22px)` above the
// 640px breakpoint (vw is against the viewport, not this grid's container),
// a flat 12px below it.
function computeGapPx(viewportWidth: number): number {
  if (viewportWidth < MOBILE_BREAKPOINT_PX) return 12
  return Math.min(22, Math.max(14, viewportWidth * 0.016))
}

// Mirrors index.css's `.poster-wall` grid-template-columns: auto-fill/minmax
// above the breakpoint (the standard CSS Grid track-count formula for
// `repeat(auto-fill, minmax(min, 1fr))`), the explicit --poster-cols below it.
function computeColumns(containerWidth: number, viewportWidth: number, minPx: number, explicitCols: number, gapPx: number): number {
  if (viewportWidth < MOBILE_BREAKPOINT_PX) return explicitCols
  if (containerWidth <= 0) return 1
  return Math.max(1, Math.floor((containerWidth + gapPx) / (minPx + gapPx)))
}

interface Layout {
  columns: number
  gapPx: number
  columnWidthPx: number
  /** Container's offset from the document top — `useWindowVirtualizer`'s
   *  `scrollMargin`, needed since this grid isn't at the top of the page. */
  scrollMarginPx: number
  /** False only for the placeholder used before the first real measurement.
   *  `columns` alone can't signal "not yet measured" — the `large` density
   *  legitimately measures to 1 column too — so this needs its own flag. */
  measured: boolean
}

const INITIAL_LAYOUT: Layout = { columns: 1, gapPx: 14, columnWidthPx: 0, scrollMarginPx: 0, measured: false }

/**
 * Windowed replacement for PosterWall on the library's flat (non-franchise-
 * grouped) grid path — the only place a real library's poster count gets
 * large enough for unvirtualized DOM cost to matter (see docs/web-perf-audit.md
 * #6). FranchiseSections and the ledger `<table>` are left as plain PosterWall
 * / LedgerList: interleaved section headers need a variable-size virtualizer,
 * and a ledger row is far cheaper than a poster.
 *
 * Reimplements `.poster-wall`'s CSS Grid column math in JS (see the two
 * `compute*` functions above) rather than measuring rendered cells, because a
 * virtualizer needs to know the row layout *before* deciding what to render —
 * there's no already-painted cell to measure on a cold load. Keep these in
 * sync with index.css's `.poster-wall` rules and POSTER_GRID_DENSITY if either
 * changes. All of that layout math lives in `layout` state, computed inside a
 * ResizeObserver effect — never read off `containerRef.current` during render
 * itself (the `react-hooks/refs` rule, and the underlying React Compiler
 * assumption, forbid that).
 *
 * Uses `useWindowVirtualizer` (not an inner scroll container) because the
 * page itself scrolls — including under Lenis (`useSmoothScroll`), which
 * drives real `window.scrollTo()` calls each frame rather than faking scroll
 * with a transform, so native `scroll` events (what the virtualizer listens
 * for) fire exactly as they would without Lenis in the picture.
 */
export function VirtualPosterWall({ titles, gridSize }: { titles: Title[]; gridSize: GridSize }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const outings = useAppStore((s) => s.outings)

  const scheduledTitleIds = useMemo(
    () => new Set(outings.filter((o) => o.status === 'scheduled').map((o) => o.titleId)),
    [outings]
  )
  const delays = useMemo(() => staggerDelays(titles.length), [titles.length])

  const containerRef = useRef<HTMLDivElement>(null)
  const density = POSTER_GRID_DENSITY[gridSize]
  const [layout, setLayout] = useState<Layout>(INITIAL_LAYOUT)

  // Layout effect (not a regular effect): the placeholder columns:1 layout
  // above should never actually reach the screen — measuring synchronously
  // before paint means the real column count is what the first frame shows.
  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el) return
    function recompute() {
      const gapPx = computeGapPx(window.innerWidth)
      const columns = computeColumns(el!.clientWidth, window.innerWidth, density.minPx, density.cols, gapPx)
      const columnWidthPx = (el!.clientWidth - (columns - 1) * gapPx) / columns
      const scrollMarginPx = el!.offsetTop
      setLayout((prev) =>
        prev.measured && prev.columns === columns && prev.gapPx === gapPx && prev.columnWidthPx === columnWidthPx && prev.scrollMarginPx === scrollMarginPx
          ? prev
          : { columns, gapPx, columnWidthPx, scrollMarginPx, measured: true }
      )
    }
    recompute()
    const observer = new ResizeObserver(recompute)
    observer.observe(el)
    window.addEventListener('resize', recompute)
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', recompute)
    }
  }, [density.minPx, density.cols])

  const { columns, gapPx, columnWidthPx, scrollMarginPx, measured } = layout
  const rows = useMemo(() => {
    const out: Title[][] = []
    for (let i = 0; i < titles.length; i += columns) out.push(titles.slice(i, i + columns))
    return out
  }, [titles, columns])

  // aspect-ratio: 2/3 on .poster → height = width * 1.5.
  const rowHeightPx = columnWidthPx > 0 ? columnWidthPx * 1.5 : 240

  const virtualizer = useWindowVirtualizer({
    count: rows.length,
    estimateSize: () => rowHeightPx + gapPx,
    overscan: 3,
    scrollMargin: scrollMarginPx,
  })

  // Titles rendered in the very first commit keep playing their entrance
  // animation/stagger forever (staggerDelays' existing per-title lookup
  // already handles that); anything mounted afterward — a row scrolled into
  // view, or a new title revealed by a filter change — gets poster--no-enter
  // instead of replaying it (index.css). A *set of ids*, captured once, not a
  // boolean: a boolean would flip for every cell including the ones already
  // mid-animation from that first commit, cutting their animation short the
  // moment it flips (confirmed empirically — this was the first design tried
  // here and it visibly interrupted the entrance fade).
  const [initialIds, setInitialIds] = useState<Set<string> | null>(null)
  useEffect(() => {
    // Wait for the real measurement (see `measured` above) — capturing
    // against the columns:1 placeholder would chunk titles into the wrong
    // rows and record the wrong "initial" ids entirely (caught empirically:
    // nearly every genuinely-initial poster came back marked poster--no-enter).
    if (!measured || initialIds !== null) return
    const ids = new Set(virtualizer.getVirtualItems().flatMap((vi) => rows[vi.index]?.map((t) => t.id) ?? []))
    // Deferred to a macrotask so setInitialIds doesn't fire synchronously
    // within the effect body (react-hooks/set-state-in-effect).
    const t = setTimeout(() => setInitialIds(ids), 0)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [measured])

  if (titles.length === 0) return null

  return (
    <div
      ref={containerRef}
      className="poster-wall poster-wall--virtual"
      style={{ position: 'relative', height: virtualizer.getTotalSize(), display: 'block' }}
    >
      {virtualizer.getVirtualItems().map((virtualRow) => {
        const row = rows[virtualRow.index]
        return (
          <div
            key={virtualRow.key}
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: '100%',
              height: rowHeightPx,
              transform: `translateY(${virtualRow.start - scrollMarginPx}px)`,
              display: 'grid',
              gridTemplateColumns: `repeat(${columns}, 1fr)`,
              gap: `0 ${gapPx}px`,
            }}
          >
            {row.map((title, colIndex) => {
              const titleIndex = virtualRow.index * columns + colIndex
              // null (not yet captured) means we're still inside the first
              // commit — everything rendered right now IS the initial set.
              const isEntering = initialIds === null || initialIds.has(title.id)
              return (
                <DynamicPoster
                  key={title.id}
                  title={title}
                  rich
                  hasScheduledOuting={scheduledTitleIds.has(title.id)}
                  onClick={() => openDetailDrawer(title.id)}
                  className={isEntering ? undefined : 'poster--no-enter'}
                  style={{ ['--poster-delay' as string]: `${delays[titleIndex] ?? 0}ms` }}
                  sizes={`${Math.round(columnWidthPx || density.minPx)}px`}
                />
              )
            })}
          </div>
        )
      })}
    </div>
  )
}
