// ─── The auteurs (directors) ──────────────────────────────────────────────────

import { useAppStore } from 'src/store/useAppStore'
import { RadialRing } from 'src/components/LedgerCharts'
import { Panel, PanelEmpty } from '../PanelShell'

export function TheAuteurs({ className }: { className?: string }) {
  const directors = useAppStore((s) => s.stats.topDirectors)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const maxCount = directors[0]?.count ?? 1
  if (directors.length === 0) {
    return (
      <Panel title="The auteurs" hint="most-watched directors" className={className}>
        <PanelEmpty message="No directors yet" />
      </Panel>
    )
  }

  return (
    <Panel title="The auteurs" hint="most-watched directors" className={className}>
      <ol className="flex flex-col gap-1">
        {directors.map((d, i) => {
          const pct = d.count / maxCount
          const color = i === 0 ? 'var(--amber-bright)' : 'rgba(128,115,95,0.6)'
          return (
            <li key={d.director}>
              <button
                onClick={() => {
                  setFilter('search', d.director)
                  requestView('library')
                }}
                className="w-full flex items-center gap-3 px-1.5 py-2.5 rounded-md transition-colors hover:bg-[var(--wash)] text-left cursor-pointer group"
              >
                <span className="font-mono text-xs text-amber-deep w-5 shrink-0">{String(i + 1).padStart(2, '0')}</span>
                <span
                  className="font-serif text-base font-medium text-paper truncate flex-1 min-w-0 group-hover:underline decoration-amber/40"
                  style={{ fontVariationSettings: '"opsz" 30' }}
                >
                  {d.director}
                </span>
                <span className="relative w-9 h-9 shrink-0">
                  <RadialRing pct={pct} size={36} stroke={4} color={color} delay={i * 60} />
                  <span className="absolute inset-0 flex items-center justify-center font-mono text-[10px] text-paper-dim">
                    {d.count}
                  </span>
                </span>
              </button>
            </li>
          )
        })}
      </ol>
    </Panel>
  )
}
