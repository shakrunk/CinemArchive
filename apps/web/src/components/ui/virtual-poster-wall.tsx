import { useLayoutEffect, useMemo, useState } from 'react'
import { useWindowVirtualizer } from '@tanstack/react-virtual'
import { useAppStore } from 'src/store/useAppStore'
import { POSTER_GRID_DENSITY } from 'src/lib/posterGridDensity'
import { posterRows, sectionName, type LibrarySection } from 'src/lib/libraryRows'
import { useLibraryFocus, useLibraryWindow } from 'src/lib/useLibraryWindow'
import { staggerDelays } from 'src/lib/utils'
import { DynamicPoster } from './dynamic-poster'
import type { Title } from 'src/store/mockData'
import type { GridSize } from 'src/store/useAppStore'

function LibraryPoster({ title, allowEntrance, onEnter, onOpen, scheduled, delay, width }: {
  title: Title; allowEntrance: boolean; onEnter: () => void; onOpen: () => void; scheduled: boolean; delay: number; width: number
}) {
  // Freeze the decision for this mount. Recording animation start must not
  // interrupt the current entrance, but a later remount must not repeat it.
  const [animate] = useState(allowEntrance)
  return <div onAnimationStart={event => { if (event.animationName === 'poster-in') onEnter() }}>
    <DynamicPoster title={title} rich hasScheduledOuting={scheduled} onClick={onOpen}
      className={animate ? undefined : 'poster--no-enter'}
      style={{ ['--poster-delay' as string]: `${delay}ms` }} sizes={`${Math.round(width)}px`} />
  </div>
}

/** One window across franchise headings and poster rows bounds DOM size even
 * when a collection contains hundreds of small franchises. */
export function VirtualPosterWall({ titles, gridSize, sections }: { titles: Title[]; gridSize: GridSize; sections?: LibrarySection[] }) {
  const openDetailDrawer = useAppStore(s => s.openDetailDrawer)
  const outings = useAppStore(s => s.outings)
  const scheduledTitleIds = useMemo(() => new Set(outings.filter(o => o.status === 'scheduled').map(o => o.titleId)), [outings])
  const { ref, width, viewportWidth, offset } = useLibraryWindow()
  const { rangeExtractor, setFocusedIndex } = useLibraryFocus()
  const density = POSTER_GRID_DENSITY[gridSize]
  // Keep in sync with .poster-wall's mobile columns and desktop auto-fill.
  const gap = viewportWidth < 640 ? 12 : Math.min(22, Math.max(14, viewportWidth * 0.016))
  const columns = viewportWidth < 640 ? density.cols : Math.max(1, Math.floor((width + gap) / (density.minPx + gap)))
  const columnWidth = Math.max(0, (width - (columns - 1) * gap) / columns)
  const rowHeight = columnWidth * 1.5
  const rows = useMemo(() => posterRows(titles, columns, sections), [titles, columns, sections])
  const delays = useMemo(() => new Map((sections ?? [{ titles }]).flatMap(section => {
    const stagger = staggerDelays(section.titles.length)
    return section.titles.map((title, index) => [title.id, stagger[index]] as const)
  })), [titles, sections])
  const virtualizer = useWindowVirtualizer({
    // Row measurements can update during React's layout phase on reflow.
    useFlushSync: false,
    count: rows.length,
    getItemKey: index => rows[index].key,
    estimateSize: index => {
      const row = rows[index]
      return row.kind === 'heading' ? (row.first ? 44 : 84) : rowHeight + (row.last ? 0 : gap)
    },
    scrollMargin: offset,
    overscan: 3,
    rangeExtractor,
  })
  // Discard measurements cached at an old density/width.
  useLayoutEffect(() => { virtualizer.measure() }, [virtualizer, columnWidth, gap])

  // Finish each entrance once. Remounted titles cannot replay it on scrolling.
  const [entered, setEntered] = useState<Set<string>>(() => new Set())
  const [entranceOpen, setEntranceOpen] = useState(true)
  useLayoutEffect(() => {
    const timer = window.setTimeout(() => setEntranceOpen(false), 1200)
    return () => window.clearTimeout(timer)
  }, [])

  return (
    <div ref={ref} className="poster-wall poster-wall--virtual" style={{ position: 'relative', height: virtualizer.getTotalSize(), display: 'block' }}
      onFocusCapture={event => {
        const index = (event.target as HTMLElement).closest<HTMLElement>('[data-index]')?.dataset.index
        if (index !== undefined) setFocusedIndex(Number(index))
      }}
      onBlurCapture={event => { if (!event.currentTarget.contains(event.relatedTarget)) setFocusedIndex(null) }}
    >
      {virtualizer.getVirtualItems().map(item => {
        const row = rows[item.index]
        return (
          <div key={item.key} data-index={item.index} ref={virtualizer.measureElement}
            style={{ position: 'absolute', top: 0, left: 0, width: '100%', transform: `translateY(${item.start - offset}px)` }}>
            {row.kind === 'heading' ? (
              <div className="flex items-baseline gap-3 pb-4" style={{ paddingTop: row.first ? 0 : 40 }}>
                <h3 className="font-serif text-xl font-light text-paper min-w-0 break-words">{sectionName(row.section)}</h3>
                <span className="font-mono text-[11px] text-paper-faint whitespace-nowrap">{row.section.titles.length} title{row.section.titles.length !== 1 ? 's' : ''}</span>
                <div className="flex-1 h-px self-center" style={{ background: 'var(--line)' }} />
              </div>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`, columnGap: gap, paddingBottom: row.last ? 0 : gap }}>
                {row.titles.map(title => (
                  <LibraryPoster key={title.id} title={title} allowEntrance={entranceOpen && !entered.has(title.id)}
                    onEnter={() => setEntered(previous => new Set(previous).add(title.id))}
                    onOpen={() => openDetailDrawer(title.id)} scheduled={scheduledTitleIds.has(title.id)}
                    delay={delays.get(title.id) ?? 0} width={columnWidth || density.minPx} />
                ))}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}
