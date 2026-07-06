// Loading placeholder for the Ledger while the library is fetched — panel-
// shaped shimmer cards instead of a misleading flash of empty-state copy.

import { LEDGER_PANEL_STANDARD_HEIGHT } from 'src/lib/ledgerPanels'

const SKELETON_SPANS = [
  'col-span-12 lg:col-span-8',
  'col-span-12 lg:col-span-4',
  'col-span-12 lg:col-span-6',
  'col-span-12 lg:col-span-6',
  'col-span-12',
]

export function LedgerSkeleton() {
  return (
    <div aria-busy="true" aria-label="Loading the ledger">
      {/* Hero stubs */}
      <div className="mb-[clamp(28px,4vw,44px)]">
        <div className="skeleton-shimmer h-3 w-44 rounded-md" />
        <div className="skeleton-shimmer h-[clamp(40px,8vw,72px)] w-[min(560px,80%)] rounded-lg mt-4" />
        <div className="skeleton-shimmer h-4 w-[min(420px,65%)] rounded-md mt-4" />
      </div>
      {/* Ribbon stubs */}
      <div className="flex items-start gap-12 overflow-hidden pb-3 mb-[clamp(24px,4vw,40px)] border-b border-[var(--line)]">
        {Array.from({ length: 5 }, (_, i) => (
          <div key={i} className="shrink-0">
            <div className="skeleton-shimmer h-9 w-16 rounded-md" />
            <div className="skeleton-shimmer h-2.5 w-20 rounded-md mt-2" />
          </div>
        ))}
      </div>
      {/* Panel-shaped cards */}
      <div className="grid grid-cols-12 gap-4">
        {SKELETON_SPANS.map((span, i) => (
          <div
            key={i}
            className={`${span} skeleton-shimmer rounded-xl border border-[var(--line)]`}
            style={{ height: LEDGER_PANEL_STANDARD_HEIGHT }}
          />
        ))}
      </div>
    </div>
  )
}
