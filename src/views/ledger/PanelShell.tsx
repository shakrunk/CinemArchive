// Shared card shell + empty state + small label helpers used across the
// Ledger's panels. Split out of Ledger.tsx so each panel lives in its own file.

import { useAppStore } from 'src/store/useAppStore'
import { cn, SECONDARY_AMBER_BUTTON } from 'src/lib/utils'

export function Panel({
  title,
  hint,
  className,
  children,
}: {
  title: string
  hint: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <article className={cn('panel p-6', className)}>
      <header className="panel__head mb-5">
        <h2 className="panel__title text-[21px]">{title}</h2>
        <span className="panel__hint">{hint}</span>
      </header>
      {children}
    </article>
  )
}

// Shared empty-state body for panels with no data yet.
export function PanelEmpty({ message }: { message: string }) {
  const requestView = useAppStore((s) => s.requestView)
  return (
    <div className="flex flex-col items-center justify-center py-8 gap-3">
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
export function RowTitle({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <span className={cn('font-serif text-sm font-medium text-paper', className)} style={{ fontVariationSettings: '"opsz" 30' }}>
      {children}
    </span>
  )
}

// Hover/click chrome shared by clickable panel rows. Callers add their own
// layout classes (flex/grid, gap, w-full) and may override `py-2` via twMerge.
export const LIST_ROW_HOVER = 'px-1.5 py-2 rounded-md transition-colors hover:bg-[var(--wash)] text-left cursor-pointer group'

// Zero-padded ordinal rank badge ("01", "02", …) used by ranked-list panels.
export function RankBadge({ rank, className }: { rank: number; className?: string }) {
  return <span className={cn('font-mono text-xs text-amber-deep', className)}>{String(rank).padStart(2, '0')}</span>
}

// One-line stat recap under a panel's chart.
export const FOOTER_CAPTION = 'font-mono text-[10px] tracking-[0.16em] uppercase text-paper-faint'

// Shared keyframe reference for the "grow from baseline" column-chart entrance.
export const COL_GROW_ANIMATION = 'col-grow 0.7s var(--ease) forwards'

