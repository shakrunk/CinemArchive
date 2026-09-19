import { Fragment, useId, useLayoutEffect, useMemo } from 'react'
import { useWindowVirtualizer } from '@tanstack/react-virtual'
import { useAppStore } from 'src/store/useAppStore'
import { ledgerRows, sectionName, type LibrarySection } from 'src/lib/libraryRows'
import { useLibraryFocus, useLibraryWindow } from 'src/lib/useLibraryWindow'
import { cn } from 'src/lib/utils'
import type { Title, WatchStatus } from 'src/store/mockData'
import { Eyebrow } from './typography'

const STATUS_DOT: Record<WatchStatus, string> = {
  watched: 'bg-amber', watching: 'bg-amber-bright', watchlist: 'bg-moon', dropped: 'bg-ember',
}

/** Native table rows retain variable heights for wrapped titles. Spacer rows
 * preserve document scrolling, including gaps around a retained keyboard focus. */
export function VirtualLedgerList({ titles, sections }: { titles: Title[]; sections?: LibrarySection[] }) {
  const openDetailDrawer = useAppStore(s => s.openDetailDrawer)
  const { ref, width, viewportWidth, offset } = useLibraryWindow()
  const wide = viewportWidth >= 640
  const headerId = useId()
  const { rangeExtractor, setFocusedIndex } = useLibraryFocus()
  const rows = useMemo(() => ledgerRows(titles, sections), [titles, sections])
  const virtualizer = useWindowVirtualizer({
    useFlushSync: false,
    count: rows.length,
    getItemKey: index => rows[index].key,
    estimateSize: index => rows[index].kind === 'heading' ? 84 : rows[index].kind === 'columns' ? 44 : 72,
    scrollMargin: offset,
    overscan: 6,
    rangeExtractor,
  })
  useLayoutEffect(() => { virtualizer.measure() }, [virtualizer, width])
  const items = virtualizer.getVirtualItems()
  const columns = wide ? ['No.', 'Title', 'Year', 'Status', 'Rating'] : ['Title', 'Status', 'Rating']
  const spacer = (height: number) => height > 0.5 ? <tr aria-hidden="true"><td colSpan={columns.length} style={{ height, padding: 0, border: 0 }} /></tr> : null

  return (
    <div ref={ref} className="rounded-xl overflow-x-auto" data-lenis-prevent-horizontal
      style={{ border: '1px solid var(--line)', background: 'linear-gradient(180deg, var(--ink-1), rgba(17,13,11,0.4))' }}
      onFocusCapture={event => {
        const index = (event.target as HTMLElement).closest<HTMLElement>('[data-index]')?.dataset.index
        if (index !== undefined) setFocusedIndex(Number(index))
      }}
      onBlurCapture={event => { if (!event.currentTarget.contains(event.relatedTarget)) setFocusedIndex(null) }}
    >
      <table className="w-full border-collapse table-fixed sm:min-w-[640px]" aria-label="Library titles" aria-rowcount={rows.length + 1}>
        <colgroup>
          {wide && <col className="w-[60px]" />}<col />{wide && <col className="w-20" />}<col className="w-20 sm:w-32" /><col className="w-12 sm:w-24" />
        </colgroup>
        <thead className="sr-only"><tr>{columns.map(label => <th key={label} id={`${headerId}-${label}`} scope="col">{label}</th>)}</tr></thead>
        <tbody>
          {items.map((item, index) => {
            const row = rows[item.index]
            const before = item.start - (index === 0 ? offset : items[index - 1].end)
            return (
              <Fragment key={item.key}>
                {spacer(before)}
                <tr ref={virtualizer.measureElement} data-index={item.index} aria-rowindex={item.index + 2} aria-hidden={row.kind === 'columns' || undefined}
                  onClick={row.kind === 'title' ? () => openDetailDrawer(row.title.id) : undefined}
                  className={row.kind === 'title' ? 'cursor-pointer transition-colors hover:bg-amber/5 focus-within:bg-amber/10' : undefined}
                  style={{ borderBottom: row.kind === 'title' ? '1px solid var(--line)' : undefined }}>
                  {row.kind === 'heading' ? (
                    <th colSpan={columns.length} scope="rowgroup" className="text-left px-3 sm:px-4 pb-4" style={{ paddingTop: row.first ? 16 : 40 }}>
                      <div className="flex items-baseline gap-3">
                        <h3 className="font-serif text-xl font-light text-paper min-w-0 break-words">{sectionName(row.section)}</h3>
                        <span className="font-mono text-[11px] font-normal text-paper-faint whitespace-nowrap">{row.section.titles.length} title{row.section.titles.length !== 1 ? 's' : ''}</span>
                      </div>
                    </th>
                  ) : row.kind === 'columns' ? columns.map(label => (
                    <th key={label} scope="col" className="text-left px-2 sm:px-4 py-3.5 whitespace-nowrap"
                      style={{ borderBottom: '1px solid var(--line-2)', background: 'var(--inset)' }}>
                      <Eyebrow size="md">{label}</Eyebrow>
                    </th>
                  )) : (
                    <>
                      {wide && <td headers={`${headerId}-No.`} className="px-4 py-3 font-mono text-[11px] text-paper-faint">{String(row.number).padStart(2, '0')}</td>}
                      <td headers={`${headerId}-Title`} className="px-2 sm:px-4 py-3">
                        <button type="button" onClick={event => { event.stopPropagation(); openDetailDrawer(row.title.id) }}
                          aria-label={`View details for ${row.title.title}`}
                          className="text-left w-full rounded font-serif text-[17px] font-medium text-paper break-words focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                          style={{ fontVariationSettings: '"opsz" 30' }}>{row.title.title}</button>
                        {row.title.director && <div className="font-sans text-xs text-paper-faint truncate">{row.title.director}</div>}
                      </td>
                      {wide && <td headers={`${headerId}-Year`} className="px-4 py-3 font-mono text-xs text-paper-dim">{row.title.year}</td>}
                      <td headers={`${headerId}-Status`} className="px-2 sm:px-4 py-3">
                        <Eyebrow size="md" tone="dim" className="inline-flex items-center gap-1.5">
                          <span aria-hidden="true" className={cn('w-[7px] h-[7px] rounded-full shrink-0', STATUS_DOT[row.title.status])} />{row.title.status}
                        </Eyebrow>
                      </td>
                      <td headers={`${headerId}-Rating`} className="px-2 sm:px-4 py-3 font-mono text-sm text-amber whitespace-nowrap">{row.title.rating ? `★ ${row.title.rating}` : '—'}</td>
                    </>
                  )}
                </tr>
              </Fragment>
            )
          })}
          {spacer(virtualizer.getTotalSize() - ((items.at(-1)?.end ?? offset) - offset))}
        </tbody>
      </table>
    </div>
  )
}
