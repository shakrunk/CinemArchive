// ─── On the air (TV networks) ─────────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { Panel, PanelEmpty } from '../PanelShell'

export function OnTheAir({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)

  const networks = useMemo(() => {
    const counts = new Map<string, number>()
    for (const t of titles) {
      if (t.type === 'tv' && t.network) counts.set(t.network, (counts.get(t.network) ?? 0) + 1)
    }
    return [...counts.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 6)
      .map(([network, count]) => ({ network, count }))
  }, [titles])

  const maxCount = networks[0]?.count ?? 1

  return (
    <Panel title="On the air" hint="where the series live" className={className}>
      {networks.length === 0 ? (
        <PanelEmpty message="No series with a network yet" />
      ) : (
        <div className="flex flex-col gap-1">
          {networks.map((n, i) => (
            <button
              key={n.network}
              onClick={() => {
                setFilter('networks', [n.network])
                requestView('library')
              }}
              className="w-full flex items-center gap-3 px-1.5 py-2 rounded-md transition-colors hover:bg-[var(--wash)] text-left cursor-pointer group"
            >
              <span
                className="font-serif text-sm font-medium text-paper truncate w-[38%] shrink-0 group-hover:underline decoration-amber/40"
                style={{ fontVariationSettings: '"opsz" 30' }}
              >
                {n.network}
              </span>
              <span className="flex-1 h-[12px] rounded-sm bg-[var(--wash)] overflow-hidden">
                <span
                  className="block h-full rounded-sm bar-fill"
                  style={{
                    width: `${(n.count / maxCount) * 100}%`,
                    background: i === 0
                      ? 'linear-gradient(90deg, var(--amber-deep), var(--amber-bright))'
                      : 'rgba(128,115,95,0.55)',
                    animationDelay: `${i * 70}ms`,
                  }}
                />
              </span>
              <span className="font-mono text-[11px] text-paper-dim w-6 text-right shrink-0">{n.count}</span>
            </button>
          ))}
        </div>
      )}
    </Panel>
  )
}
