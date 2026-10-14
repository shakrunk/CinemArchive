// Shared card shell + empty state + small label helpers used across the
// Ledger's panels. Split out of Ledger.tsx so each panel lives in its own file.

import { useRef, useState } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, SECONDARY_AMBER_BUTTON } from 'src/lib/utils'
import { CinemaModal } from 'src/components/ui/cinema-modal'

export interface PanelData {
  columns: string[]
  rows: ReadonlyArray<ReadonlyArray<string | number>>
}

export function Panel({
  title,
  hint,
  className,
  children,
  data,
}: {
  title: string
  hint: string
  className?: string
  children: React.ReactNode
  data?: PanelData
}) {
  return (
    <article className={cn('panel flex h-full min-h-0 flex-col overflow-hidden p-6', className)}>
      <header className="panel__head mb-5 shrink-0">
        <h2 className="panel__title text-[21px]">{title}</h2>
        <span className="panel__hint">{hint}</span>
        {data && data.rows.length > 0 && <PanelDataButton title={title} hint={hint} data={data} />}
      </header>
      <div className="panel__body flex min-h-0 flex-1 flex-col justify-center">{children}</div>
    </article>
  )
}

function PanelDataButton({ title, hint, data }: { title: string; hint: string; data: PanelData }) {
  const session = useAppStore((s) => s.librarySession)
  const [openedFor, setOpenedFor] = useState<number | null>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  return <>
    <button ref={trigger} type="button" data-ledger-control
      className="mt-2 rounded text-xs text-amber underline underline-offset-4 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-amber"
      aria-label={`View data for ${title}`} onClick={() => setOpenedFor(session)}>
      View data
    </button>
    <CinemaModal open={openedFor !== null && openedFor === session} title={`${title} data`} description={hint}
      onClose={() => { setOpenedFor(null); requestAnimationFrame(() => trigger.current?.focus()) }}>
      <div className="min-h-0 overflow-auto p-6 pt-14" role="region" aria-label={`${title} data table`} tabIndex={0}>
        <table className="w-full text-left text-sm text-paper">
          <caption className="mb-4 text-left"><span className="block font-serif text-xl">{title}</span><span className="text-xs text-paper-faint">{hint}</span></caption>
          <thead><tr>{data.columns.map((column) => <th key={column} scope="col" className="border-b border-border p-2">{column}</th>)}</tr></thead>
          <tbody>{data.rows.map((row, index) => <tr key={index}>
            {row.map((value, cell) => cell === 0
              ? <th key={cell} scope="row" className="border-b border-border p-2 font-normal">{value}</th>
              : <td key={cell} className="border-b border-border p-2 tabular-nums">{value}</td>)}
          </tr>)}</tbody>
        </table>
      </div>
    </CinemaModal>
  </>
}

// Shared empty-state body for panels with no data yet.
export function PanelEmpty({ message }: { message: string }) {
  const requestView = useAppStore((s) => s.requestView)
  return (
    <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3 py-8">
      <p className="text-center text-sm text-paper-faint">{message}</p>
      <button onClick={() => requestView('library')} className={SECONDARY_AMBER_BUTTON}>
        Browse Library
      </button>
    </div>
  )
}

// Ranked-row title span shared by list-style panels (directors, genres,
// languages, networks, …). Callers append their own layout classes (width,
// truncate, block/flex placement) — those legitimately vary per panel.
export function RowTitle({
  className,
  children,
}: {
  className?: string
  children: React.ReactNode
}) {
  return (
    <span
      className={cn('font-serif text-sm font-medium text-paper', className)}
      style={{ fontVariationSettings: '"opsz" 30' }}
    >
      {children}
    </span>
  )
}

// Hover/click chrome shared by clickable panel rows. Callers add their own
// layout classes (flex/grid, gap, w-full) and may override `py-2` via twMerge.
export const LIST_ROW_HOVER =
  'px-1.5 py-2 rounded-md transition-colors hover:bg-(--wash) text-left cursor-pointer group'

// Zero-padded ordinal rank badge ("01", "02", …) used by ranked-list panels.
export function RankBadge({ rank, className }: { rank: number; className?: string }) {
  return (
    <span className={cn('font-mono text-xs text-amber-deep', className)}>
      {String(rank).padStart(2, '0')}
    </span>
  )
}

// One-line stat recap under a panel's chart.
export const FOOTER_CAPTION = 'font-mono text-[10px] tracking-[0.16em] uppercase text-paper-faint'

// Shared keyframe reference for the "grow from baseline" column-chart entrance.
export const COL_GROW_ANIMATION = 'col-grow 0.7s var(--ease) forwards'
